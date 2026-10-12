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

    fun resolveConsole(project: Project, consoleName: String?): ResolvedConsole? {
        val files = FileEditorManager.getInstance(project).openFiles
        val candidates = if (consoleName.isNullOrBlank()) files.toList() else files.filter {
            it.name.equals(consoleName, ignoreCase = true)
        }
        for (file in candidates) {
            try {
                val console = JdbcConsoleProvider.getConsole(project, file) ?: continue
                val ds = try { console.dataSource } catch (_: Exception) { null } ?: continue
                return ResolvedConsole(file.name, ds, currentPathName(console, ObjectKind.DATABASE))
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    fun openConsoleNames(project: Project): List<String> {
        return FileEditorManager.getInstance(project).openFiles.mapNotNull { file ->
            try {
                JdbcConsoleProvider.getConsole(project, file)?.let { file.name }
            } catch (_: Exception) {
                null
            }
        }
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
                        jobs.running("${ds.uniqueId}|${file.name}")?.let { put("busyJobId", it.id) }
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
