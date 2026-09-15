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
import kotlin.coroutines.coroutineContext
import com.intellij.mcpserver.project

class BuildToolset : McpToolset {

    @McpTool
    @McpDescription("Starts building the solution (compile/build). Returns sessionId for polling. Use rider_get_output to poll build progress and get build logs until status is not 'running'.")
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
    @McpDescription("Polls output for any async session (build, test, nuget restore). Returns new log lines since last call, plus current status and exit code. Keep polling until status is not 'running'. Works with sessionId from rider_start_build, rider_run_tests, rider_nuget_restore, rider_start_debug, rider_rerun_failed_tests.")
    suspend fun rider_get_output(
        @McpDescription("Session ID from a start operation")
        sessionId: String
    ): String {
        val session = SessionManager.get(sessionId)
            ?: mcpFail("Session '$sessionId' not found")

        val newLines = session.getNewLines()
        return buildJsonObject {
            put("status", session.status)
            if (newLines.isNotEmpty()) {
                putJsonArray("lines") { newLines.forEach { add(it) } }
            }
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
