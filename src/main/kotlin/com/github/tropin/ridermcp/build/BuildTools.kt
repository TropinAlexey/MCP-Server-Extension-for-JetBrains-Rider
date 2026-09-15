package com.github.tropin.ridermcp.build

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.project.Project
import com.intellij.task.ProjectTaskManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.mcpJson
import kotlinx.serialization.encodeToString

class StartBuildTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "rider_start_build"
    override val description = "Starts building the solution (compile/build). Returns sessionId for polling. Use rider_get_output to poll build progress and get build logs until status is not 'running'."

    override fun handle(project: Project, args: NoArgs): Response {
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

        return Response(mcpJson.encodeToString(mapOf("sessionId" to session.id)))
    }
}

@Serializable
data class SessionIdArgs(val sessionId: String)

class GetOutputTool : AbstractMcpTool<SessionIdArgs>(SessionIdArgs.serializer()) {
    override val name = "rider_get_output"
    override val description = "Polls output for any async session (build, test, nuget restore). Returns new log lines since last call, plus current status and exit code. Keep polling until status is not 'running'. Works with sessionId from rider_start_build, rider_run_tests, rider_nuget_restore, rider_start_debug, rider_rerun_failed_tests."

    override fun handle(project: Project, args: SessionIdArgs): Response {
        val session = SessionManager.get(args.sessionId)
            ?: return Response(error = "Session '${args.sessionId}' not found")

        val newLines = session.getNewLines()
        val result = buildJsonObject {
            put("status", session.status)
            if (newLines.isNotEmpty()) {
                putJsonArray("lines") { newLines.forEach { add(it) } }
            }
            session.exitCode?.let { put("exitCode", it) }
        }
        return Response(result.toString())
    }
}

class CancelBuildTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "rider_cancel_build"
    override val description = "Cancels/stops the currently running build. Use when a build is taking too long or needs to be aborted."

    override fun handle(project: Project, args: NoArgs): Response {
        val action = ActionManager.getInstance().getAction("Stop")
            ?: return Response(error = "No cancel action available")

        ApplicationManager.getApplication().invokeLater {
            val frame = WindowManager.getInstance().getFrame(project)
            ActionManager.getInstance().tryToExecute(action, null, frame, "", true)
        }
        return Response("ok")
    }
}
