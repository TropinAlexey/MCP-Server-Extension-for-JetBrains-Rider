package com.github.tropin.ridermcp.debugger

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.github.tropin.ridermcp.runOnEdt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.projectDir
import com.github.tropin.ridermcp.relTo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import kotlin.coroutines.coroutineContext

class DebugToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages .NET line breakpoints. action='set' (default) adds a breakpoint, action='remove' deletes it at that line. " +
            "filePath is project-relative or absolute, line is 1-indexed. " +
            "Use before rider_start_debug to arm breakpoints; verify paused state with rider_debug(action='state'). " +
            "There is no listing action — set is idempotent ('already set' when present), remove fails when absent."
    )
    suspend fun rider_breakpoint(
        @McpDescription("Action: set (default) adds breakpoint, remove deletes breakpoints at that line") action: String = "set",
        @McpDescription("File path, project-relative (e.g. 'src/Program.cs') or absolute") filePath: String,
        @McpDescription("Line number, 1-indexed") line: Int
    ): String {
        val project = coroutineContext.project
        val vf = resolveFile(project, filePath)
            ?: mcpFail("File not found: $filePath")
        val lineIndex = line - 1

        return when (action.lowercase()) {
            "set" -> {
                val bm = XDebuggerManager.getInstance(project).breakpointManager
                val existing = bm.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
                    .find { it.fileUrl == vf.url && it.line == lineIndex }
                if (existing != null) return "Breakpoint already set at $filePath:$line"
                runOnEdt {
                    XDebuggerUtil.getInstance().toggleLineBreakpoint(project, vf, lineIndex, false)
                }
                "Breakpoint set at $filePath:$line"
            }
            "remove" -> {
                val bm = XDebuggerManager.getInstance(project).breakpointManager
                val toRemove = bm.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
                    .filter { it.fileUrl == vf.url && it.line == lineIndex }
                if (toRemove.isEmpty()) mcpFail("No breakpoint at $filePath:$line")
                runOnEdt {
                    toRemove.forEach { bm.removeBreakpoint(it) }
                }
                "Breakpoint removed at $filePath:$line"
            }
            else -> mcpFail("Unknown action '$action'. Use: set, remove")
        }
    }

    @McpTool
    @McpDescription("Starts the app under the .NET debugger for an IDE run configuration (omit configName for the selected one). Returns sessionId — poll app stdout with rider_get_output and debugger status with rider_debug(action='state'). Use rider_breakpoint first to arm breakpoints. Do NOT use for plain runs without debugging, for builds (rider_build) or tests (rider_tests). Non-runnable configs (Publish/MSBuild profiles) fail fast.")
    suspend fun rider_start_debug(
        @McpDescription("IDE run configuration name (omit = selected configuration). Must match exactly; manage with rider_run_config") configName: String? = null
    ): String {
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)
        val settings = if (configName != null) {
            runManager.allSettings.find { it.name == configName }
                ?: mcpFail("Configuration '$configName' not found")
        } else {
            runManager.selectedConfiguration
                ?: mcpFail("No active run configuration. Specify configName.")
        }

        val debugRunner = try {
            ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, settings.configuration)
        } catch (_: Exception) {
            null
        }
        if (debugRunner == null) mcpFail("Configuration '${settings.name}' cannot be debugged (no debug runner — Publish/MSBuild profiles aren't debuggable). Pick a run configuration instead: list them with the built-in get_run_configurations (or IDE Run → Edit Configurations) and pass its name as configName.")

        val session = SessionManager.create("debug")
        session.appendLine("Debugging: ${settings.name}")
        val cfgName = settings.name

        ApplicationManager.getApplication().invokeLater {
            val connection = project.messageBus.connect()
            fun failDebugStart(error: Throwable?) {
                try { connection.disconnect() } catch (_: Exception) {}
                session.exitCode = 1
                session.status = "failed"
                session.appendLine("Error starting debug session '${cfgName}': ${error?.message ?: error?.javaClass?.simpleName ?: "unknown error"}")
            }
            try {
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    if (env.runProfile.name != cfgName) return
                    connection.disconnect()
                    session.tag = handler
                    handler.addProcessListener(object : ProcessListener {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            val text = event.text.trimEnd('\n', '\r')
                            if (text.isNotEmpty()) session.appendLine(text)
                        }
                        override fun processTerminated(event: ProcessEvent) {
                            session.exitCode = event.exitCode
                            session.status = "stopped"
                        }
                    })
                }
                override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                    if (env.runProfile.name != cfgName) return
                    failDebugStart(null)
                }
                override fun processNotStarted(executorId: String, env: ExecutionEnvironment, error: Throwable) {
                    if (env.runProfile.name != cfgName) return
                    failDebugStart(error)
                }
            })
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
            } catch (e: Exception) {
                try { connection.disconnect() } catch (_: Exception) {}
                session.status = "failed"
                session.appendLine("Error starting debug session: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    @McpTool
    @McpDescription(
        "Controls/inspects the active .NET debug session. action='state' (default) returns status (running/paused/stopped), current file:line and stack trace — poll it after rider_start_debug until paused. " +
            "Stepping: stepOver/stepInto/stepOut, flow: resume/pause/stop (must be paused, except pause needs running, stop works always). " +
            "action='evaluate' evaluates an expression in the current frame (requires expression; must be paused). " +
            "Evaluated code runs inside the debuggee: property getters can have side effects — prefer dev/test instances and confirm evaluation of unknown expressions with the user. " +
            "App stdout is NOT here — read it via rider_get_output(sessionId) or rider_tool_window(windowId='Debug', section='Console'). " +
            "Recipe: rider_breakpoint → rider_start_debug → poll state until paused → evaluate/step."
    )
    suspend fun rider_debug(
        @McpDescription("Action: state (default) inspects, stepOver/stepInto/stepOut/resume/pause/stop control flow, evaluate reads values") action: String = "state",
        @McpDescription("Expression to evaluate, e.g. 'myVar.Property' (required for action='evaluate' only)") expression: String? = null
    ): String {
        return when (action.lowercase()) {
            "state" -> debugState()
            "stepover", "stepinto", "stepout", "resume", "pause", "stop" -> debugStep(action)
            "evaluate" -> debugEvaluate(expression)
            else -> mcpFail("Unknown action '$action'. Use: state, stepOver, stepInto, stepOut, resume, pause, stop, evaluate")
        }
    }

    private suspend fun debugState(): String {
        val project = coroutineContext.project
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: mcpFail("No active debug session")

        val projectDir = project.projectDir()
        return buildJsonObject {
            put("status", when {
                session.isStopped -> "stopped"
                session.isPaused -> "paused"
                else -> "running"
            })
            put("session", session.sessionName)

            if (session.isPaused) {
                session.currentStackFrame?.sourcePosition?.let { pos ->
                    put("file", pos.file.toNioPathOrNull()?.relTo(projectDir) ?: pos.file.path)
                    put("line", pos.line + 1)
                }
                session.suspendContext?.activeExecutionStack?.let { stack ->
                    val frames = collectStackFrames(stack, projectDir)
                    if (frames.isNotEmpty()) {
                        putJsonArray("stackTrace") { frames.forEach { add(it) } }
                    }
                }
            }
        }.toString()
    }

    private fun collectStackFrames(stack: XExecutionStack, projectDir: java.nio.file.Path?): List<JsonObject> {
        val frames = mutableListOf<JsonObject>()
        val latch = CountDownLatch(1)
        stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                stackFrames.forEach { frame ->
                    frames.add(buildJsonObject {
                        val name = getFrameName(frame)
                        if (name.isNotEmpty()) put("name", name)
                        frame.sourcePosition?.let { pos ->
                            put("file", pos.file.toNioPathOrNull()?.relTo(projectDir) ?: pos.file.path)
                            put("line", pos.line + 1)
                        }
                    })
                }
                if (last) latch.countDown()
            }
            override fun errorOccurred(errorMessage: String) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        return frames
    }

    private fun getFrameName(frame: XStackFrame): String {
        val parts = mutableListOf<String>()
        frame.customizePresentation(object : ColoredTextContainer {
            override fun append(fragment: String, attributes: SimpleTextAttributes) { parts.add(fragment) }
            override fun append(fragment: String, attributes: SimpleTextAttributes, tag: Any?) { parts.add(fragment) }
            override fun setIcon(icon: Icon?) {}
            override fun setToolTipText(text: String?) {}
        })
        return parts.joinToString("")
    }

    private suspend fun debugStep(action: String): String {
        val project = coroutineContext.project
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: mcpFail("No active debug session")

        when (action.lowercase()) {
            "stepover" -> { if (!session.isPaused) mcpFail("Not paused"); session.stepOver(false) }
            "stepinto" -> { if (!session.isPaused) mcpFail("Not paused"); session.stepInto() }
            "stepout" -> { if (!session.isPaused) mcpFail("Not paused"); session.stepOut() }
            "resume" -> { if (!session.isPaused) mcpFail("Not paused"); session.resume() }
            "pause" -> { if (session.isPaused) mcpFail("Already paused"); session.pause() }
            "stop" -> session.stop()
        }
        return "ok"
    }

    private suspend fun debugEvaluate(expression: String?): String {
        if (expression.isNullOrBlank()) mcpFail("expression is required for action='evaluate'")
        val project = coroutineContext.project
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: mcpFail("No active debug session")
        if (!session.isPaused) mcpFail("Debugger is not paused. Pause it first (rider_debug(action='pause')), or set a breakpoint + resume and wait until rider_debug(action='state') shows paused.")

        val frame = session.currentStackFrame
            ?: mcpFail("No current stack frame")
        val evaluator = frame.evaluator
            ?: mcpFail("Evaluator not available for current frame")

        var resultType: String? = null
        var resultValue: String? = null
        var error: String? = null
        val latch = CountDownLatch(1)

        evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) {
                result.computePresentation(object : XValueNode {
                    override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                        resultType = type
                        resultValue = value
                        latch.countDown()
                    }
                    override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                        resultType = presentation.type
                        resultValue = renderPresentation(presentation)
                        latch.countDown()
                    }
                    override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
                    override fun isObsolete() = false
                }, XValuePlace.TREE)
            }
            override fun errorOccurred(errorMessage: String) {
                error = errorMessage
                latch.countDown()
            }
        }, frame.sourcePosition)

        if (!latch.await(10, TimeUnit.SECONDS)) mcpFail("Evaluation timed out")
        if (error != null) mcpFail(error!!)

        return buildJsonObject {
            resultType?.let { put("type", it) }
            put("value", resultValue ?: "null")
        }.toString()
    }

    private fun renderPresentation(presentation: XValuePresentation): String {
        val sb = StringBuilder()
        presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
            override fun renderValue(value: String) { sb.append(value) }
            override fun renderStringValue(value: String) { sb.append("\"$value\"") }
            override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { sb.append("\"$value\"") }
            override fun renderNumericValue(value: String) { sb.append(value) }
            override fun renderKeywordValue(value: String) { sb.append(value) }
            override fun renderValue(value: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(value) }
            override fun renderComment(comment: String) {}
            override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
            override fun renderError(error: String) { sb.append("error: $error") }
        })
        return sb.toString().ifEmpty { "[complex value]" }
    }

    private fun resolveFile(project: com.intellij.openapi.project.Project, filePath: String): VirtualFile? {
        LocalFileSystem.getInstance().findFileByPath(filePath)?.let { return it }
        val projectDir = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath("$projectDir/$filePath")
    }
}
