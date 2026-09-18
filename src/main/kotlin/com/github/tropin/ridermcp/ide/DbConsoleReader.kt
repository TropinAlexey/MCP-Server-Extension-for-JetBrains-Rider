package com.github.tropin.ridermcp.ide

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

// All database-plugin references are isolated here. This class is loaded only
// when rider_list_db_consoles actually runs, keeping MCP tool registration
// (which happens on the EDT at startup) free of DatabaseTools class loading.
internal object DbConsoleReader {

    fun listOpenConsoles(project: Project): String {
        val consoles = FileEditorManager.getInstance(project).openFiles.mapNotNull { file ->
            try {
                val console = JdbcConsoleProvider.getConsole(project, file) ?: return@mapNotNull null
                val ds = try {
                    console.dataSource
                } catch (_: Exception) {
                    null
                }
                buildJsonObject {
                    put("console", file.name)
                    if (ds == null) {
                        put("dataSource", "(not attached)")
                    } else {
                        put("dataSource", ds.name)
                        put("uniqueId", ds.uniqueId)
                        try {
                            ds.dbms?.name?.takeIf { it.isNotBlank() }?.let { put("dbms", it) }
                        } catch (_: Exception) {
                        }
                        try {
                            ds.url?.takeIf { it.isNotBlank() }?.let { put("url", it) }
                        } catch (_: Exception) {
                        }
                        try {
                            ds.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                        } catch (_: Exception) {
                        }
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
