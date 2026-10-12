package com.github.tropin.ridermcp.ide

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Failure of a console job with a ready-to-show message (server text, batch, messages). */
internal class ConsoleJobFailure(message: String) : Exception(message)

/** One rider_execute_console run, sync or async; also the executor's progress listener. */
internal class ConsoleJob(
    val id: String,
    val consoleKey: String,
    val console: String,
    val dataSource: String,
    val database: String?,
    val sqlSnippet: String,
    val readOnlyEnforced: Boolean
) : JdbcConsoleExecutor.Listener {

    data class Message(val at: Long, val text: String)

    val startedAt: Long = System.currentTimeMillis()
    @Volatile var finishedAt: Long? = null
    /** running | completed | failed | cancelled */
    @Volatile var status: String = "running"
    @Volatile var result: JsonObject? = null
    @Volatile var error: String? = null
    @Volatile var batch: Int = 0
    @Volatile var totalBatches: Int = 0
    @Volatile var abort: (() -> Unit)? = null
    @Volatile var cancelRequested: Boolean = false
    lateinit var coroutine: Job
    val done = CompletableDeferred<Unit>()
    private val log = mutableListOf<Message>()

    val elapsedMs: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt

    override fun onMessage(text: String) {
        synchronized(log) { if (log.size < MAX_LOG) log.add(Message(System.currentTimeMillis(), text)) }
    }

    override fun onBatch(batchIndex: Int, totalBatches: Int) {
        batch = batchIndex
        this.totalBatches = totalBatches
    }

    override fun onConnected(abort: () -> Unit) {
        this.abort = abort
    }

    fun messages(from: Int): List<Message> = synchronized(log) { log.drop(from.coerceAtLeast(0)) }

    fun messageCount(): Int = synchronized(log) { log.size }

    fun lastMessages(n: Int): List<String> = synchronized(log) { log.takeLast(n).map { it.text } }

    fun statusJson(messagesOffset: Int = 0, includeResult: Boolean = true): JsonObject = buildJsonObject {
        put("jobId", id)
        put("status", status)
        put("console", console)
        put("dataSource", dataSource)
        database?.let { put("database", it) }
        put("readOnlyEnforced", readOnlyEnforced)
        put("elapsedMs", elapsedMs)
        if (status == "running" && totalBatches > 0) put("batch", "$batch/$totalBatches")
        put("sql", sqlSnippet)
        val msgs = messages(messagesOffset)
        putJsonArray("messages") {
            msgs.forEach { m ->
                add(buildJsonObject {
                    put("ts", Instant.ofEpochMilli(m.at).toString())
                    put("atMs", m.at - startedAt)
                    put("text", m.text)
                })
            }
        }
        put("nextMessagesOffset", messagesOffset.coerceAtLeast(0) + msgs.size)
        error?.let { put("error", it) }
        if (includeResult) result?.let { put("result", it) }
    }

    companion object {
        private const val MAX_LOG = 2000
    }
}

@Service(Service.Level.APP)
internal class ConsoleJobService(private val cs: CoroutineScope) {

    private val jobs = ConcurrentHashMap<String, ConsoleJob>()
    private val counter = AtomicLong()

    fun running(consoleKey: String): ConsoleJob? =
        jobs.values.firstOrNull { it.consoleKey == consoleKey && it.status == "running" }

    fun get(id: String): ConsoleJob? = jobs[id]

    fun list(): List<ConsoleJob> = jobs.values.sortedByDescending { it.startedAt }

    /**
     * Starts [body] as a background job unless the console already runs one (then returns that job
     * as `Left`). The body returns the final response or throws [ConsoleJobFailure].
     */
    @Synchronized
    fun start(
        consoleKey: String,
        create: (String) -> ConsoleJob,
        body: suspend (ConsoleJob) -> JsonObject
    ): Pair<ConsoleJob?, ConsoleJob?> {
        running(consoleKey)?.let { return it to null }
        cleanup()
        val job = create("cj${counter.incrementAndGet()}")
        jobs[job.id] = job
        job.coroutine = cs.launch(Dispatchers.IO) {
            try {
                job.result = body(job)
                job.status = "completed"
            } catch (e: CancellationException) {
                job.status = "cancelled"
                job.error = "Cancelled after ${job.elapsedMs / 1000}s; the server statement was cancelled."
            } catch (e: ConsoleJobFailure) {
                job.status = if (job.cancelRequested) "cancelled" else "failed"
                job.error = e.message
            } catch (e: Throwable) {
                job.status = if (job.cancelRequested) "cancelled" else "failed"
                job.error = SqlText.describe(e)
            } finally {
                job.finishedAt = System.currentTimeMillis()
                job.abort = null
                job.done.complete(Unit)
            }
        }
        return null to job
    }

    /**
     * Cancels [job]: coroutine cancel → statement cancel (driver attention / pg cancel). If the
     * driver does not return within the grace period, the connection is force-closed, which makes
     * the server drop the session and its request.
     */
    suspend fun cancel(job: ConsoleJob): String {
        if (job.status != "running") return job.status
        job.cancelRequested = true
        job.coroutine.cancel()
        if (withTimeoutOrNull(CANCEL_GRACE_MS) { job.done.await() } == null) {
            withContext(Dispatchers.IO) { job.abort?.invoke() }
            withTimeoutOrNull(CANCEL_GRACE_MS) { job.done.await() }
        }
        return job.status
    }

    /** Fire-and-forget cancel for callers that are themselves being cancelled. */
    fun cancelAsync(job: ConsoleJob) {
        cs.launch { cancel(job) }
    }

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        jobs.values.removeIf { it.status != "running" && (it.finishedAt ?: Long.MAX_VALUE) < cutoff }
        val finished = jobs.values.filter { it.status != "running" }.sortedByDescending { it.startedAt }
        finished.drop(MAX_FINISHED).forEach { jobs.remove(it.id) }
    }

    companion object {
        private const val CANCEL_GRACE_MS = 10_000L
        private const val RETENTION_MS = 60 * 60 * 1000L
        private const val MAX_FINISHED = 50

        fun getInstance(): ConsoleJobService = service()
    }
}

class ConsoleJobToolset : McpToolset {

    @McpTool
    @McpDescription("Polls and controls rider_execute_console jobs (async=true, or a sync call detached by onClientTimeout=detach). action='status' (default): status running|completed|failed|cancelled, elapsedMs, current batch, server messages (PRINT / RAISERROR … WITH NOWAIT) with timestamps since messagesOffset — pass back nextMessagesOffset to get only new ones — and, once finished, the same result object rider_execute_console returns. action='result': the result only (fails while running). action='cancel': cancels the statement on the server (force-closes the connection if the driver ignores the cancel) — the console is free again. action='list': recent jobs. Poll every 5-30 s; jobs are kept for 1 h.")
    suspend fun rider_console_job(
        @McpDescription("status (default) | result | cancel | list") action: String = "status",
        @McpDescription("Job id from rider_execute_console (not needed for list)") jobId: String? = null,
        @McpDescription("Return server messages from this index (use nextMessagesOffset of the previous status)") messagesOffset: Int = 0
    ): String {
        val service = ConsoleJobService.getInstance()
        val act = action.trim().lowercase()
        if (act == "list") {
            val jobs = service.list()
            return buildJsonObject {
                put("count", jobs.size)
                putJsonArray("jobs") {
                    jobs.forEach { j ->
                        add(buildJsonObject {
                            put("jobId", j.id)
                            put("status", j.status)
                            put("console", j.console)
                            put("dataSource", j.dataSource)
                            put("elapsedMs", j.elapsedMs)
                            put("sql", j.sqlSnippet)
                        })
                    }
                }
            }.toString()
        }
        if (jobId.isNullOrBlank()) mcpFail("jobId is required for action='$act'. Use action='list' to see jobs.")
        val job = service.get(jobId.trim())
            ?: mcpFail("Unknown jobId '$jobId' (jobs are kept 1 h). Use action='list' to see jobs.")
        return when (act) {
            "status" -> job.statusJson(messagesOffset).toString()
            "result" -> when (job.status) {
                "running" -> mcpFail("Job ${job.id} is still running (${job.elapsedMs / 1000}s). Poll action='status'.")
                "completed" -> job.result.toString()
                else -> mcpFail("Job ${job.id} ${job.status}: ${job.error ?: "no details"}")
            }
            "cancel" -> {
                val status = service.cancel(job)
                job.statusJson(messagesOffset, includeResult = false).let { json ->
                    buildJsonObject {
                        json.forEach { (k, v) -> put(k, v) }
                        put("cancelled", status == "cancelled")
                        if (status == "running") put("hint", "The driver has not returned yet; the connection was force-closed. Poll action='status'.")
                    }
                }.toString()
            }
            else -> mcpFail("Unknown action '$action'. Use status, result, cancel or list.")
        }
    }
}
