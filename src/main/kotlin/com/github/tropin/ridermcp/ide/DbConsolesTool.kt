package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.runOnEdt
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
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
}
