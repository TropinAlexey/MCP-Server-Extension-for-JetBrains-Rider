package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.runOnEdt
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

// Same shape as a populated list, so clients never branch on an array vs an object.
private const val EMPTY_CONSOLE_LIST = """{"count":0,"consoles":[]}"""

class DbConsolesToolset : McpToolset {

    @McpTool
    @McpDescription("Lists open SQL consoles (editor tabs bound to a data source) with console file name, data source name + uniqueId (use as connectionId), DBMS, URL, currentDatabase + currentDatabaseSource (toolbar = picked in the console toolbar | url = the URL's database | login-default = the login's default database from the introspected model; no currentDatabase = unknown until the first rider_execute_console, whose response names the real database), readOnly (data source Read-only flag) and busyJobId when a rider_execute_console job runs there. tableEditors lists open table/view tabs with dataSource, database, schema, object and kind — use them when the user says 'the open table'; selected=true marks the active tab. Call FIRST when the user mentions an open console or table. Run SQL against any of them with rider_execute_console(console=consoleRef) — not the built-in execute_sql_query, which waits for a per-schema IDE confirmation dialog. count=0 when nothing is open (rider_database_connection(action='open_console')).")
    suspend fun rider_list_db_consoles(): String {
        val project = coroutineContext.project
        return runOnEdt {
            try {
                DbConsoleReader.listOpenConsoles(project)
            } catch (_: NoClassDefFoundError) {
                EMPTY_CONSOLE_LIST
            }
        }
    }

    @McpTool
    @McpDescription("Executes SQL over the data source of an open console (rider_list_db_consoles) on its own JDBC connection; returns CSV + columns per result set, database = the session's real DB, server errors with code/SQLState/severity/line, PRINT/RAISERROR in messages. T-SQL (MSSQL/Sybase) is split by GO lines, others by ';'. DB = console's currentDatabase unless database=. Long queries: async=true returns a jobId at once — poll rider_console_job; a sync call waits waitSeconds (default 50), then cancels the server query (onClientTimeout=cancel) or keeps it as a job (detach). A busy console answers with its jobId. readOnly (forced for Read-only data sources) rejects DML/DDL before sending, runs in a rolled-back transaction (MSSQL) / read-only session (PG); #temp and @table allowed. maskSecrets (on) hides password/secret/token values. outputFile streams all rows to .csv/.json/.txt (txt: row per line, no header) + preview. hasMore=true: raise pageSize (≤5000) or page with offset. inIde=true runs it in the console itself (result tabs in the IDE). Default to SELECT; confirm DML/DDL with the user.")
    suspend fun rider_execute_console(
        @McpDescription("SQL script; see split for how it is cut into batches") sql: String,
        @McpDescription("consoleRef '<data source>/<file>' from rider_list_db_consoles (a bare file name works when unique; omit = selected console tab)") console: String? = null,
        @McpDescription("Rows per result set, 1-5000, default 200") pageSize: Int = 200,
        @McpDescription("Execution database (omit = console's currentDatabase)") database: String? = null,
        @McpDescription("Rows to skip in each result set before the page (re-runs the query; pair with ORDER BY)") offset: Int = 0,
        @McpDescription("Per-batch query timeout in seconds, 0 = none, max 3600, default 60") timeoutSeconds: Int = 60,
        @McpDescription("Max chars per cell, longer values are cut with a '…[+N chars]' marker; 0 = unlimited, default 4000") maxCellLength: Int = 4000,
        @McpDescription("auto (default: GO for MSSQL/Sybase, ';' otherwise) | go | semicolon | none (send the script as one batch)") split: String = "auto",
        @McpDescription("true = start as a background job and return its jobId immediately; poll rider_console_job") async: Boolean = false,
        @McpDescription("Sync calls: seconds to wait for the result, 1-3600, default 50 — keep below the MCP client's tool timeout") waitSeconds: Int = 50,
        @McpDescription("Sync calls still running after waitSeconds: cancel (default, the server query is cancelled) | detach (keeps running as a job, returns jobId)") onClientTimeout: String = "cancel",
        @McpDescription("true = read-only mode (DML/DDL rejected, changes rolled back). Always on for data sources marked Read-only in Rider; false cannot lift that") readOnly: Boolean = false,
        @McpDescription("Mask secrets in cells and messages: password/pwd/secret/token/api_key columns → ***, password=… inside connection strings → password=***. Default true") maskSecrets: Boolean = true,
        @McpDescription("Write every result set (all rows, no page limit) to this file instead of the response: .csv, .json, or .txt (one line per row, cells tab-separated, no header — a ready list for scripts); later result sets go to <name>-<batch>-<result>.<ext>. Relative = project dir; allowed: project, temp/scratchpad dirs, -Drider.mcp.exportDirs. Response keeps path, rowCount, columns and a 5-row preview") outputFile: String? = null,
        @McpDescription("Replace outputFile (and its <name>-<batch>-<result> siblings) when it already exists; default false = fail instead") overwrite: Boolean = false,
        @McpDescription("true = run in the console itself like the user's Execute (not for Read-only data sources / readOnly=true): sql replaces the console text (undoable; blank sql = run its current text), result tabs appear in the IDE, waits ≤40 s; read them with rider_console_results") inIde: Boolean = false
    ): String {
        val project = coroutineContext.project
        val splitMode = SqlText.parseSplitMode(split)
            ?: mcpFail("Unknown split='$split'. Use auto, go, semicolon or none.")
        val detach = when (onClientTimeout.trim().lowercase()) {
            "cancel" -> false
            "detach" -> true
            else -> mcpFail("Unknown onClientTimeout='$onClientTimeout'. Use cancel or detach.")
        }
        val resolved = try {
            runOnEdt { DbConsoleReader.resolveConsole(project, console) }
        } catch (e: IllegalArgumentException) {
            mcpFail(e.message ?: "Console not found")
        }
        val ds = resolved.dataSource
        val targetDb = database?.takeIf { it.isNotBlank() } ?: resolved.currentDatabase
        val outputPath = outputFile?.takeIf { it.isNotBlank() }?.let {
            try {
                ExportFiles.resolve(it, project.basePath, overwrite)
            } catch (e: IllegalArgumentException) {
                mcpFail(e.message ?: "Invalid outputFile '$it'")
            }
        }

        val dsReadOnly = runCatching { ds.isReadOnly }.getOrDefault(false)
        val enforceReadOnly = readOnly || dsReadOnly
        // The IDE run has no rolled-back transaction or read-only session, so read-only cannot be enforced
        // there: refuse instead of degrading to the lexical guard alone.
        if (inIde && enforceReadOnly) {
            mcpFail(
                "inIde=true is not available in read-only mode (" +
                    (if (dsReadOnly) "data source '${ds.name}' is marked Read-only" else "readOnly=true") +
                    "): the IDE console cannot roll changes back. Run it with rider_execute_console without inIde, " +
                    "or ask the user to run the script in the console and read it with rider_console_results."
            )
        }
        if (enforceReadOnly) {
            val tsql = runCatching { ds.dbms.isMicrosoft || ds.dbms.isSybase }.getOrDefault(true)
            val verdict = SqlReadOnlyGuard.check(sql, tsql)
            if (!verdict.allowed) {
                val why = if (dsReadOnly) "data source '${ds.name}' is marked Read-only" else "readOnly=true"
                mcpFail(
                    "Read-only mode ($why) rejected the script before sending it: ${verdict.violations.joinToString("; ")}. " +
                        "Allowed: SELECT, #temp tables, @table variables, CREATE TEMP TABLE, read-only system procedures (sp_help*, sp_who…), " +
                        "sp_executesql / EXEC('…') / sp_MSforeachdb with literal SQL. A column named like a keyword must be quoted ([load], \"load\")."
                )
            }
        }

        // Pin the console resolved above: a bare or omitted name could select another tab by now.
        if (inIde) return IdeConsoleRunner.run(project, resolved.fileName, sql, waitSeconds.coerceAtMost(40))

        val opts = JdbcConsoleExecutor.Options(
            pageSize = pageSize.coerceIn(1, 5000),
            offset = offset.coerceAtLeast(0),
            timeoutSeconds = timeoutSeconds.coerceIn(0, 3600),
            maxCellLength = maxCellLength.coerceAtLeast(0),
            splitMode = splitMode,
            readOnly = enforceReadOnly,
            maskSecrets = maskSecrets,
            outputFile = outputPath,
            overwrite = overwrite
        )

        val consoleKey = "${ds.uniqueId}|${resolved.fileName}"
        val service = ConsoleJobService.getInstance()
        val (busy, job) = service.start(
            consoleKey,
            create = { id ->
                ConsoleJob(id, consoleKey, resolved.fileName, ds.name, targetDb, SqlText.snippet(sql, 120), enforceReadOnly)
            }
        ) { j ->
            val outcome = try {
                JdbcConsoleExecutor.execute(project, ds, sql, targetDb, opts, j)
            } catch (e: IllegalStateException) {
                throw ConsoleJobFailure("${e.message ?: "Query execution failed"} (console='${resolved.fileName}', dataSource='${ds.name}')")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ConsoleJobFailure("Query execution failed: ${SqlText.describe(e)} (console='${resolved.fileName}', dataSource='${ds.name}')")
            }
            render(outcome, resolved.fileName, ds.name, targetDb, opts)
        }
        if (busy != null) {
            mcpFail(
                "Console '${resolved.fileName}' is busy: jobId=${busy.id}, running ${busy.elapsedMs / 1000}s (${busy.sqlSnippet}). " +
                    "Poll rider_console_job(jobId='${busy.id}') or stop it with action='cancel'."
            )
        }
        job!!

        if (async) return withHint(job.statusJson(), "Running in background. Poll rider_console_job(jobId='${job.id}') every 5-30 s; cancel with action='cancel'.")

        val finished = try {
            withTimeoutOrNull(waitSeconds.coerceIn(1, 3600) * 1000L) { job.done.await() }
        } catch (e: CancellationException) {
            // The MCP call itself was cancelled: do not leave the statement running unless asked to.
            if (!detach) service.cancelAsync(job)
            throw e
        }
        if (finished == null) {
            if (detach) {
                return withHint(job.statusJson(), "Still running after waitSeconds=$waitSeconds — detached as job ${job.id}. Poll rider_console_job(jobId='${job.id}').")
            }
            val status = service.cancel(job)
            val last = job.lastMessages(5).takeIf { it.isNotEmpty() }?.let { " Last server messages: ${it.joinToString(" | ")}" }.orEmpty()
            mcpFail(
                "Query still running after waitSeconds=$waitSeconds (sync budget, kept below MCP client timeouts) — " +
                    (if (status == "cancelled") "cancelled on the server" else "cancel requested, connection force-closed") +
                    " (onClientTimeout=cancel).$last For long queries re-run with async=true and poll rider_console_job, " +
                    "or pass onClientTimeout='detach'."
            )
        }
        job.error?.let { mcpFail(it) }
        return job.result.toString()
    }

    private fun withHint(json: JsonObject, hint: String): String = buildJsonObject {
        json.forEach { (k, v) -> put(k, v) }
        put("hint", hint)
    }.toString()

    private fun JsonObjectBuilder.putErrorInfo(info: SqlText.ErrorInfo) {
        info.errorCode?.let { put("errorCode", it) }
        info.sqlState?.let { put("sqlState", it) }
        info.severity?.let { put("severity", it) }
        info.line?.let { put("line", it) }
        put("message", info.message)
        if (info.errorCode == null && info.sqlState == null) put("exceptionClass", info.exceptionClass)
    }

    /** Builds the response object, or throws [ConsoleJobFailure] for a single failed batch. */
    private fun render(
        outcome: JdbcConsoleExecutor.Outcome,
        consoleName: String,
        dataSourceName: String,
        targetDb: String?,
        opts: JdbcConsoleExecutor.Options
    ): JsonObject {
        fun JsonObjectBuilder.putEntry(r: JdbcConsoleExecutor.Entry) {
            put("status", r.status)
            if (r.error != null) {
                put("error", r.error)
                r.errorInfo?.let { putErrorInfo(it) }
                return
            }
            if (r.updateCount != null) {
                put("updateCount", r.updateCount)
                return
            }
            r.file?.let { put("file", it) }
            r.columns?.let { cols -> putJsonArray("columns") { cols.forEach { add(it) } } }
            put("text", r.text)
            put("rowCount", r.rowCount)
            put("hasMore", r.hasMore)
            if (r.truncatedCells > 0) put("truncatedCells", r.truncatedCells)
            if (r.maskedCells > 0) put("maskedCells", r.maskedCells)
        }

        fun JsonObjectBuilder.putHeader() {
            put("console", consoleName)
            put("dataSource", dataSourceName)
            (outcome.database ?: targetDb)?.let { put("database", it) }
            put("pageSize", opts.pageSize)
            if (opts.offset > 0) put("offset", opts.offset)
            put("readOnlyEnforced", outcome.readOnlyMode != null)
            outcome.readOnlyMode?.let { put("readOnlyMode", it) }
        }

        fun JsonObjectBuilder.putMessages() {
            if (outcome.messages.isNotEmpty()) putJsonArray("messages") { outcome.messages.forEach { add(it) } }
        }

        val entries = outcome.entries
        if (entries.size == 1 && outcome.batchCount == 1) {
            val r = entries[0]
            if (r.error != null) {
                val msgs = if (outcome.messages.isEmpty()) "" else " Server messages: ${outcome.messages.joinToString(" | ")}"
                throw ConsoleJobFailure(r.error + msgs)
            }
            return buildJsonObject {
                putHeader()
                putEntry(r)
                putMessages()
                put("elapsedMs", outcome.elapsedMs)
            }
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
        }
    }
}
