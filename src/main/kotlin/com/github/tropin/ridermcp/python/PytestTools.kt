package com.github.tropin.ridermcp.python

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

class PytestToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs pytest inside a specific Python environment. " +
            "action='run' executes tests (optional filter for test selection, returns sessionId for polling). " +
            "action='run_and_wait' blocks until completion (timeoutMs max 300000). " +
            "action='coverage' runs with coverage (requires pytest-cov). " +
            "action='collect' lists tests without running. " +
            "action='rerun_failed' re-runs only failed tests from last run. " +
            "envType/envName specify the Python environment. " +
            "projectPath scopes to subdirectory (e.g., where pyproject.toml/pytest.ini lives)."
    )
    suspend fun rider_pytest(
        @McpDescription("Action: run (default), run_and_wait, coverage, collect, rerun_failed") action: String = "run",
        @McpDescription("Test filter: 'module', 'module::Class', 'module::Class::method', or pytest -k expression") filter: String? = null,
        @McpDescription("Environment type: venv, conda, poetry, pipenv") envType: String = "venv",
        @McpDescription("Environment name (required)") envName: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Additional pytest args (e.g. '-v', '--tb=short', '-x')") extraArgs: String? = null,
        @McpDescription("Coverage output format: term, html, xml (coverage only)") coverageFormat: String = "term",
        @McpDescription("Timeout in ms for run_and_wait (default 120000)") timeoutMs: Int = 120000,
        @McpDescription("Session ID for run_and_wait/rerun_failed (from previous run)") sessionId: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        if (envName.isNullOrBlank()) mcpFail("envName is required (use rider_python_env to list/create environments)")

        val (pytestCmd, envWorkDir) = resolvePytestCmd(workDir, envType, envName)

        return when (action.lowercase()) {
            "run" -> runTests(envWorkDir, pytestCmd, filter, extraArgs, wait = false)
            "run_and_wait" -> runTests(envWorkDir, pytestCmd, filter, extraArgs, wait = true, timeoutMs = timeoutMs)
            "coverage" -> runCoverage(envWorkDir, pytestCmd, filter, extraArgs, coverageFormat)
            "collect" -> collectTests(envWorkDir, pytestCmd, filter)
            "rerun_failed" -> rerunFailed(envWorkDir, pytestCmd, filter, extraArgs, sessionId)
            else -> mcpFail("Unknown action '$action'. Use: run, run_and_wait, coverage, collect, rerun_failed")
        }
    }

    private fun resolvePytestCmd(workDir: String, envType: String, envName: String): Pair<List<String>, String> {
        return when (envType.lowercase()) {
            "venv" -> {
                val envPath = Path.of(workDir, envName)
                listOf("${envPath}/bin/pytest") to envPath.toString()
            }
            "conda" -> listOf("conda", "run", "-n", envName, "pytest") to workDir
            "poetry" -> listOf("poetry", "run", "pytest") to workDir
            "pipenv" -> listOf("pipenv", "run", "pytest") to workDir
            else -> mcpFail("Unknown env type '$envType'. Use: venv, conda, poetry, pipenv")
        }
    }

    private suspend fun runTests(
        envWorkDir: String, pytestCmd: List<String>, filter: String?, extraArgs: String?,
        wait: Boolean, timeoutMs: Int = 120000
    ): String {
        val cmd = mutableListOf<String>()
        cmd.addAll(pytestCmd)
        cmd.addAll(listOf("-v", "--tb=short"))
        filter?.let { cmd.add("-k"); cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }

        if (wait) {
            val (exitCode, output) = runCmd(envWorkDir, cmd, timeout = (timeoutMs / 1000 + 10).toLong())
            return JsonObject(mapOf(
                "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
                "exitCode" to JsonPrimitive(exitCode.toLong()),
                "output" to JsonPrimitive(output.trim())
            )).toString()
        }

        // Async run with session polling
        val session = SessionManager.create("pytest")
        try {
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
                .withWorkDirectory(envWorkDir)
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
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return JsonObject(mapOf("sessionId" to JsonPrimitive(session.id))).toString()
    }

    private suspend fun runCoverage(
        envWorkDir: String, pytestCmd: List<String>, filter: String?, extraArgs: String?, coverageFormat: String
    ): String {
        val cmd = mutableListOf<String>()
        cmd.addAll(pytestCmd)
        cmd.addAll(listOf("--cov", "--cov-report=$coverageFormat", "-v"))
        filter?.let { cmd.add("-k"); cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(envWorkDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun collectTests(envWorkDir: String, pytestCmd: List<String>, filter: String?): String {
        val cmd = mutableListOf<String>()
        cmd.addAll(pytestCmd)
        cmd.addAll(listOf("--collect-only", "-q"))
        filter?.let { cmd.add("-k"); cmd.add(it) }
        val (exitCode, output) = runCmd(envWorkDir, cmd, timeout = 30)
        if (exitCode != 0) mcpFail("pytest collect failed:\n$output")
        val tests = output.trim().split("\n").filter { it.startsWith("    ") || it.contains("::") }.map { it.trim() }
        val testsArray = JsonArray(tests.map { JsonPrimitive(it) })
        return JsonObject(mapOf("tests" to testsArray)).toString()
    }

    private suspend fun rerunFailed(
        envWorkDir: String, pytestCmd: List<String>, filter: String?, extraArgs: String?, sessionId: String?
    ): String {
        val cmd = mutableListOf<String>()
        cmd.addAll(pytestCmd)
        cmd.addAll(listOf("--lf", "-v", "--tb=short"))
        filter?.let { cmd.add("-k"); cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        return runTests(envWorkDir, cmd, null, null, wait = true)
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startsWith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 30): Pair<Int, String> {
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
