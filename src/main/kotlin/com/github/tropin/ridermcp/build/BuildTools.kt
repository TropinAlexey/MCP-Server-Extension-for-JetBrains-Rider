package com.github.tropin.ridermcp.build

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.task.ProjectTaskManager
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.paginateLines
import kotlin.coroutines.coroutineContext
import com.intellij.mcpserver.project

class BuildToolset : McpToolset {

    @McpTool
    @McpDescription("Starts building the solution (compile/build). Returns sessionId for polling. Use rider_get_output to poll build progress and get build logs until status is not 'running'. If the IDE is busy (indexing, another build, active debug), check rider_get_ide_state first.")
    suspend fun rider_start_build(): String {
        val project = coroutineContext.project
        val session = SessionManager.create("build")
        session.appendLine("Build started")

        ProjectTaskManager.getInstance(project).buildAllModules()
            .onSuccess { result ->
                session.status = when {
                    result.isAborted -> "cancelled"
                    result.hasErrors() -> "failed"
                    else -> "succeeded"
                }
                session.exitCode = if (result.hasErrors()) 1 else 0
                session.appendLine("Build ${session.status}")
            }
            .onError { error ->
                session.status = "failed"
                session.exitCode = 1
                session.appendLine("Build error: ${error.message}")
            }

        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    @McpTool
    @McpDescription("Polls output for any async session (build, test, nuget restore). Returns new log lines since last call, plus current status and exit code. Keep polling until status is not 'running'. Works with sessionId from rider_start_build, rider_run_tests, rider_nuget_restore, rider_start_debug, rider_rerun_failed_tests. Supports pagination: fromEnd=true for last N lines, pattern for regex grep, offset for random access. Pass allLines=true to read ALL accumulated lines (not just new since last poll). totalLines and returnedRange use session-wide 0-based coordinates; [n] prefixes are session-wide 1-based line numbers.")
    suspend fun rider_get_output(
        @McpDescription("Session ID from a start operation") sessionId: String,
        @McpDescription("Max lines to return (0 = unlimited)") maxLines: Int = 0,
        @McpDescription("Start from this line (0-based). Mutually exclusive with fromEnd (error if combined). Pages within pattern matches when pattern is set") offset: Int? = null,
        @McpDescription("Return last maxLines lines instead of first (default false)") fromEnd: Boolean = false,
        @McpDescription("Regex filter — return only matching lines (case-insensitive). E.g. 'error|exception|warn'") pattern: String? = null,
        @McpDescription("Read all accumulated lines, not just new since last poll (default false)") allLines: Boolean = false
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

    @McpTool
    @McpDescription("Cancels/stops the currently running build. Use when a build is taking too long or needs to be aborted.")
    suspend fun rider_cancel_build(): String {
        val project = coroutineContext.project
        val action = ActionManager.getInstance().getAction("Stop")
            ?: mcpFail("No cancel action available")

        ApplicationManager.getApplication().invokeLater {
            val frame = WindowManager.getInstance().getFrame(project)
            ActionManager.getInstance().tryToExecute(action, null, frame, "", true)
        }
        return "ok"
    }
}
