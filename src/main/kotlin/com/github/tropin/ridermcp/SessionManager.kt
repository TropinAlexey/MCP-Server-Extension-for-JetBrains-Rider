package com.github.tropin.ridermcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class OutputSession(
    val id: String,
    val type: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    @Volatile var status: String = "running"
    @Volatile var exitCode: Int? = null
    @Volatile var progress: Double = -1.0
    @Volatile var tag: Any? = null

    private val lines = mutableListOf<String>()
    private var readCursor: Int = 0

    @Synchronized fun appendLine(line: String) { lines.add(line) }

    @Synchronized fun appendLines(newLines: List<String>) { lines.addAll(newLines) }

    @Synchronized fun getNewLines(): List<String> {
        val result = lines.subList(readCursor, lines.size).toList()
        readCursor = lines.size
        return result
    }

    @Synchronized fun getNewLinesWithBase(): Pair<Int, List<String>> {
        val base = readCursor
        val result = lines.subList(readCursor, lines.size).toList()
        readCursor = lines.size
        return base to result
    }

    @Synchronized fun totalLineCount(): Int = lines.size

    @Synchronized fun getAllLines(): List<String> = lines.toList()
}

object SessionManager {
    private val sessions = ConcurrentHashMap<String, OutputSession>()
    private val counter = AtomicLong(0)
    private const val STALE_TTL_MS = 10 * 60 * 1000L

    fun create(type: String): OutputSession {
        cleanupStale()
        val id = "${type}_${counter.incrementAndGet()}"
        val session = OutputSession(id = id, type = type)
        sessions[id] = session
        return session
    }

    fun get(id: String): OutputSession? = sessions[id]

    fun remove(id: String) { sessions.remove(id) }

    fun listByType(type: String): List<OutputSession> =
        sessions.values.filter { it.type == type }

    fun running(): List<OutputSession> =
        sessions.values.filter { it.status == "running" }

    private fun cleanupStale() {
        val cutoff = System.currentTimeMillis() - STALE_TTL_MS
        sessions.entries.removeIf { it.value.status != "running" && it.value.createdAt < cutoff }
    }
}
