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
    private const val MAX_MESSAGES = 50

    data class Options(
        val pageSize: Int,
        val offset: Int,
        val timeoutSeconds: Int,
        val maxCellLength: Int,
        val splitMode: SqlText.SplitMode
    )

    data class Entry(
        val batchIndex: Int,
        val resultIndex: Int,
        val status: String,
        val text: String? = null,
        val rowCount: Int = 0,
        val hasMore: Boolean = false,
        val truncatedCells: Int = 0,
        val updateCount: Int? = null,
        val error: String? = null
    )

    data class Outcome(
        val entries: List<Entry>,
        val messages: List<String>,
        val batchCount: Int,
        val skippedBatches: Int,
        val splitMode: SqlText.SplitMode,
        val elapsedMs: Long
    )

    suspend fun execute(project: Project, ds: LocalDataSource, sql: String, database: String?, opts: Options): Outcome {
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
        var skipped = 0
        try {
            val conn = ref.get()
            if (database != null) switchDatabase(conn, database, tsql, dbms.isMysql)
            for ((idx, batch) in batches.withIndex()) {
                val batchIndex = idx + 1
                val stmt = try {
                    withContext(Dispatchers.IO) { conn.remoteConnection.createStatement() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException("Cannot create a statement on '${ds.name}': ${SqlText.describe(e)}", e)
                }
                val watchdogFired = AtomicBoolean(false)
                try {
                    withContext(Dispatchers.IO) {
                        if (opts.timeoutSeconds > 0) stmt.setQueryTimeout(opts.timeoutSeconds)
                        runCatching { stmt.setFetchSize((opts.offset + opts.pageSize + 1).coerceAtMost(1000)) }
                    }
                    guarded(stmt, opts.timeoutSeconds, watchdogFired) {
                        runBatch(stmt, batch, batchIndex, opts, entries)
                    }
                    collectWarnings(stmt, messages)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { collectWarnings(stmt, messages) }
                    val timedOut = watchdogFired.get() || SqlText.isTimeout(e)
                    val error = if (timedOut) {
                        "Batch $batchIndex timed out after ${opts.timeoutSeconds}s and was cancelled " +
                            "(raise timeoutSeconds, or narrow with TOP / id ranges): ${SqlText.snippet(batch)}"
                    } else "Batch $batchIndex failed: ${SqlText.describe(e)} — ${SqlText.snippet(batch)}"
                    val resultIndex = entries.count { it.batchIndex == batchIndex } + 1
                    entries.add(Entry(batchIndex, resultIndex, if (timedOut) "timeout" else "error", error = error))
                    skipped = batches.size - batchIndex
                    break
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { stmt.close() } }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { ref.close() } }
        }
        return Outcome(entries, messages, batches.size, skipped, mode, System.currentTimeMillis() - started)
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

    /**
     * Runs [block] on the IO pool while a watchdog cancels [stmt] when the deadline passes
     * (driver ignored setQueryTimeout) or when the calling coroutine is cancelled.
     */
    private suspend fun <T> guarded(stmt: RemoteStatement, timeoutSeconds: Int, fired: AtomicBoolean, block: () -> T): T =
        coroutineScope {
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
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                finished.set(true)
                watchdog.cancel()
            }
        }

    /** Walks every result of one batch (result sets and update counts) via getMoreResults. */
    private fun runBatch(stmt: RemoteStatement, batch: String, batchIndex: Int, opts: Options, entries: MutableList<Entry>) {
        var resultIndex = 0
        var isResultSet = stmt.execute(batch)
        while (resultIndex < MAX_RESULTS_PER_BATCH) {
            if (isResultSet) {
                val rs = stmt.resultSet
                try {
                    entries.add(readResultSet(rs, batchIndex, ++resultIndex, opts))
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

    private fun readResultSet(rs: RemoteResultSet, batchIndex: Int, resultIndex: Int, opts: Options): Entry {
        val meta = rs.metaData
        val columns = meta.columnCount
        val out = StringBuilder()
        (1..columns).joinTo(out, ",") { SqlText.csvField(meta.getColumnLabel(it) ?: "column$it") }
        out.append('\n')

        var skippedRows = 0
        while (skippedRows < opts.offset && rs.next()) skippedRows++

        var rows = 0
        var truncated = 0
        var hasMore = false
        while (rs.next()) {
            if (rows == opts.pageSize) { hasMore = true; break }
            for (c in 1..columns) {
                if (c > 1) out.append(',')
                val value = rs.getString(c) ?: continue
                val (cell, cut) = SqlText.truncateCell(value, opts.maxCellLength)
                if (cut) truncated++
                out.append(SqlText.csvField(cell))
            }
            out.append('\n')
            rows++
        }
        return Entry(batchIndex, resultIndex, "success", out.toString().trimEnd('\n'), rows, hasMore, truncated)
    }

    private fun collectWarnings(stmt: RemoteStatement, messages: MutableList<String>) {
        var w: SQLWarning? = stmt.warnings
        while (w != null && messages.size < MAX_MESSAGES) {
            val text = w.message?.trim().orEmpty()
            if (text.isNotEmpty() && !text.startsWith("Changed database context") && !text.startsWith("Changed language setting")) {
                messages.add(text)
            }
            w = w.nextWarning?.takeIf { it !== w }
        }
    }
}
