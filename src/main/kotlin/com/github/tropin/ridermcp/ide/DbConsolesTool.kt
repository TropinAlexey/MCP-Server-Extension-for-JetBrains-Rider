package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.runOnEdt
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class DbConsolesToolset : McpToolset {

    @McpTool
    @McpDescription("Lists database consoles (SQL query consoles) currently open in the IDE, each bound to its data source. Call this FIRST when the user says a DB console is open, or before scanning servers with list_schemas — the open console already identifies the right database. Returns console file name plus data source name, uniqueId (pass it as connectionId to the database tools like execute_sql_query), DBMS, connection URL, and currentDatabase (the database selected in the console toolbar). If the target database is not introspected in the IDE model, use any introspected database as the execution context and address tables with three-part names (db.schema.table); confirm the real database list with SELECT name FROM sys.databases — never trust a filtered schema list. Always confirm the database context BEFORE running expensive queries.")
    suspend fun rider_list_db_consoles(): String {
        val project = coroutineContext.project
        return runOnEdt {
            try {
                DbConsoleReader.listOpenConsoles(project)
            } catch (_: NoClassDefFoundError) {
                "[]"
            } catch (_: Exception) {
                "[]"
            }
        }
    }

    @McpTool
    @McpDescription("Executes SQL in the context of an open database console's data source and returns the result as CSV text. Handles multiple statements separated by semicolons — each produces its own result set (no silent loss). Use when the user has a console open (find it with rider_list_db_consoles) instead of scanning servers. Runs without requiring IDE introspection of the target database — address tables with three-part names (db.schema.table) when needed. Execution database: the console's current one (its toolbar dropdown) by default, or pass database='<name>' to pin it explicitly. For heavy queries, slice the work (TOP, date/id ranges, EXISTS instead of COUNT LIKE over CAST) and page large results. Response includes rowCount and hasMore per result set — when hasMore=true, add TOP/OFFSET or increase pageSize to get remaining rows.")
    suspend fun rider_execute_console(
        @McpDescription("SQL statement(s) to execute — multiple statements separated by ';' are executed individually, each returning its own result set") sql: String,
        @McpDescription("Console file name (omit for the first open console). Use rider_list_db_consoles to discover names") console: String? = null,
        @McpDescription("Rows per page (default 200). Response includes hasMore=true when the result was truncated") pageSize: Int = 200,
        @McpDescription("Execution database name (omit to use the console's current database from its toolbar dropdown). Use list_database_schemas if unsure about available databases") database: String? = null
    ): String {
        val project = coroutineContext.project
        val resolved = runOnEdt { DbConsoleReader.resolveConsole(project, console) }
            ?: mcpFail(
                if (console.isNullOrBlank()) "No open database console found. Open one in Rider first (double-click it in the Database tool window)."
                else "Console '$console' is not open. Open consoles: ${runOnEdt { DbConsoleReader.openConsoleNames(project) }}"
            )

        val results = try {
            DbConsoleReader.executeSql(project, resolved.dataSource, sql, pageSize.coerceIn(1, 5000), database?.takeIf { it.isNotBlank() })
        } catch (e: IllegalStateException) {
            mcpFail(e.message ?: "Query execution failed")
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() }
                ?: e.cause?.message?.takeIf { it.isNotBlank() }
                ?: e.javaClass.simpleName
            mcpFail("Query execution failed: $detail (console='${resolved.fileName}', dataSource='${resolved.dataSource.name}')")
        }

        if (results.isEmpty()) mcpFail("No results returned")

        fun buildResultJson(r: DbConsoleReader.SqlStatementResult): JsonObject = buildJsonObject {
            put("statementIndex", r.statementIndex)
            if (r.error != null) {
                put("status", "error")
                put("error", r.error)
            } else {
                put("status", "success")
                val res = r.result!!
                put("resultSetId", res.resultSetId)
                put("text", res.text)
                put("rowCount", res.rowCount)
                put("pageSize", pageSize)
                put("hasMore", res.hasMore)
            }
        }

        return if (results.size == 1) {
            val r = results[0]
            if (r.error != null) mcpFail(r.error)
            val res = r.result!!
            buildJsonObject {
                put("console", resolved.fileName)
                put("dataSource", resolved.dataSource.name)
                put("resultSetId", res.resultSetId)
                put("text", res.text)
                put("rowCount", res.rowCount)
                put("pageSize", pageSize)
                put("hasMore", res.hasMore)
            }.toString()
        } else {
            val successCount = results.count { it.error == null }
            val errorCount = results.count { it.error != null }
            buildJsonObject {
                put("console", resolved.fileName)
                put("dataSource", resolved.dataSource.name)
                put("totalStatements", results.size)
                put("successCount", successCount)
                put("errorCount", errorCount)
                putJsonArray("resultSets") { results.forEach { add(buildResultJson(it)) } }
            }.toString()
        }
    }
}
