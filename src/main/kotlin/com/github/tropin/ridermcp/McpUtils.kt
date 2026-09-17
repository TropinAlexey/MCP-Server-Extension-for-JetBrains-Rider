package com.github.tropin.ridermcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import java.nio.file.Path

fun Project.projectDir(): Path? = guessProjectDir()?.toNioPathOrNull()

fun Path.relTo(projectDir: Path?): String =
    projectDir?.relativize(this)?.toString() ?: this.toString()

enum class TruncateMode { START, END, MIDDLE, NONE }

fun parseTruncateMode(raw: String, default: TruncateMode): TruncateMode =
    TruncateMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default

fun truncateLines(lines: List<String>, maxLines: Int, mode: TruncateMode): Pair<List<String>, Boolean> {
    if (maxLines <= 0 || mode == TruncateMode.NONE || lines.size <= maxLines)
        return lines to false

    return when (mode) {
        TruncateMode.END -> lines.take(maxLines) to true
        TruncateMode.START -> lines.takeLast(maxLines) to true
        TruncateMode.MIDDLE -> {
            val head = (maxLines - 1) / 2
            val tail = maxLines - 1 - head
            val result = lines.take(head) + listOf("... (${lines.size - head - tail} lines truncated) ...") + lines.takeLast(tail)
            result to true
        }
        TruncateMode.NONE -> lines to false
    }
}
