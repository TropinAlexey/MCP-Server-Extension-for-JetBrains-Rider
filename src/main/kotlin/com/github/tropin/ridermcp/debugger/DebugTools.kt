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
    @McpDescription("Sets a line breakpoint in the debugger. filePath relative to project root or absolute. line is 1-indexed. Use before rider_start_debug to set up breakpoints for debugging.")
    suspend fun rider_set_breakpoint(
        @McpDescription("File path (relative or absolute)") filePath: String,
        @McpDescription("Line number (1-indexed)") line: Int
    ): String {
        val project = coroutineContext.project
        val vf = resolveFile(project, filePath)
            ?: mcpFail("File not found: $filePath")
        val lineIndex = line - 1

        val bm = XDebuggerManager.getInstance(project).breakpointManager
        val existing = bm.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
            .find { it.fileUrl == vf.url && it.line == lineIndex }
        if (existing != null) return "Breakpoint already set at $filePath:$line"

        runOnEdt {
            XDebuggerUtil.getInstance().toggleLineBreakpoint(project, vf, lineIndex, false)
        }
        return "Breakpoint set at $filePath:$line"
    }

    @McpTool
    @McpDescription("Removes a line breakpoint at file:line. Use to clean up breakpoints after debugging.")
    suspend fun rider_remove_breakpoint(
        @McpDescription("File path (relative or absolute)") filePath: String,
        @McpDescription("Line number (1-indexed)") line: Int
    ): String {
        val project = coroutineContext.project
        val vf = resolveFile(project, filePath)
            ?: mcpFail("File not found: $filePath")
        val lineIndex = line - 1

        val bm = XDebuggerManager.getInstance(project).breakpointManager
        val toRemove = bm.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
            .filter { it.fileUrl == vf.url && it.line == lineIndex }
        if (toRemove.isEmpty()) mcpFail("No breakpoint at $filePath:$line")

        runOnEdt {
            toRemove.forEach { bm.removeBreakpoint(it) }
        }
        return "Breakpoint removed at $filePath:$line"
    }

    @McpTool
    @McpDescription("Launches a debug session for a run configuration (starts the app with debugger attached). Omit configName to use the selected one. Poll output with rider_get_output, check paused/running state with rider_debug_state.")
    suspend fun rider_start_debug(
        @McpDescription("Run configuration name (omit for selected)") configName: String? = null
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

        val session = SessionManager.create("debug")
        session.appendLine("Debugging: ${settings.name}")
        val cfgName = settings.name

        ApplicationManager.getApplication().invokeLater {
            val connection = project.messageBus.connect()
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
            })
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
        }

        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    @McpTool
    @McpDescription("Returns current debug session state: status (running/paused/stopped), current file and line when paused at breakpoint, full stack trace with file locations. Use to check where the debugger stopped or whether it's still running.")
    suspend fun rider_debug_state(): String {
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

    @McpTool
    @McpDescription("Evaluates an expression in the current debug frame (watch expression). Use to inspect variable values, call methods, check object state, or compute values while paused at a breakpoint. Debugger must be paused.")
    suspend fun rider_debug_evaluate(
        @McpDescription("Expression to evaluate") expression: String
    ): String {
        val project = coroutineContext.project
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: mcpFail("No active debug session")
        if (!session.isPaused) mcpFail("Debugger is not paused")

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

    @McpTool
    @McpDescription("Controls debugger execution flow. Actions: stepOver (next line), stepInto (enter method), stepOut (exit method), resume (continue to next breakpoint), pause (break running program), stop (end debug session). Use to navigate through code during debugging.")
    suspend fun rider_debug_step(
        @McpDescription("Action: stepOver, stepInto, stepOut, resume, pause, stop") action: String
    ): String {
        val project = coroutineContext.project
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: mcpFail("No active debug session")

        when (action) {
            "stepOver" -> { if (!session.isPaused) mcpFail("Not paused"); session.stepOver(false) }
            "stepInto" -> { if (!session.isPaused) mcpFail("Not paused"); session.stepInto() }
            "stepOut" -> { if (!session.isPaused) mcpFail("Not paused"); session.stepOut() }
            "resume" -> { if (!session.isPaused) mcpFail("Not paused"); session.resume() }
            "pause" -> { if (session.isPaused) mcpFail("Already paused"); session.pause() }
            "stop" -> session.stop()
            else -> mcpFail("Unknown action: $action. Use: stepOver, stepInto, stepOut, resume, pause, stop")
        }
        return "ok"
    }

    private fun resolveFile(project: com.intellij.openapi.project.Project, filePath: String): VirtualFile? {
        LocalFileSystem.getInstance().findFileByPath(filePath)?.let { return it }
        val projectDir = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath("$projectDir/$filePath")
    }
}
