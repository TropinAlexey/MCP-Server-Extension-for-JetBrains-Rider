package com.github.tropin.ridermcp.build

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.WindowManager
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rider.build.BuildHost
import com.jetbrains.rider.build.BuildEventsService
import com.jetbrains.rider.model.BuildEventRef
import com.jetbrains.rider.model.BuildEventKind
import com.jetbrains.rider.model.BuildResultKind
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.paginateLines
import com.github.tropin.ridermcp.projectDir
import com.github.tropin.ridermcp.relTo
import com.github.tropin.ridermcp.runOnEdt
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import com.intellij.mcpserver.project

private const val BUILD_SOLUTION_ACTION = "BuildWholeSolutionAction"
private const val BUILD_START_TIMEOUT_S = 30L
private const val MAX_BUILD_PROBLEMS = 50

class BuildToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Builds the .NET solution (MSBuild). action='start' (default) launches an async build and returns sessionId — poll it with rider_get_output until status != 'running'. " +
            "action='cancel' stops the running build. " +
            "Use for compile errors: the session output lists them as 'error CS0029: src/A.cs(6,34): message' lines (warnings likewise) before the final status. " +
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

        try {
            SolutionConfigurationManager.getInstance(project).activeConfigurationAndPlatform?.let { active ->
                session.metadata["configuration"] = active.configuration
                session.metadata["platform"] = active.platform
            }
        } catch (_: Throwable) {
            // configuration manager may not be ready yet
        }

        // Rider's own build host: ProjectTaskManager.buildAllModules() reports success there even when
        // MSBuild fails, so the outcome comes from the BuildSession result. The build is triggered through
        // the IDE action because BuildParameters' constructor differs between Rider versions.
        val host = BuildHost.getInstance(project)
        if (host.building.value) {
            SessionManager.remove(session.id)
            mcpFail("A build is already running. Wait for it (rider_get_ide_state) or cancel it with rider_build(action='cancel').")
        }
        val action = ActionManager.getInstance().getAction(BUILD_SOLUTION_ACTION)
            ?: run { SessionManager.remove(session.id); mcpFail("Build action '$BUILD_SOLUTION_ACTION' not available in this IDE") }
        val projectDir = project.projectDir()
        val lifetime = LifetimeDefinition()
        val previous = host.buildSession.value
        runOnEdt {
            host.buildSession.advise(lifetime) { buildSession ->
                if (buildSession == null || buildSession === previous) return@advise
                session.tag = buildSession
                // Events live in the build's storage file; refs carry kind + offset to load them.
                val problemRefs = mutableListOf<BuildEventRef>()
                buildSession.buildEventRefs.advise(lifetime) { refs ->
                    synchronized(problemRefs) {
                        problemRefs += refs.filter { it.kind == BuildEventKind.Error || it.kind == BuildEventKind.Warning }
                    }
                }
                buildSession.result.advise(lifetime) { result ->
                    val events = BuildEventsService.getInstance(project)
                    val refs = synchronized(problemRefs) { problemRefs.sortedBy { it.kind != BuildEventKind.Error }.toList() }
                    session.appendLines(refs.take(MAX_BUILD_PROBLEMS).map { formatBuildEvent(it, events, projectDir) })
                    if (refs.size > MAX_BUILD_PROBLEMS) {
                        session.appendLine("[${refs.size - MAX_BUILD_PROBLEMS} more — see rider_tool_window(windowId='Build')]")
                    }
                    session.status = when (result.kind) {
                        BuildResultKind.Successful, BuildResultKind.HasWarnings -> "succeeded"
                        BuildResultKind.Canceled -> "cancelled"
                        else -> "failed"
                    }
                    session.exitCode = if (session.status == "succeeded") 0 else 1
                    session.appendLine("Build ${session.status}" + if (result.kind == BuildResultKind.Crashed) " (build host crashed)" else "")
                    lifetime.terminate()
                }
            }
            val frame = WindowManager.getInstance().getFrame(project)
            ActionManager.getInstance().tryToExecute(action, null, frame, "", true)
        }
        // The action is a no-op while the solution is still loading: do not leave the session running forever.
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (session.tag == null && session.status == "running") {
                session.status = "failed"
                session.exitCode = 1
                session.appendLine("Build did not start within ${BUILD_START_TIMEOUT_S}s (solution still loading?). Check rider_get_ide_state, then retry.")
                lifetime.terminate()
            }
        }, BUILD_START_TIMEOUT_S, TimeUnit.SECONDS)

        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    // "error CS0029: src/A.cs(6,34): message" — file and line relative to the project, like MSBuild output.
    private fun formatBuildEvent(ref: BuildEventRef, events: BuildEventsService, projectDir: Path?): String {
        val kind = if (ref.kind == BuildEventKind.Error) "error" else "warning"
        val event = runCatching { events.getEvent(ref.offset) }.getOrNull()
            ?: return "$kind: ${ref.customMessage ?: "(details in rider_tool_window(windowId='Build'))"}"
        val code = event.code?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val location = event.filePath?.let { path ->
            val pos = event.line?.let { line -> "($line${event.column?.let { ",$it" } ?: ""})" } ?: ""
            " ${path.relTo(projectDir)}$pos:"
        } ?: ""
        return "$kind$code:$location ${event.message}"
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
