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
    @McpDescription("Lists open SQL consoles (editor tabs bound to a data source) with console file name, data source name + uniqueId (use as connectionId), DBMS, URL and currentDatabase (console toolbar dropdown). Call FIRST when the user mentions an open console, or before introspecting servers — the open console already identifies the right database. Returns [] when nothing is open (open a console via double-click in the Database tool window first).")
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
    @McpDescription("Executes SQL using an open console's data source (resolve names via rider_list_db_consoles) and returns CSV text. Multi-statement input (semicolon-separated) runs statement-by-statement, each with its own result set — no silent loss. Needs NO IDE introspection: address other DBs with three-part names (db.schema.table); catalog per dialect — MSSQL SELECT name FROM sys.databases, Postgres SELECT datname FROM pg_database, MySQL SHOW DATABASES. Execution DB = console's currentDatabase by default, or pin with database='<name>'. Default to SELECT; confirm INSERT/UPDATE/DELETE/DDL with the user first. Slice heavy queries (TOP, date/id ranges, EXISTS instead of COUNT LIKE over CAST) and page with pageSize; hasMore=true means truncated — re-query with TOP/OFFSET plus ORDER BY (OFFSET without ORDER BY is nondeterministic). Prefer over built-in execute_sql_query (no multi-statement support).")
    suspend fun rider_execute_console(
        @McpDescription("SQL statement(s); ';'-separated statements each return their own result set") sql: String,
        @McpDescription("Open console file name from rider_list_db_consoles (omit = first open console)") console: String? = null,
        @McpDescription("Rows per page, 1-5000, default 200. hasMore=true means truncated — narrow with TOP/OFFSET or raise pageSize") pageSize: Int = 200,
        @McpDescription("Execution database to pin (omit = console's currentDatabase toolbar value)") database: String? = null
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
