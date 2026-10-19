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

    /** Structured fields of the first server error; null fields = the driver did not report them. */
    data class ErrorInfo(
        val message: String,
        val errorCode: Int? = null,
        val sqlState: String? = null,
        val severity: Int? = null,
        val line: Int? = null,
        val exceptionClass: String
    )

    fun errorInfo(t: Throwable): ErrorInfo {
        val sql = findSqlException(t)
            ?: return ErrorInfo(message = maskJdbcSecrets(firstMessage(t) ?: "Unknown error"), exceptionClass = t.javaClass.name)
        val (severity, line) = serverErrorDetails(sql)
        return ErrorInfo(
            message = maskJdbcSecrets(sql.message?.takeIf { it.isNotBlank() } ?: "Unknown error"),
            errorCode = sql.errorCode.takeIf { it != 0 },
            sqlState = sql.sqlState?.takeIf { it.isNotBlank() },
            severity = severity,
            line = line,
            exceptionClass = sql.javaClass.name
        )
    }

    /** Server error text with vendor code, SQLState, severity and line, including chained exceptions; JDBC secrets masked. */
    fun describe(t: Throwable): String = maskJdbcSecrets(describeRaw(t))

    private fun firstMessage(t: Throwable): String? {
        var cur: Throwable? = t
        while (cur != null) {
            cur.message?.takeIf { it.isNotBlank() }?.let { return it }
            cur = cur.cause
        }
        return null
    }

    private fun describeRaw(t: Throwable): String {
        val sql = findSqlException(t) ?: return firstMessage(t) ?: "Unknown error (${t.javaClass.name})"
        val parts = mutableListOf<String>()
        var e: SQLException? = sql
        while (e != null && parts.size < 5) {
            val (severity, line) = serverErrorDetails(e)
            val code = buildList {
                if (e.errorCode != 0) add("error ${e.errorCode}")
                e.sqlState?.takeIf { it.isNotBlank() }?.let { add("SQLState $it") }
                severity?.let { add("severity $it") }
                line?.let { add("line $it") }
            }
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "Unknown error (${e.javaClass.name})"
            parts.add(if (code.isEmpty()) msg else "$msg [${code.joinToString(", ")}]")
            e = e.nextException?.takeIf { it !== e }
        }
        return parts.distinct().joinToString(" | ")
    }

    /**
     * Severity and line of an MSSQL error (SQLServerException.getSQLServerError()), read reflectively:
     * the driver lives in another classloader and other drivers lack the method.
     */
    private fun serverErrorDetails(e: SQLException): Pair<Int?, Int?> = runCatching {
        val err = e.javaClass.getMethod("getSQLServerError").invoke(e) ?: return null to null
        fun int(name: String) = runCatching { (err.javaClass.getMethod(name).invoke(err) as? Number)?.toInt() }.getOrNull()
        int("getErrorSeverity") to int("getLineNumber")?.takeIf { it > 0 }
    }.getOrDefault(null to null)

    fun snippet(sql: String, max: Int = 200): String {
        val flat = sql.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max) + "…"
    }
}
