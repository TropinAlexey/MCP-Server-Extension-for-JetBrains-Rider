package com.github.tropin.ridermcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import java.nio.file.Path

fun Project.projectDir(): Path? = guessProjectDir()?.toNioPathOrNull()

fun Path.relTo(projectDir: Path?): String =
    projectDir?.relativize(this)?.toString() ?: this.toString()

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
    val sliceTotal = allLines.size
    val totalLines = globalTotal ?: sliceTotal

    val filtered = if (!pattern.isNullOrBlank()) {
        val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
        allLines.mapIndexedNotNull { idx, line -> if (regex.containsMatchIn(line)) idx to line else null }
    } else null

    if (filtered != null) {
        val matchedLines = filtered.size
        val cap = if (maxLines > 0) maxLines else Int.MAX_VALUE
        val taken = if (fromEnd) filtered.takeLast(cap) else filtered.take(cap)
        val lines = taken.map { (idx, line) -> "[${baseLine + idx + 1}] $line" }
        val from = taken.firstOrNull()?.first ?: 0
        val to = taken.lastOrNull()?.first ?: 0
        return PaginationResult(lines, totalLines, baseLine + from, baseLine + to, taken.size < matchedLines, matchedLines)
    }

    if (maxLines <= 0) return PaginationResult(allLines, totalLines, baseLine, baseLine + sliceTotal - 1, false)

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
    return PaginationResult(result, totalLines, baseLine + from, baseLine + to - 1, result.size < sliceTotal)
}
