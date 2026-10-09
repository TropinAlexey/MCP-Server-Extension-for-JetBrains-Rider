package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.maskJdbcSecrets
import java.sql.SQLException
import java.sql.SQLTimeoutException

/** Pure SQL text helpers for rider_execute_console — no IDE dependencies, unit-tested. */
internal object SqlText {

    enum class SplitMode { AUTO, SEMICOLON, GO, NONE }

    fun parseSplitMode(value: String?): SplitMode? = when (value?.trim()?.lowercase()) {
        null, "", "auto" -> SplitMode.AUTO
        "semicolon", ";" -> SplitMode.SEMICOLON
        "go" -> SplitMode.GO
        "none", "false" -> SplitMode.NONE
        else -> null
    }

    fun split(sql: String, mode: SplitMode): List<String> = when (mode) {
        SplitMode.SEMICOLON, SplitMode.AUTO -> splitBySemicolon(sql)
        SplitMode.GO -> splitByGo(sql)
        SplitMode.NONE -> listOfNotNull(sql.trim().takeIf { it.isNotEmpty() })
    }

    /**
     * Walks [sql] skipping string literals, quoted identifiers and comments, calling [onCode]
     * with the index of every character outside them. [onCode] returns the next index to scan
     * from, or null to advance by one.
     */
    private inline fun scan(sql: String, onCode: (Int) -> Int?) {
        var i = 0
        val len = sql.length
        while (i < len) {
            val c = sql[i]
            when {
                c == '\'' -> {
                    i++
                    while (i < len) {
                        if (sql[i] == '\'') {
                            i++
                            if (i < len && sql[i] == '\'') i++ else break
                        } else i++
                    }
                }
                c == '"' -> { i++; while (i < len && sql[i] != '"') i++; i++ }
                c == '[' -> { i++; while (i < len && sql[i] != ']') i++; i++ }
                c == '-' && i + 1 < len && sql[i + 1] == '-' -> { while (i < len && sql[i] != '\n') i++ }
                c == '/' && i + 1 < len && sql[i + 1] == '*' -> {
                    i += 2
                    while (i < len && !(sql[i] == '*' && i + 1 < len && sql[i + 1] == '/')) i++
                    i += 2
                }
                else -> i = onCode(i) ?: (i + 1)
            }
        }
    }

    fun splitBySemicolon(sql: String): List<String> {
        val statements = mutableListOf<String>()
        var start = 0
        scan(sql) { i ->
            if (sql[i] == ';') {
                sql.substring(start, i).trim().takeIf { it.isNotEmpty() }?.let(statements::add)
                start = i + 1
            }
            null
        }
        if (start < sql.length) sql.substring(start).trim().takeIf { it.isNotEmpty() }?.let(statements::add)
        return statements
    }

    private val goLine = Regex("""^[ \t]*GO(?:[ \t]+(\d+))?[ \t]*(?:--.*)?$""", RegexOption.IGNORE_CASE)

    /** T-SQL batch separator: a line holding only `GO [count]` outside literals/comments. */
    fun splitByGo(sql: String): List<String> {
        val batches = mutableListOf<String>()
        var start = 0
        scan(sql) { i ->
            if (i != 0 && sql[i - 1] != '\n') return@scan null
            val end = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
            val match = goLine.matchEntire(sql.substring(i, end).trimEnd('\r')) ?: return@scan null
            val batch = sql.substring(start, i).trim()
            if (batch.isNotEmpty()) {
                val count = match.groupValues[1].toIntOrNull()?.coerceIn(1, 1000) ?: 1
                repeat(count) { batches.add(batch) }
            }
            start = end
            end
        }
        if (start < sql.length) sql.substring(start).trim().takeIf { it.isNotEmpty() }?.let(batches::add)
        return batches
    }

    /** Cuts a cell to [maxLength] chars (0 = unlimited); returns text and whether it was cut. */
    fun truncateCell(value: String, maxLength: Int): Pair<String, Boolean> {
        if (maxLength <= 0 || value.length <= maxLength) return value to false
        return value.take(maxLength) + "…[+${value.length - maxLength} chars]" to true
    }

    fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\""
        else value

    /** Finds the innermost SQLException in a cause chain (remote/reflection wrappers hide it). */
    fun findSqlException(t: Throwable): SQLException? {
        var cur: Throwable? = t
        val seen = HashSet<Throwable>()
        while (cur != null && seen.add(cur)) {
            if (cur is SQLException) return cur
            cur = cur.cause
        }
        return null
    }

    fun isTimeout(t: Throwable): Boolean {
        val e = findSqlException(t)
        if (e is SQLTimeoutException) return true
        val msg = (e?.message ?: t.message).orEmpty().lowercase()
        return "timed out" in msg || "timeout" in msg
    }

    /** Server error text with vendor code and SQLState, including chained exceptions; JDBC secrets masked. */
    fun describe(t: Throwable): String = maskJdbcSecrets(describeRaw(t))

    private fun describeRaw(t: Throwable): String {
        val sql = findSqlException(t)
        if (sql == null) {
            var cur: Throwable? = t
            while (cur != null) {
                cur.message?.takeIf { it.isNotBlank() }?.let { return it }
                cur = cur.cause
            }
            return t.javaClass.simpleName
        }
        val parts = mutableListOf<String>()
        var e: SQLException? = sql
        while (e != null && parts.size < 5) {
            val code = buildList {
                if (e!!.errorCode != 0) add("error ${e!!.errorCode}")
                e!!.sqlState?.takeIf { it.isNotBlank() }?.let { add("SQLState $it") }
            }
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            parts.add(if (code.isEmpty()) msg else "$msg [${code.joinToString(", ")}]")
            e = e.nextException?.takeIf { it !== e }
        }
        return parts.distinct().joinToString(" | ")
    }

    fun snippet(sql: String, max: Int = 200): String {
        val flat = sql.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max) + "…"
    }
}
