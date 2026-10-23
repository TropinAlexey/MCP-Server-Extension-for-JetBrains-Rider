package com.github.tropin.ridermcp.session

import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.paginateLines
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.mcpFail
import kotlinx.serialization.json.*

class SessionOutputToolset : McpToolset {

    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpTool
    @McpDescription("Polls output of any async session started by rider_tests, rider_nuget(action='restore') or rider_publish. Returns new log lines since the last call plus status and exitCode — keep polling until status is not 'running'. Pass allLines=true to re-read full history (default is delta only). Use fromEnd=true for the tail, pattern for regex grep (case-insensitive, invalid regex matched literally), offset for paging (mutually exclusive with fromEnd). totalLines and returnedRange are session-wide 0-based coordinates; [n] prefixes are session-wide 1-based line numbers. Builds and app runs use the built-in build_solution_state / execute_run_configuration instead.")
    suspend fun rider_get_output(
        @McpDescription("Session ID returned by rider_tests / rider_nuget(action='restore') / rider_publish") sessionId: String,
        @McpDescription("Max lines to return (0 = unlimited, default 0)") maxLines: Int = 0,
        @McpDescription("Start line, session-wide 0-based. Pages within pattern matches when pattern is set. Mutually exclusive with fromEnd") offset: Int? = null,
        @McpDescription("Return last maxLines lines instead of first (default false). Use for tails/errors") fromEnd: Boolean = false,
        @McpDescription("Regex filter, case-insensitive — only matching lines returned, e.g. 'error|exception|warn'") pattern: String? = null,
        @McpDescription("true = re-read ALL accumulated lines; false (default) = only new lines since last poll") allLines: Boolean = false
    ): String {
        val session = SessionManager.get(sessionId)
            ?: mcpFail("Session '$sessionId' not found")

        val (base, rawLines) = if (allLines) 0 to session.getAllLines() else session.getNewLinesWithBase()
        val result = paginateLines(rawLines, maxLines, offset, fromEnd, pattern,
            baseLine = base, globalTotal = session.totalLineCount())

        return buildJsonObject {
            put("status", session.status)
            put("totalLines", result.totalLines)
            putJsonObject("returnedRange") {
                put("from", result.returnedFrom)
                put("to", result.returnedTo)
            }
            if (result.lines.isNotEmpty()) {
                putJsonArray("lines") { result.lines.forEach { add(it) } }
            }
            if (result.truncated) put("truncated", true)
            result.matchedLines?.let { put("matchedLines", it) }
            session.exitCode?.let { put("exitCode", it) }
        }.toString()
    }
}
