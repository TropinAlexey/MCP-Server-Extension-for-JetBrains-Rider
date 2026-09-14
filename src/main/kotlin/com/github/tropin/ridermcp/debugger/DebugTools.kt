package com.github.tropin.ridermcp.debugger

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.mcpJson
import com.github.tropin.ridermcp.projectDir
import com.github.tropin.ridermcp.relTo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon

// === Breakpoints ===

@Serializable
data class BreakpointArgs(val filePath: String, val line: Int)

class SetBreakpointTool : AbstractMcpTool<BreakpointArgs>(BreakpointArgs.serializer()) {
    override val name = "rider_set_breakpoint"
    override val description = "Sets a line breakpoint. filePath relative to project root or absolute. line is 1-indexed."

    override fun handle(project: Project, args: BreakpointArgs): Response {
        val vf = resolveFile(project, args.filePath)
            ?: return Response(error = "File not found: ${args.filePath}")
        val lineIndex = args.line - 1

        val bm = XDebuggerManager.getInstance(project).breakpointManager
        val existing = bm.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
            .find { it.fileUrl == vf.url && it.line == lineIndex }
        if (existing != null) return Response("Breakpoint already set at ${args.filePath}:${args.line}")

        ApplicationManager.getApplication().invokeAndWait {
            XDebuggerUtil.getInstance().toggleLineBreakpoint(project, vf, lineIndex, false)
        }
        return Response("Breakpoint set at ${args.filePath}:${args.line}")
    }
}

class RemoveBreakpointTool : AbstractMcpTool<BreakpointArgs>(BreakpointArgs.serializer()) {
    override val name = "rider_remove_breakpoint"
    override val description = "Removes a line breakpoint at file:line."

    override fun handle(project: Project, args: BreakpointArgs): Response {
        val vf = resolveFile(project, args.filePath)
            ?: return Response(error = "File not found: ${args.filePath}")
        val lineIndex = args.line - 1

        val bm = XDebuggerManager.getInstance(project).breakpointManager
        val toRemove = bm.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
            .filter { it.fileUrl == vf.url && it.line == lineIndex }
        if (toRemove.isEmpty()) return Response(error = "No breakpoint at ${args.filePath}:${args.line}")

        ApplicationManager.getApplication().invokeAndWait {
            toRemove.forEach { bm.removeBreakpoint(it) }
        }
        return Response("Breakpoint removed at ${args.filePath}:${args.line}")
    }
}

// === Debug Session ===

@Serializable
data class StartDebugArgs(val configName: String? = null)

class StartDebugTool : AbstractMcpTool<StartDebugArgs>(StartDebugArgs.serializer()) {
    override val name = "rider_start_debug"
    override val description = "Starts debugging a run configuration. Omit configName to use the selected one. Poll with rider_get_output, check state with rider_debug_state."

    override fun handle(project: Project, args: StartDebugArgs): Response {
        val runManager = RunManager.getInstance(project)
        val settings = if (args.configName != null) {
            runManager.allSettings.find { it.name == args.configName }
                ?: return Response(error = "Configuration '${args.configName}' not found")
        } else {
            runManager.selectedConfiguration
                ?: return Response(error = "No active run configuration. Specify configName.")
        }

        val session = SessionManager.create("debug")
        session.appendLine("Debugging: ${settings.name}")
        val configName = settings.name

        ApplicationManager.getApplication().invokeLater {
            val connection = project.messageBus.connect()
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    if (env.runProfile.name != configName) return
                    connection.disconnect()
                    session.tag = handler
                    handler.addProcessListener(object : ProcessAdapter() {
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

        return Response(mcpJson.encodeToString(mapOf("sessionId" to session.id)))
    }
}

// === Debug State ===

class DebugStateTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "rider_debug_state"
    override val description = "Returns debug session state: status (running/paused/stopped), current position, and stack trace when paused."

    override fun handle(project: Project, args: NoArgs): Response {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: return Response(error = "No active debug session")

        val projectDir = project.projectDir()
        val result = buildJsonObject {
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
        }
        return Response(result.toString())
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
}

// === Evaluate ===

@Serializable
data class EvaluateArgs(val expression: String)

class DebugEvaluateTool : AbstractMcpTool<EvaluateArgs>(EvaluateArgs.serializer()) {
    override val name = "rider_debug_evaluate"
    override val description = "Evaluates an expression in the current debug frame. Use to inspect variables, call methods, or compute values. Debugger must be paused."

    override fun handle(project: Project, args: EvaluateArgs): Response {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: return Response(error = "No active debug session")
        if (!session.isPaused) return Response(error = "Debugger is not paused")

        val frame = session.currentStackFrame
            ?: return Response(error = "No current stack frame")
        val evaluator = frame.evaluator
            ?: return Response(error = "Evaluator not available for current frame")

        var resultType: String? = null
        var resultValue: String? = null
        var error: String? = null
        val latch = CountDownLatch(1)

        evaluator.evaluate(args.expression, object : XDebuggerEvaluator.XEvaluationCallback {
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

        if (!latch.await(10, TimeUnit.SECONDS)) return Response(error = "Evaluation timed out")
        if (error != null) return Response(error = error)

        val response = buildJsonObject {
            resultType?.let { put("type", it) }
            put("value", resultValue ?: "null")
        }
        return Response(response.toString())
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
}

// === Step ===

@Serializable
data class StepArgs(val action: String)

class DebugStepTool : AbstractMcpTool<StepArgs>(StepArgs.serializer()) {
    override val name = "rider_debug_step"
    override val description = "Controls debug execution. action: stepOver, stepInto, stepOut, resume, pause, stop."

    override fun handle(project: Project, args: StepArgs): Response {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: return Response(error = "No active debug session")

        when (args.action) {
            "stepOver" -> { if (!session.isPaused) return Response(error = "Not paused"); session.stepOver(false) }
            "stepInto" -> { if (!session.isPaused) return Response(error = "Not paused"); session.stepInto() }
            "stepOut" -> { if (!session.isPaused) return Response(error = "Not paused"); session.stepOut() }
            "resume" -> { if (!session.isPaused) return Response(error = "Not paused"); session.resume() }
            "pause" -> { if (session.isPaused) return Response(error = "Already paused"); session.pause() }
            "stop" -> session.stop()
            else -> return Response(error = "Unknown action: ${args.action}. Use: stepOver, stepInto, stepOut, resume, pause, stop")
        }
        return Response("ok")
    }
}

// === Helpers ===

private fun resolveFile(project: Project, filePath: String): VirtualFile? {
    LocalFileSystem.getInstance().findFileByPath(filePath)?.let { return it }
    val projectDir = project.basePath ?: return null
    return LocalFileSystem.getInstance().findFileByPath("$projectDir/$filePath")
}
