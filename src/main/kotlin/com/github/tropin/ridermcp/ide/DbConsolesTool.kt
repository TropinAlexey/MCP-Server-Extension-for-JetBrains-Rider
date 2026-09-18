package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.runOnEdt
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.fileEditor.FileEditorManager
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class DbConsolesToolset : McpToolset {

    @McpTool
    @McpDescription("Lists database consoles (SQL query consoles) currently open in the IDE, each bound to its data source. Call this FIRST when the user says a DB console is open, or before scanning servers with list_schemas — the open console already identifies the right database. Returns console file name plus data source name, uniqueId (pass it as connectionId to the database tools like execute_sql_query), DBMS, and connection URL.")
    suspend fun rider_list_db_consoles(): String {
        val project = coroutineContext.project
        return runOnEdt {
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
            if (consoles.isEmpty()) {
                "[]"
            } else {
                buildJsonObject {
                    put("count", consoles.size)
                    putJsonArray("consoles") { consoles.forEach { add(it) } }
                }.toString()
            }
        }
    }
}
