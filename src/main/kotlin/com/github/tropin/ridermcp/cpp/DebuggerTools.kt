package com.github.tropin.ridermcp.cpp

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DebuggerToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Controls GDB/LLDB debugger sessions in CLion. " +
            "action='start' starts a debug session (requires executable path). " +
            "action='attach' attaches to running process by PID. " +
            "action='break' sets a breakpoint (file:line or function). " +
            "action='continue' continues execution. " +
            "action='step' steps over. " +
            "action='step_in' steps into. " +
            "action='step_out' steps out. " +
            "action='evaluate' evaluates expression in current frame. " +
            "action='backtrace' shows call stack. " +
            "action='locals' shows local variables. " +
            "action='registers' shows register values. " +
            "action='threads' lists threads. " +
            "action='stop' stops debugging. " +
            "debugger specifies debugger: gdb, lldb (auto-detected). " +
            "executable path to debug (required for start). " +
            "args program arguments. " +
            "pid process ID for attach. " +
            "breakpoint location (file:line or function) for break action. " +
            "expression for evaluate action."
    )
    suspend fun rider_debugger(
        @McpDescription("Action: start (default), attach, break, continue, step, step_in, step_out, evaluate, backtrace, locals, registers, threads, stop") action: String = "start",
        @McpDescription("Debugger: gdb, lldb (auto-detected from system)") debugger: String? = null,
        @McpDescription("Executable path to debug (required for start)") executable: String? = null,
        @McpDescription("Program arguments (space-separated)") programArgs: String? = null,
        @McpDescription("Process ID for attach") pid: Int? = null,
        @McpDescription("Breakpoint location (file:line or function name)") breakpoint: String? = null,
        @McpDescription("Expression to evaluate in current frame") expression: String? = null
    ): String {
        val debug = debugger?.lowercase() ?: detectDebugger()

        return when (action.lowercase()) {
            "start" -> {
                if (executable.isNullOrBlank()) mcpFail("executable is required for start")
                startDebug(executable!!, programArgs)
            }
            "attach" -> {
                val p = pid ?: mcpFail("pid is required for attach")
                attach(p)
            }
            "break" -> {
                if (breakpoint.isNullOrBlank()) mcpFail("breakpoint is required for break")
                setBreakpoint(breakpoint!!)
            }
            "continue" -> continueDebug()
            "step" -> stepOver()
            "step_in" -> stepInto()
            "step_out" -> stepOut()
            "evaluate" -> {
                if (expression.isNullOrBlank()) mcpFail("expression is required for evaluate")
                evaluate(expression!!)
            }
            "backtrace" -> backtrace()
            "locals" -> locals()
            "registers" -> registers()
            "threads" -> threads()
            "stop" -> stopDebug()
            else -> mcpFail("Unknown action '$action'. Use: start, attach, break, continue, step, step_in, step_out, evaluate, backtrace, locals, registers, threads, stop")
        }
    }

    private fun detectDebugger(): String {
        val lldb = Path.of("/usr/bin/lldb").toFile()
        val gdb = Path.of("/usr/bin/gdb").toFile()
        if (lldb.exists()) return "lldb"
        if (gdb.exists()) return "gdb"
        return "gdb"
    }

    private suspend fun startDebug(executable: String, programArgs: String?): String {
        val cmd = mutableListOf<String>()
        when (detectDebugger()) {
            "lldb" -> {
                cmd.addAll(listOf("lldb", executable))
                programArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
                cmd.addAll(listOf("-o", "run", "-o", "bt", "-b"))
            }
            else -> {
                cmd.addAll(listOf("gdb", executable))
                programArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
                cmd.addAll(listOf("-ex", "run", "-ex", "bt", "-batch"))
            }
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "started" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun attach(pid: Int): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-p", pid.toString(), "-o", "bt", "-b")
            else -> listOf("gdb", "-p", pid.toString(), "-ex", "bt", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "attached" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun setBreakpoint(location: String): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "breakpoint set -- $location", "-b")
            else -> listOf("gdb", "-ex", "break $location", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "breakpoint_set" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun continueDebug(): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "continue", "-b")
            else -> listOf("gdb", "-ex", "continue", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "continued" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun stepOver(): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "thread step-over", "-b")
            else -> listOf("gdb", "-ex", "next", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "stepped_over" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun stepInto(): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "thread step-in", "-b")
            else -> listOf("gdb", "-ex", "step", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "stepped_in" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun stepOut(): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "thread step-out", "-b")
            else -> listOf("gdb", "-ex", "finish", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "stepped_out" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun evaluate(expression: String): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "expr $expression", "-b")
            else -> listOf("gdb", "-ex", "print $expression", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "evaluated" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun backtrace(): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "bt", "-b")
            else -> listOf("gdb", "-ex", "bt", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun locals(): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "frame variable", "-b")
            else -> listOf("gdb", "-ex", "info locals", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun registers(): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "register read", "-b")
            else -> listOf("gdb", "-ex", "info registers", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun threads(): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "thread list", "-b")
            else -> listOf("gdb", "-ex", "info threads", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun stopDebug(): String {
        val debug = detectDebugger()
        val cmd = when (detectDebugger()) {
            "lldb" -> listOf("lldb", "-o", "quit", "-b")
            else -> listOf("gdb", "-ex", "quit", "-batch")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "stopped" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startsWith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 60): Pair<Int, String> {
        val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
            .withWorkDirectory(coroutineContext.project.basePath!!)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(60).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after 60s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}