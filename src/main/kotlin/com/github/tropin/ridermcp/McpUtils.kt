package com.github.tropin.ridermcp

import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import java.nio.file.Path

fun Project.projectDir(): Path? = guessProjectDir()?.toNioPathOrNull()

// Masks credentials in JDBC URLs for tool responses: password=... params
// and userinfo (user:pass@) embedded in the URL authority. Never log raw URLs.
fun maskJdbcSecrets(url: String): String =
    url.replace(Regex("password=[^;&\\s]+"), "password=***")
        .replace(Regex("(://)[^/\\s]*@"), "$1***@")

fun Path.relTo(projectDir: Path?): String =
    projectDir?.relativize(this)?.toString() ?: this.toString()

// Runs [block] on the EDT and returns its result, rethrowing any failure.
// Falls through directly when already on the EDT (invokeAndWait would deadlock there).
fun <T> runOnEdt(block: () -> T): T {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) return block()
    var outcome: Result<T>? = null
    app.invokeAndWait { outcome = runCatching(block) }
    return outcome!!.getOrThrow()
}

data class PaginationResult(
    val lines: List<String>,
    val totalLines: Int,
    val returnedFrom: Int,
    val returnedTo: Int,
    val truncated: Boolean,
    val matchedLines: Int? = null
)

fun paginateLines(
    allLines: List<String>,
    maxLines: Int,
    offset: Int? = null,
    fromEnd: Boolean = false,
    pattern: String? = null,
    // Global coordinates for incremental reads (e.g. session polling):
    // baseLine = 0-based index of allLines[0] in the full stream,
    // globalTotal = full stream size. totalLines/returnedRange/[n] prefixes
    // are reported in global coordinates; truncation stays chunk-local.
    baseLine: Int = 0,
    globalTotal: Int? = null
): PaginationResult {
    if (offset != null && fromEnd) mcpFail("offset and fromEnd are mutually exclusive — pass only one")
    val sliceTotal = allLines.size
    val totalLines = globalTotal ?: sliceTotal

    val filtered = if (!pattern.isNullOrBlank()) {
        // Overlong patterns are matched literally: user/agent-supplied regex runs
        // on the server thread, and pathological patterns (e.g. nested quantifiers)
        // over thousands of lines are a ReDoS vector. 300 chars covers real greps.
        val regex = if (pattern.length > 300) Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
            else try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
        allLines.mapIndexedNotNull { idx, line -> if (regex.containsMatchIn(line)) idx to line else null }
    } else null

    if (filtered != null) {
        val matchedLines = filtered.size
        val cap = if (maxLines > 0) maxLines else Int.MAX_VALUE
        // offset pages within the matched lines (fromEnd is excluded above).
        val taken = when {
            offset != null -> filtered.drop(offset.coerceAtLeast(0)).take(cap)
            fromEnd -> filtered.takeLast(cap)
            else -> filtered.take(cap)
        }
        val lines = taken.map { (idx, line) -> "[${baseLine + idx + 1}] $line" }
        val from = taken.firstOrNull()?.first ?: 0
        val to = taken.lastOrNull()?.first ?: 0
        return PaginationResult(lines, totalLines, baseLine + from, baseLine + to, taken.size < matchedLines, matchedLines)
    }

    if (maxLines <= 0) return PaginationResult(allLines, totalLines, baseLine, maxOf(baseLine, baseLine + sliceTotal - 1), false)

    val from: Int
    val to: Int
    if (offset != null) {
        from = offset.coerceIn(0, sliceTotal)
        to = (from + maxLines).coerceAtMost(sliceTotal)
    } else if (fromEnd) {
        to = sliceTotal
        from = (sliceTotal - maxLines).coerceAtLeast(0)
    } else {
        from = 0
        to = maxLines.coerceAtMost(sliceTotal)
    }

    val result = allLines.subList(from, to)
    // Empty page → zero-width range instead of an underflowing to < from.
    return PaginationResult(result, totalLines, baseLine + from, maxOf(baseLine + from, baseLine + to - 1), result.size < sliceTotal)
}
