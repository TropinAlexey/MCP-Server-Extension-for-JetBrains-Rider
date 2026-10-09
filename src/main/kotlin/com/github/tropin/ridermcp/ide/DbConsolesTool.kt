package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.runOnEdt
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.coroutines.CancellationException
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
    @McpDescription("Executes SQL over the data source of an open console (names via rider_list_db_consoles) on a dedicated JDBC connection; returns CSV per result set. Errors carry the server text, vendor code and SQLState. Splitting: auto = T-SQL (MSSQL/Sybase) by GO lines, so DECLARE/@vars live across ';' — each batch may return several result sets; other DBMS by ';'. Execution DB = console's currentDatabase unless database='<name>'. timeoutSeconds (default 60) cancels a hung batch — the failing batch is named, later ones are skipped. Address other DBs with three-part names; catalogs: MSSQL sys.databases, Postgres pg_database, MySQL SHOW DATABASES. Default to SELECT; confirm DML/DDL with the user first. hasMore=true = page truncated: raise pageSize or page with offset (needs ORDER BY), or slice via TOP/id ranges. Long cells are cut to maxCellLength (truncatedCells>0). Prefer over built-in execute_sql_query (it hides driver errors as 'Unknown error').")
    suspend fun rider_execute_console(
        @McpDescription("SQL script; see split for how it is cut into batches") sql: String,
        @McpDescription("Open console file name from rider_list_db_consoles (omit = first open console)") console: String? = null,
        @McpDescription("Rows per result set, 1-5000, default 200") pageSize: Int = 200,
        @McpDescription("Execution database (omit = console's currentDatabase)") database: String? = null,
        @McpDescription("Rows to skip in each result set before the page (re-runs the query; pair with ORDER BY)") offset: Int = 0,
        @McpDescription("Per-batch query timeout in seconds, 0 = none, max 3600, default 60") timeoutSeconds: Int = 60,
        @McpDescription("Max chars per cell, longer values are cut with a '…[+N chars]' marker; 0 = unlimited, default 4000") maxCellLength: Int = 4000,
        @McpDescription("auto (default: GO for MSSQL/Sybase, ';' otherwise) | go | semicolon | none (send the script as one batch)") split: String = "auto"
    ): String {
        val project = coroutineContext.project
        val splitMode = SqlText.parseSplitMode(split)
            ?: mcpFail("Unknown split='$split'. Use auto, go, semicolon or none.")
        val resolved = runOnEdt { DbConsoleReader.resolveConsole(project, console) }
            ?: mcpFail(
                if (console.isNullOrBlank()) "No open database console found. Open one in Rider first (double-click it in the Database tool window)."
                else "Console '$console' is not open. Open consoles: ${runOnEdt { DbConsoleReader.openConsoleNames(project) }}"
            )
        val targetDb = database?.takeIf { it.isNotBlank() } ?: resolved.currentDatabase
        val opts = JdbcConsoleExecutor.Options(
            pageSize = pageSize.coerceIn(1, 5000),
            offset = offset.coerceAtLeast(0),
            timeoutSeconds = timeoutSeconds.coerceIn(0, 3600),
            maxCellLength = maxCellLength.coerceAtLeast(0),
            splitMode = splitMode
        )

        val outcome = try {
            JdbcConsoleExecutor.execute(project, resolved.dataSource, sql, targetDb, opts)
        } catch (e: IllegalStateException) {
            mcpFail("${e.message ?: "Query execution failed"} (console='${resolved.fileName}', dataSource='${resolved.dataSource.name}')")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mcpFail("Query execution failed: ${SqlText.describe(e)} (console='${resolved.fileName}', dataSource='${resolved.dataSource.name}')")
        }

        fun JsonObjectBuilder.putEntry(r: JdbcConsoleExecutor.Entry) {
            put("status", r.status)
            if (r.error != null) {
                put("error", r.error)
                return
            }
            if (r.updateCount != null) {
                put("updateCount", r.updateCount)
                return
            }
            put("text", r.text)
            put("rowCount", r.rowCount)
            put("hasMore", r.hasMore)
            if (r.truncatedCells > 0) put("truncatedCells", r.truncatedCells)
        }

        fun JsonObjectBuilder.putHeader() {
            put("console", resolved.fileName)
            put("dataSource", resolved.dataSource.name)
            targetDb?.let { put("database", it) }
            put("pageSize", opts.pageSize)
            if (opts.offset > 0) put("offset", opts.offset)
        }

        fun JsonObjectBuilder.putMessages() {
            if (outcome.messages.isNotEmpty()) putJsonArray("messages") { outcome.messages.forEach { add(it) } }
        }

        val entries = outcome.entries
        if (entries.size == 1 && outcome.batchCount == 1) {
            val r = entries[0]
            if (r.error != null) {
                val msgs = if (outcome.messages.isEmpty()) "" else " Server messages: ${outcome.messages.joinToString(" | ")}"
                mcpFail(r.error + msgs)
            }
            return buildJsonObject {
                putHeader()
                putEntry(r)
                putMessages()
                put("elapsedMs", outcome.elapsedMs)
            }.toString()
        }

        return buildJsonObject {
            putHeader()
            put("split", outcome.splitMode.name.lowercase())
            put("totalBatches", outcome.batchCount)
            put("successCount", entries.count { it.error == null })
            put("errorCount", entries.count { it.error != null })
            if (outcome.skippedBatches > 0) put("skippedBatches", outcome.skippedBatches)
            putJsonArray("resultSets") {
                entries.forEach { r ->
                    add(buildJsonObject {
                        put("batchIndex", r.batchIndex)
                        put("resultIndex", r.resultIndex)
                        putEntry(r)
                    })
                }
            }
            putMessages()
            put("elapsedMs", outcome.elapsedMs)
        }.toString()
    }
}
