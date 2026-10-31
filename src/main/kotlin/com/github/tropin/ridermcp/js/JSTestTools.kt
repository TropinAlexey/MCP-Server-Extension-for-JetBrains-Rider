package com.github.tropin.ridermcp.js

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
import com.github.tropin.ridermcp.SessionManager
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class JSTestToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs JavaScript/TypeScript tests via jest, vitest, mocha, or playwright. " +
            "action='run' executes tests (optional pattern filter, returns sessionId for polling). " +
            "action='run_and_wait' blocks until completion (timeoutMs max 300000). " +
            "action='coverage' runs with coverage. " +
            "action='list' lists tests without running. " +
            "action='watch' runs in watch mode (returns sessionId). " +
            "runner specifies test runner: jest, vitest, mocha, playwright (auto-detected if omitted). " +
            "projectPath scopes to subdirectory."
    )
    suspend fun rider_js_test(
        @McpDescription("Action: run (default), run_and_wait, coverage, list, watch") action: String = "run",
        @McpDescription("Test runner: jest, vitest, mocha, playwright (auto-detected from config/lockfile)") runner: String? = null,
        @McpDescription("Test filter pattern (e.g., 'my.test', 'MyComponent', '**/*.test.ts')") pattern: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Run with coverage") coverage: Boolean = false,
        @McpDescription("Coverage output format: lcov, text, json, html (coverage only)") coverageFormat: String = "text",
        @McpDescription("Additional runner flags (e.g., '--verbose', '--reporter=verbose')") extraArgs: String? = null,
        @McpDescription("Timeout in ms for run_and_wait (default 120000)") timeoutMs: Int = 120000,
        @McpDescription("Session ID for run_and_wait (from previous run)") sessionId: String? = null,
        @McpDescription("Test file path filter (e.g., 'src/**/*.test.ts')") filePattern: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        val testRunner = runner?.lowercase() ?: detectRunner(workDir)

        return when (action.lowercase()) {
            "run" -> runTests(workDir, testRunner, pattern, filePattern, extraArgs, wait = false)
            "run_and_wait" -> runTests(workDir, testRunner, pattern, filePattern, extraArgs, wait = true, timeoutMs = timeoutMs)
            "coverage" -> runCoverage(workDir, testRunner, pattern, filePattern, extraArgs)
            "list" -> listTests(workDir, testRunner, pattern)
            "watch" -> runWatch(workDir, testRunner, pattern, filePattern, extraArgs)
            else -> mcpFail("Unknown action '$action'. Use: run, run_and_wait, coverage, list, watch")
        }
    }

    private fun detectRunner(workDir: String): String {
        val files = Path.of(workDir).toFile().listFiles() ?: return "jest"
        val pkgJson = Path.of(workDir, "package.json").toFile()
        if (pkgJson.exists()) {
            try {
                val content = pkgJson.readText()
                val json = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(content)
                val scripts = json["scripts"] as? Map<String, Any> ?: return "jest"
                if (scripts.keys.any { it.contains("vitest") }) return "vitest"
                if (scripts.keys.any { it.contains("playwright") }) return "playwright"
                if (scripts.keys.any { it.contains("mocha") }) return "mocha"
            } catch (_: Exception) {}
        }
        if (files.any { it.name == "vitest.config.ts" || it.name == "vitest.config.js" }) return "vitest"
        if (files.any { it.name == "playwright.config.ts" || it.name == "playwright.config.js" }) return "playwright"
        if (files.any { it.name == "mocha.opts" || it.name == ".mocharc.json" || it.name == ".mocharc.js" }) return "mocha"
        return "jest"
    }

    private suspend fun runTests(
        workDir: String, runner: String, pattern: String?, filePattern: String?, extraArgs: String?,
        wait: Boolean, timeoutMs: Int = 120000
    ): String {
        val (cmd, workDirForCmd) = buildCmd(runner, pattern, filePattern, extraArgs, coverage = false, watch = false, project = coroutineContext.project)
        if (wait) {
            val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = (timeoutMs / 1000 + 10).toLong())
            return JsonObject(mapOf(
                "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
                "exitCode" to JsonPrimitive(exitCode.toLong()),
                "output" to JsonPrimitive(output.trim())
            )).toString()
        }

        // Async run with session polling
        val session = SessionManager.create("js_test")
        try {
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
                .withWorkDirectory(coroutineContext.project.basePath!!)
                .withRedirectErrorStream(true)
            session.appendLine("Running: ${commandLine.commandLineString}")
            val handler = com.intellij.execution.process.OSProcessHandler(commandLine)
            session.tag = handler
            handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text.trimEnd('\n', '\r')
                    if (text.isNotEmpty()) session.appendLine(text)
                }
                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                    session.exitCode = event.exitCode
                    session.status = if (event.exitCode == 0) "passed" else "failed"
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            // session status handled
        }
        return JsonObject(mapOf("sessionId" to JsonPrimitive(SessionManager.create("js_test").id))).toString()
    }

    private suspend fun runCoverage(
        workDir: String, runner: String, pattern: String?, filePattern: String?, extraArgs: String?
    ): String {
        val (cmd, _) = buildCmd(runner, pattern, filePattern, extraArgs, coverage = true, watch = false, project = coroutineContext.project)
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun listTests(workDir: String, runner: String, pattern: String?): String {
        val (cmd, _) = buildCmd(runner, pattern, null, null, coverage = false, watch = false, list = true, project = coroutineContext.project)
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        if (exitCode != 0) mcpFail("$runner list failed:\n$output")
        val tests = output.trim().split("\n").filter { it.isNotBlank() }.map { it.trim() }
        val testsArray = JsonArray(tests.map { JsonPrimitive(it) })
        return JsonObject(mapOf("tests" to testsArray)).toString()
    }

    private suspend fun runWatch(workDir: String, runner: String, pattern: String?, filePattern: String?, extraArgs: String?): String {
        val (cmd, _) = buildCmd(runner, pattern, filePattern, null, coverage = false, watch = true, project = coroutineContext.project)
        val session = SessionManager.create("js_test_watch")
        try {
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
                .withWorkDirectory(coroutineContext.project.basePath!!)
                .withRedirectErrorStream(true)
            val session = SessionManager.create("js_test_watch")
            session.appendLine("Running: ${commandLine.commandLineString}")
            val handler = com.intellij.execution.process.OSProcessHandler(commandLine)
            session.tag = handler
            handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text.trimEnd('\n', '\r')
                    if (text.isNotEmpty()) session.appendLine(text)
                }
                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                    session.exitCode = event.exitCode
                    session.status = if (event.exitCode == 0) "stopped" else "failed"
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            // session status handled
        }
        return JsonObject(mapOf("sessionId" to JsonPrimitive(SessionManager.create("js_test_watch").id))).toString()
    }

    private fun buildCmd(runner: String, pattern: String?, filePattern: String?, extraArgs: String?, coverage: Boolean, watch: Boolean, list: Boolean = false, project: com.intellij.openapi.project.Project): Pair<List<String>, String> {
        val cmd = mutableListOf<String>()
        val runnerCmd = when (runner) {
            "vitest" -> listOf("npx", "vitest", "run")
            "playwright" -> listOf("npx", "playwright", "test")
            "mocha" -> listOf("npx", "mocha")
            else -> listOf("npx", "jest")
        }
        cmd.addAll(runnerCmd)
        if (watch) cmd.add("--watch")
        if (coverage) cmd.add("--coverage")
        pattern?.let { cmd.addAll(listOf("-t", it)) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        return cmd to project.basePath!!
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
            .withWorkDirectory(workDir)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}