package com.github.tropin.ridermcp.ide

import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.*

internal object DbConsoleReader {

    data class ResolvedConsole(val fileName: String, val dataSource: LocalDataSource, val currentDatabase: String?)

    /** Open console tab with its JdbcConsole; [ref] = `<data source>/<file>`, unique even when file names repeat. */
    data class OpenConsole(val file: VirtualFile, val console: JdbcConsole, val dataSource: LocalDataSource?) {
        val ref: String get() = dataSource?.let { "${it.name}/${file.name}" } ?: file.name
    }

    fun openConsoles(project: Project): List<OpenConsole> =
        FileEditorManager.getInstance(project).openFiles.mapNotNull { file ->
            try {
                val console = JdbcConsoleProvider.getConsole(project, file) ?: return@mapNotNull null
                OpenConsole(file, console, try { console.dataSource } catch (_: Exception) { null })
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Finds the console named [name]: `<data source>/<file>` (always unique) or a bare file name.
     * Default consoles of different data sources share a file name (console.sql), so a bare name that
     * matches several data sources is an error rather than a silent pick of the first tab — the wrong
     * tab could be a different server. Blank [name] = the selected console tab, else the only one.
     */
    fun findConsole(project: Project, name: String?): OpenConsole {
        val all = openConsoles(project)
        if (all.isEmpty()) throw IllegalArgumentException("No open database console found. Open one in Rider first (double-click it in the Database tool window, or rider_database_connection(action='open_console')).")
        val refs = all.map { it.ref }
        if (name.isNullOrBlank()) {
            val selected = FileEditorManager.getInstance(project).selectedFiles.toSet()
            all.firstOrNull { it.file in selected }?.let { return it }
            if (all.size == 1) return all[0]
            throw IllegalArgumentException("Several consoles are open — pass console=<one of $refs>.")
        }
        all.firstOrNull { it.ref.equals(name, ignoreCase = true) }?.let { return it }
        val byFile = all.filter { it.file.name.equals(name, ignoreCase = true) }
        return when {
            byFile.size == 1 -> byFile[0]
            byFile.size > 1 -> throw IllegalArgumentException("Console name '$name' is ambiguous (several data sources): use one of ${byFile.map { it.ref }}.")
            else -> throw IllegalArgumentException("Console '$name' is not open. Open consoles: $refs")
        }
    }

    fun resolveConsole(project: Project, consoleName: String?): ResolvedConsole {
        val c = findConsole(project, consoleName)
        val ds = c.dataSource ?: throw IllegalArgumentException("Console '${c.ref}' is not attached to a data source.")
        return ResolvedConsole(c.ref, ds, currentPathName(c.console, ObjectKind.DATABASE))
    }

    /** Name of the [kind] element (database / schema) in the console toolbar's current search path. */
    private fun currentPathName(console: JdbcConsole, kind: ObjectKind): String? = try {
        val current = console.searchPath?.current
        if (current == null || current == ObjectPath.CURRENT || current == ObjectPath.ROOT) null
        else current.findParent(kind, true)?.name?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun listOpenConsoles(project: Project): String {
        val fem = FileEditorManager.getInstance(project)
        val selected = fem.selectedFiles.toSet()
        val jobs = ConsoleJobService.getInstance()
        val dataSources = LocalDataSourceManager.getInstance(project).dataSources.associateBy { it.uniqueId }
        val tableEditors = mutableListOf<JsonObject>()
        val consoles = fem.openFiles.mapNotNull { file ->
            try {
                val console = JdbcConsoleProvider.getConsole(project, file)
                if (console == null) {
                    describeTableEditor(file, dataSources, file in selected)?.let(tableEditors::add)
                    return@mapNotNull null
                }
                val ds = try { console.dataSource } catch (_: Exception) { null }
                buildJsonObject {
                    put("console", file.name)
                    if (file in selected) put("selected", true)
                    (try { console.dataSource } catch (_: Exception) { null })?.let { put("consoleRef", "${it.name}/${file.name}") }
                    if (ds == null) {
                        put("dataSource", "(not attached)")
                    } else {
                        put("dataSource", ds.name)
                        put("uniqueId", ds.uniqueId)
                        val url = try { ds.url } catch (_: Exception) { null }
                        val fromConsole = currentPathName(console, ObjectKind.DATABASE)
                        val currentDb = fromConsole ?: DbEditorPaths.databaseFromUrl(url)
                        currentDb?.let { put("currentDatabase", it) }
                        put("currentDatabaseSource", when {
                            fromConsole != null -> "console"
                            currentDb != null -> "url"
                            else -> "login-default"
                        })
                        currentPathName(console, ObjectKind.SCHEMA)?.let { put("currentSchema", it) }
                        put("readOnly", runCatching { ds.isReadOnly }.getOrDefault(false))
                        jobs.running("${ds.uniqueId}|${ds.name}/${file.name}")?.let { put("busyJobId", it.id) }
                        try {
                            ds.dbms?.name?.takeIf { it.isNotBlank() }?.let { put("dbms", it) }
                        } catch (_: Exception) {}
                        url?.takeIf { it.isNotBlank() }?.let { put("url", com.github.tropin.ridermcp.maskJdbcSecrets(it)) }
                        try {
                            ds.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
        if (consoles.isEmpty() && tableEditors.isEmpty()) return "[]"
        return buildJsonObject {
            put("count", consoles.size)
            putJsonArray("consoles") { consoles.forEach { add(it) } }
            if (tableEditors.isNotEmpty()) putJsonArray("tableEditors") { tableEditors.forEach { add(it) } }
        }.toString()
    }

    /** Table/view editor tab: its virtual path starts with the data source id, then kind/name pairs. */
    private fun describeTableEditor(file: VirtualFile, dataSources: Map<String, LocalDataSource>, selected: Boolean): JsonObject? {
        val tab = DbEditorPaths.parse(file.path) ?: return null
        val ds = dataSources[tab.dataSourceId] ?: return null
        return buildJsonObject {
            put("tab", file.presentableName)
            if (selected) put("selected", true)
            put("kind", tab.kind)
            put("dataSource", ds.name)
            put("dataSourceId", ds.uniqueId)
            tab.database?.let { put("database", it) }
            tab.schema?.let { put("schema", it) }
            tab.objectName?.let { put("object", it) }
            put("readOnly", runCatching { ds.isReadOnly }.getOrDefault(false))
        }
    }
}
