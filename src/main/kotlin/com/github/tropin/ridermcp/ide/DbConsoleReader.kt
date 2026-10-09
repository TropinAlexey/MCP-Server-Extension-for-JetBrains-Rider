package com.github.tropin.ridermcp.ide

import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
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
        val consoles = FileEditorManager.getInstance(project).openFiles.mapNotNull { file ->
            try {
                val console = JdbcConsoleProvider.getConsole(project, file) ?: return@mapNotNull null
                val ds = try { console.dataSource } catch (_: Exception) { null }
                buildJsonObject {
                    put("console", file.name)
                    if (ds == null) {
                        put("dataSource", "(not attached)")
                    } else {
                        put("dataSource", ds.name)
                        put("uniqueId", ds.uniqueId)
                        currentPathName(console, ObjectKind.DATABASE)?.let { put("currentDatabase", it) }
                        currentPathName(console, ObjectKind.SCHEMA)?.let { put("currentSchema", it) }
                        try {
                            ds.dbms?.name?.takeIf { it.isNotBlank() }?.let { put("dbms", it) }
                        } catch (_: Exception) {}
                        try {
                            ds.url?.takeIf { it.isNotBlank() }?.let { put("url", com.github.tropin.ridermcp.maskJdbcSecrets(it)) }
                        } catch (_: Exception) {}
                        try {
                            ds.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
        if (consoles.isEmpty()) return "[]"
        return buildJsonObject {
            put("count", consoles.size)
            putJsonArray("consoles") { consoles.forEach { add(it) } }
        }.toString()
    }
}
