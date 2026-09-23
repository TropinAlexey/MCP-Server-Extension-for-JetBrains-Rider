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
    @McpDescription(
        "Builds the .NET solution (MSBuild). action='start' (default) launches an async build and returns sessionId — poll it with rider_get_output until status != 'running'. " +
            "action='cancel' stops the running build. " +
            "Use for compile errors; structured errors are also in rider_tool_window(windowId='Problems'). " +
            "Do NOT use for running apps (use rider_start_debug), running tests (use rider_tests), or restoring packages (use rider_nuget action='restore'). " +
            "If the IDE is busy, check rider_get_ide_state first."
    )
    suspend fun rider_build(
        @McpDescription("Action: start (default) launches build, cancel stops the running build") action: String = "start"
    ): String {
        return when (action.lowercase()) {
            "start" -> buildStart()
            "cancel" -> buildCancel()
            else -> mcpFail("Unknown action '$action'. Use: start, cancel")
        }
    }

    private suspend fun buildStart(): String {
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
    @McpDescription("Polls output of any async session started by rider_build, rider_tests, rider_nuget(action='restore') or rider_start_debug. Returns new log lines since the last call plus status and exitCode — keep polling until status is not 'running'. Pass allLines=true to re-read full history (default is delta only). Use fromEnd=true for the tail, pattern for regex grep (case-insensitive, invalid regex matched literally), offset for paging (mutually exclusive with fromEnd). totalLines and returnedRange are session-wide 0-based coordinates; [n] prefixes are session-wide 1-based line numbers.")
    suspend fun rider_get_output(
        @McpDescription("Session ID returned by rider_build / rider_tests / rider_nuget(action='restore') / rider_start_debug") sessionId: String,
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

    private suspend fun buildCancel(): String {
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
