package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.runOnEdt
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

// NOTE: this toolset must not reference database-plugin classes directly.
// They live in DbConsoleReader (separate class) so the DatabaseTools plugin
// is only loaded on first actual tool use — never during MCP tool registration.
class DbConsolesToolset : McpToolset {

    @McpTool
    @McpDescription("Lists database consoles (SQL query consoles) currently open in the IDE, each bound to its data source. Call this FIRST when the user says a DB console is open, or before scanning servers with list_schemas — the open console already identifies the right database. Returns console file name plus data source name, uniqueId (pass it as connectionId to the database tools like execute_sql_query), DBMS, and connection URL. If the target database is not introspected in the IDE model, use any introspected database as the execution context and address tables with three-part names (db.schema.table); confirm the real database list with SELECT name FROM sys.databases — never trust a filtered schema list. Always confirm the database context BEFORE running expensive queries.")
    suspend fun rider_list_db_consoles(): String {
        val project = coroutineContext.project
        return runOnEdt {
            try {
                DbConsoleReader.listOpenConsoles(project)
            } catch (_: NoClassDefFoundError) {
                "[]" // Database Tools plugin not available
            } catch (_: Exception) {
                "[]"
            }
        }
    }

    @McpTool
    @McpDescription("Executes SQL in the context of an open database console's data source and returns the result as CSV text. Use when the user has a console open (find it with rider_list_db_consoles) instead of scanning servers. Runs without requiring IDE introspection of the target database — address tables with three-part names (db.schema.table) when needed. Execution database: the console's current one (its toolbar dropdown) by default, or pass database='<name>' to pin it explicitly. For heavy queries, slice the work (TOP, date/id ranges, EXISTS instead of COUNT LIKE over CAST) and page large results.")
    suspend fun rider_execute_console(
        @McpDescription("SQL statement to execute") sql: String,
        @McpDescription("Console file name (omit for the first open console). Use rider_list_db_consoles to discover names") console: String? = null,
        @McpDescription("Rows per page (default 200)") pageSize: Int = 200,
        @McpDescription("Execution database name (omit to use the console's current database from its toolbar dropdown)") database: String? = null
    ): String {
        val project = coroutineContext.project
        val resolved = runOnEdt { DbConsoleReader.resolveConsole(project, console) }
            ?: mcpFail(
                if (console.isNullOrBlank()) "No open database console found. Open one in Rider first (double-click it in the Database tool window)."
                else "Console '$console' is not open. Open consoles: ${runOnEdt { DbConsoleReader.openConsoleNames(project) }}"
            )

        val result = try {
            DbConsoleReader.executeSql(project, resolved.dataSource, sql, pageSize.coerceIn(1, 5000), database?.takeIf { it.isNotBlank() })
        } catch (e: IllegalStateException) {
            mcpFail(e.message ?: "Query execution failed")
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() }
                ?: e.cause?.message?.takeIf { it.isNotBlank() }
                ?: e.javaClass.simpleName
            mcpFail("Query execution failed: $detail (console='${resolved.fileName}', dataSource='${resolved.dataSource.name}')")
        }

        return buildJsonObject {
            put("console", resolved.fileName)
            put("dataSource", resolved.dataSource.name)
            put("resultSetId", result.resultSetId)
            put("text", result.text)
        }.toString()
    }
}
