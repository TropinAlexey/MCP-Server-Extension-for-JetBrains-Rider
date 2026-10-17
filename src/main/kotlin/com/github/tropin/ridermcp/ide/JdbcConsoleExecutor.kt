package com.github.tropin.ridermcp.ide

import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.model.ObjectKind
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.util.ObjectPath
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLWarning
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs SQL over a dedicated JDBC connection of the data source (not the console's own session):
 * driver errors surface verbatim, statements honour a query timeout and are cancelled on
 * timeout or client cancellation, and T-SQL scripts are split by GO, not by ';'.
 */
internal object JdbcConsoleExecutor {

    /** Extra seconds the watchdog waits past the driver-level query timeout before cancelling. */
    private const val WATCHDOG_GRACE_SECONDS = 5
    private const val MAX_RESULTS_PER_BATCH = 100
    private const val MAX_MESSAGES = 500
    private const val WARNING_POLL_MS = 1000L
    private const val FILE_PREVIEW_ROWS = 5
    private const val FILE_PREVIEW_CELL = 200

    data class Options(
        val pageSize: Int,
        val offset: Int,
        val timeoutSeconds: Int,
        val maxCellLength: Int,
        val splitMode: SqlText.SplitMode,
        val readOnly: Boolean = false,
        val maskSecrets: Boolean = true,
        /** When set, every result set is streamed to this file (format by extension: .csv, .json, .txt). */
        val outputFile: Path? = null,
        /** Replace existing export files (the first file is checked by the caller, siblings here). */
        val overwrite: Boolean = false
    )

    /** Progress hooks for background jobs; all called from IO threads. */
    interface Listener {
        fun onMessage(text: String) {}
        fun onBatch(batchIndex: Int, totalBatches: Int) {}
        /** [abort] force-closes the connection when a statement cancel is not honoured. */
        fun onConnected(abort: () -> Unit) {}
    }

    data class Entry(
        val batchIndex: Int,
        val resultIndex: Int,
        val status: String,
        val text: String? = null,
        val rowCount: Int = 0,
        val hasMore: Boolean = false,
        val truncatedCells: Int = 0,
        val updateCount: Int? = null,
        val error: String? = null,
        val maskedCells: Int = 0,
        val file: String? = null,
        val columns: List<String>? = null,
        val errorInfo: SqlText.ErrorInfo? = null
    )

    data class Outcome(
        val entries: List<Entry>,
        val messages: List<String>,
        val batchCount: Int,
        val skippedBatches: Int,
        val splitMode: SqlText.SplitMode,
        val elapsedMs: Long,
        /** How read-only was enforced on the connection (null = not requested). */
        val readOnlyMode: String? = null,
        /** Database the session actually ran in (DB_NAME() / current_database()), null when unknown. */
        val database: String? = null
    )

    suspend fun execute(
        project: Project,
        ds: LocalDataSource,
        sql: String,
        database: String?,
        opts: Options,
        listener: Listener = object : Listener {}
    ): Outcome {
        val started = System.currentTimeMillis()
        val dbms = ds.dbms
        val tsql = dbms.isMicrosoft || dbms.isSybase
        val mode = if (opts.splitMode == SqlText.SplitMode.AUTO) {
            if (tsql) SqlText.SplitMode.GO else SqlText.SplitMode.SEMICOLON
        } else opts.splitMode
        val batches = SqlText.split(sql, mode)
        if (batches.isEmpty()) throw IllegalStateException("No SQL statements to execute")

        val builder = DatabaseConnectionManager.getInstance().build(project, ds)
            .setRequestor(ConnectionRequestor.Anonymous())
            .setAskPassword(false)
        if (database != null) builder.setDestination(ObjectPath.create(database, ObjectKind.DATABASE))
        val ref = try {
            withContext(Dispatchers.IO) { builder.create() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Cannot connect to data source '${ds.name}': ${SqlText.describe(e)}", e)
        } ?: throw IllegalStateException(
            "Cannot connect to data source '${ds.name}': no connection (password not saved?). Connect once from the console in Rider, then retry."
        )

        val entries = mutableListOf<Entry>()
        val messages = mutableListOf<String>()
        val sink: (String) -> Unit = { raw ->
            val text = if (opts.maskSecrets) SecretMasker.maskText(raw) else raw
            synchronized(messages) { if (messages.size < MAX_MESSAGES) messages.add(text) }
            listener.onMessage(text)
        }
        val files = FileTargets(opts.outputFile)
        var skipped = 0
        var readOnlyMode: String? = null
        var sessionDb: String? = null
        try {
            val conn = ref.get()
            listener.onConnected { runCatching { conn.remoteConnection.close() } }
            if (database != null) switchDatabase(conn, database, tsql, dbms.isMysql)
            sessionDb = currentDatabase(conn, tsql, dbms.isPostgres || dbms.isRedshift || dbms.isGreenplum, dbms.isMysql)
            if (opts.readOnly) readOnlyMode = enforceReadOnly(conn, tsql, dbms.isPostgres || dbms.isRedshift || dbms.isGreenplum)
            for ((idx, batch) in batches.withIndex()) {
                val batchIndex = idx + 1
                listener.onBatch(batchIndex, batches.size)
                val stmt = try {
                    withContext(Dispatchers.IO) { conn.remoteConnection.createStatement() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException("Cannot create a statement on '${ds.name}': ${SqlText.describe(e)}", e)
                }
                val watchdogFired = AtomicBoolean(false)
                val warnings = WarningCursor()
                try {
                    withContext(Dispatchers.IO) {
                        if (opts.timeoutSeconds > 0) stmt.setQueryTimeout(opts.timeoutSeconds)
                        val fetch = if (opts.outputFile != null) 1000 else opts.offset + opts.pageSize + 1
                        runCatching { stmt.setFetchSize(fetch.coerceAtMost(1000)) }
                    }
                    guarded(stmt, opts.timeoutSeconds, watchdogFired, warnings, sink) {
                        runBatch(stmt, batch, batchIndex, opts, entries, files)
                    }
                    warnings.drain(stmt, sink)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { warnings.drain(stmt, sink) }
                    val timedOut = watchdogFired.get() || SqlText.isTimeout(e)
                    val error = if (timedOut) {
                        "Batch $batchIndex timed out after ${opts.timeoutSeconds}s and was cancelled " +
                            "(raise timeoutSeconds, or narrow with TOP / id ranges): ${SqlText.snippet(batch)}"
                    } else "Batch $batchIndex failed: ${SqlText.describe(e)} — ${SqlText.snippet(batch)}"
                    val resultIndex = entries.count { it.batchIndex == batchIndex } + 1
                    entries.add(Entry(batchIndex, resultIndex, if (timedOut) "timeout" else "error", error = error, errorInfo = SqlText.errorInfo(e)))
                    skipped = batches.size - batchIndex
                    break
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { stmt.close() } }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (readOnlyMode != null) runCatching { ref.get().remoteConnection.rollback() }
                runCatching { ref.close() }
            }
        }
        return Outcome(entries, messages.toList(), batches.size, skipped, mode, System.currentTimeMillis() - started, readOnlyMode, sessionDb)
    }

    /**
     * Puts the connection into a mode that cannot persist changes:
     * MSSQL/Sybase — implicit transaction rolled back at the end; PostgreSQL — read-only session;
     * others — the driver's read-only flag. Returns a label for the response.
     */
    private suspend fun enforceReadOnly(conn: DatabaseConnection, tsql: Boolean, postgres: Boolean): String = try {
        withContext(Dispatchers.IO) {
            val rc = conn.remoteConnection
            when {
                tsql -> {
                    rc.setAutoCommit(false)
                    runCatching { rc.setReadOnly(true) }
                    "transaction-rollback"
                }
                postgres -> {
                    runCatching { if (!rc.autoCommit) rc.rollback() }
                    val stmt = rc.createStatement()
                    try { stmt.execute("SET default_transaction_read_only = on") } finally { runCatching { stmt.close() } }
                    runCatching { rc.setReadOnly(true) }
                    "read-only-session"
                }
                else -> {
                    rc.setAutoCommit(false)
                    runCatching { rc.setReadOnly(true) }
                    "transaction-rollback"
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException("Cannot enforce read-only mode: ${SqlText.describe(e)}", e)
    }

    private suspend fun switchDatabase(conn: DatabaseConnection, database: String, tsql: Boolean, mysql: Boolean) {
        val use = when {
            tsql -> "USE [${database.replace("]", "]]")}]"
            mysql -> "USE `${database.replace("`", "``")}`"
            else -> return // other dialects bind the database via setDestination / the URL
        }
        try {
            withContext(Dispatchers.IO) {
                val stmt = conn.remoteConnection.createStatement()
                try { stmt.execute(use) } finally { runCatching { stmt.close() } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Cannot switch to database '$database': ${SqlText.describe(e)}", e)
        }
    }

    /** Name of the session's current database; null for other dialects or when the query fails. */
    private suspend fun currentDatabase(conn: DatabaseConnection, tsql: Boolean, postgres: Boolean, mysql: Boolean): String? {
        val query = when {
            tsql -> "SELECT DB_NAME()"
            postgres -> "SELECT current_database()"
            mysql -> "SELECT DATABASE()"
            else -> return null
        }
        return try {
            withContext(Dispatchers.IO) {
                val stmt = conn.remoteConnection.createStatement()
                try {
                    val rs = stmt.executeQuery(query)
                    try { if (rs.next()) rs.getString(1)?.takeIf { it.isNotBlank() } else null } finally { runCatching { rs.close() } }
                } finally { runCatching { stmt.close() } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs [block] on the IO pool while a watchdog cancels [stmt] when the deadline passes
     * (driver ignored setQueryTimeout) or when the calling coroutine is cancelled, and a poller
     * streams PRINT / RAISERROR … WITH NOWAIT messages as they arrive.
     */
    private suspend fun <T> guarded(
        stmt: RemoteStatement,
        timeoutSeconds: Int,
        fired: AtomicBoolean,
        warnings: WarningCursor,
        sink: (String) -> Unit,
        block: () -> T
    ): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watchdog = launch {
            try {
                if (timeoutSeconds > 0) {
                    delay((timeoutSeconds + WATCHDOG_GRACE_SECONDS) * 1000L)
                    fired.set(true)
                } else awaitCancellation()
            } finally {
                if (!finished.get()) withContext(NonCancellable + Dispatchers.IO) { runCatching { stmt.cancel() } }
            }
        }
        val poller = launch(Dispatchers.IO) {
            while (isActive) {
                delay(WARNING_POLL_MS)
                runCatching { warnings.drain(stmt, sink) }
            }
        }
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            finished.set(true)
            watchdog.cancel()
            poller.cancel()
        }
    }

    /** Walks every result of one batch (result sets and update counts) via getMoreResults. */
    private fun runBatch(
        stmt: RemoteStatement,
        batch: String,
        batchIndex: Int,
        opts: Options,
        entries: MutableList<Entry>,
        files: FileTargets
    ) {
        var resultIndex = 0
        var isResultSet = stmt.execute(batch)
        while (resultIndex < MAX_RESULTS_PER_BATCH) {
            if (isResultSet) {
                val rs = stmt.resultSet
                try {
                    val target = files.next(batchIndex, resultIndex + 1)
                    entries.add(
                        if (target != null) writeResultSet(rs, batchIndex, ++resultIndex, opts, target)
                        else readResultSet(rs, batchIndex, ++resultIndex, opts)
                    )
                } finally {
                    runCatching { rs.close() }
                }
            } else {
                val count = stmt.updateCount
                if (count == -1) break
                entries.add(Entry(batchIndex, ++resultIndex, "success", updateCount = count))
            }
            isResultSet = stmt.moreResults
        }
        if (resultIndex == 0) entries.add(Entry(batchIndex, 1, "success", text = "(no result set)"))
    }

    private class Columns(rs: RemoteResultSet, mask: Boolean) {
        val count: Int
        val labels: List<String>
        val kinds: List<SecretMasker.ColumnKind>?

        init {
            val meta = rs.metaData
            count = meta.columnCount
            labels = (1..count).map { meta.getColumnLabel(it) ?: "column$it" }
            kinds = if (mask) labels.map(SecretMasker::classifyColumn) else null
        }

        /** Cell value with secrets masked; increments [masked] when something was hidden. */
        fun cell(rs: RemoteResultSet, c: Int, masked: IntArray): String? {
            val value = rs.getString(c) ?: return null
            val kinds = kinds ?: return value
            val (out, hit) = SecretMasker.maskCell(value, kinds[c - 1])
            if (hit) masked[0]++
            return out
        }

        fun csvHeader(): String = labels.joinToString(",") { SqlText.csvField(it) }
    }

    private fun readResultSet(rs: RemoteResultSet, batchIndex: Int, resultIndex: Int, opts: Options): Entry {
        val cols = Columns(rs, opts.maskSecrets)
        val out = StringBuilder(cols.csvHeader()).append('\n')

        var skippedRows = 0
        while (skippedRows < opts.offset && rs.next()) skippedRows++

        var rows = 0
        var truncated = 0
        var hasMore = false
        val masked = IntArray(1)
        while (rs.next()) {
            if (rows == opts.pageSize) { hasMore = true; break }
            for (c in 1..cols.count) {
                if (c > 1) out.append(',')
                val value = cols.cell(rs, c, masked) ?: continue
                val (cell, cut) = SqlText.truncateCell(value, opts.maxCellLength)
                if (cut) truncated++
                out.append(SqlText.csvField(cell))
            }
            out.append('\n')
            rows++
        }
        return Entry(batchIndex, resultIndex, "success", out.toString().trimEnd('\n'), rows, hasMore, truncated, maskedCells = masked[0], columns = cols.labels)
    }

    /** Streams the whole result set (after offset) to [target]; the entry carries a short CSV preview. */
    private fun writeResultSet(rs: RemoteResultSet, batchIndex: Int, resultIndex: Int, opts: Options, target: Path): Entry {
        val cols = Columns(rs, opts.maskSecrets)
        val format = ExportFiles.formatOf(target) ?: ExportFiles.Format.CSV
        val json = format == ExportFiles.Format.JSON
        val preview = StringBuilder(cols.csvHeader()).append('\n')
        val previewCell = if (opts.maxCellLength in 1..FILE_PREVIEW_CELL) opts.maxCellLength else FILE_PREVIEW_CELL
        val masked = IntArray(1)
        var rows = 0
        ExportFiles.newWriter(target, opts.overwrite).use { w ->
            when (format) {
                ExportFiles.Format.JSON -> w.write("[")
                ExportFiles.Format.CSV -> { w.write(cols.csvHeader()); w.write("\n") }
                ExportFiles.Format.TXT -> {}
            }
            var skippedRows = 0
            while (skippedRows < opts.offset && rs.next()) skippedRows++
            val row = arrayOfNulls<String>(cols.count)
            while (rs.next()) {
                for (c in 1..cols.count) row[c - 1] = cols.cell(rs, c, masked)
                when (format) {
                    ExportFiles.Format.JSON -> writeJsonRow(w, cols.labels, row, rows == 0)
                    ExportFiles.Format.CSV -> writeCsvRow(w, row)
                    ExportFiles.Format.TXT -> { w.write(ExportFiles.txtLine(row.asList())); w.write("\n") }
                }
                if (rows < FILE_PREVIEW_ROWS) {
                    row.joinTo(preview, ",") { v -> v?.let { SqlText.csvField(SqlText.truncateCell(it, previewCell).first) }.orEmpty() }
                    preview.append('\n')
                }
                rows++
            }
            if (json) w.write(if (rows == 0) "]\n" else "\n]\n")
        }
        return Entry(
            batchIndex, resultIndex, "success", preview.toString().trimEnd('\n'), rows,
            hasMore = false, maskedCells = masked[0], file = target.toString(), columns = cols.labels
        )
    }

    private fun writeCsvRow(w: Writer, row: Array<String?>) {
        row.forEachIndexed { i, v ->
            if (i > 0) w.write(",")
            if (v != null) w.write(SqlText.csvField(v))
        }
        w.write("\n")
    }

    private fun writeJsonRow(w: Writer, labels: List<String>, row: Array<String?>, first: Boolean) {
        w.write(if (first) "\n{" else ",\n{")
        row.forEachIndexed { i, v ->
            if (i > 0) w.write(",")
            w.write(JsonPrimitive(labels[i]).toString())
            w.write(":")
            w.write(if (v == null) "null" else JsonPrimitive(v).toString())
        }
        w.write("}")
    }

    /** First result set goes to the requested path, later ones to `<stem>-<batch>-<result>.<ext>`. */
    private class FileTargets(private val base: Path?) {
        private var used = false

        fun next(batchIndex: Int, resultIndex: Int): Path? {
            val base = base ?: return null
            if (!used) { used = true; return base }
            return ExportFiles.sibling(base, "-$batchIndex-$resultIndex")
        }
    }

    /** Reads the statement's warning chain incrementally so polled and final reads never repeat a message. */
    private class WarningCursor {
        private var seen = 0

        @Synchronized
        fun drain(stmt: RemoteStatement, sink: (String) -> Unit) {
            val head: SQLWarning? = stmt.warnings
            // Some drivers clear the chain between results: a shorter chain means it restarted.
            if (chainLength(head) < seen) seen = 0
            var w: SQLWarning? = head
            var index = 0
            while (w != null) {
                if (index >= seen) {
                    val text = w.message?.trim().orEmpty()
                    if (text.isNotEmpty() && !text.startsWith("Changed database context") && !text.startsWith("Changed language setting")) {
                        sink(text)
                    }
                    seen = index + 1
                }
                index++
                w = w.nextWarning?.takeIf { it !== w }
            }
        }

        private fun chainLength(head: SQLWarning?): Int {
            var n = 0
            var w = head
            while (w != null) { n++; w = w.nextWarning?.takeIf { it !== w } }
            return n
        }
    }
}
