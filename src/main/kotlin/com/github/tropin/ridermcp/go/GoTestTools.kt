package com.github.tropin.ridermcp.go

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

class GoTestToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs Go tests via 'go test'. " +
            "action='run' executes tests (optional package pattern, returns sessionId for polling). " +
            "action='run_and_wait' blocks until completion (timeoutMs max 300000). " +
            "action='cover' runs with coverage profile. " +
            "action='bench' runs benchmarks. " +
            "action='list' lists tests without running. " +
            "action='vet' runs go vet on packages. " +
            "projectPath scopes to subdirectory. " +
            "Use 'race' for race detector, 'count' for flaky test detection."
    )
    suspend fun rider_go_test(
        @McpDescription("Action: run (default), run_and_wait, cover, bench, list, vet") action: String = "run",
        @McpDescription("Package pattern (e.g., './...', 'pkg/...', 'pkg')") pkg: String = "./...",
        @McpDescription("Test filter regex (go test -run)") runFilter: String? = null,
        @McpDescription("Benchmark filter regex (bench only)") benchFilter: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Enable race detector") race: Boolean = false,
        @McpDescription("Run tests N times (flaky detection)") count: Int = 1,
        @McpDescription("Coverage output file (cover only)") coverProfile: String? = null,
        @McpDescription("Additional go test flags (e.g., '-v', '-short', '-parallel 4')") extraArgs: String? = null,
        @McpDescription("Timeout in ms for run_and_wait (default 120000)") timeoutMs: Int = 120000,
        @McpDescription("Session ID for run_and_wait (from previous run)") sessionId: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "run" -> runTests(workDir, pkg, runFilter, extraArgs, race, count, wait = false)
            "run_and_wait" -> runTests(workDir, pkg, runFilter, extraArgs, race, count, wait = true, timeoutMs = timeoutMs)
            "cover" -> runCover(workDir, pkg, runFilter, extraArgs, coverProfile)
            "bench" -> runBench(workDir, pkg, benchFilter, extraArgs)
            "list" -> listTests(workDir, pkg, runFilter)
            "vet" -> runVet(workDir, pkg)
            else -> mcpFail("Unknown action '$action'. Use: run, run_and_wait, cover, bench, list, vet")
        }
    }

    private suspend fun runTests(
        workDir: String, pkg: String, runFilter: String?, extraArgs: String?,
        race: Boolean, count: Int, wait: Boolean, timeoutMs: Int = 120000
    ): String {
        val syncCmd = mutableListOf<String>("go", "test")
        if (race) syncCmd.add("-race")
        if (count > 1) syncCmd.addAll(listOf("-count", count.toString()))
        runFilter?.let { syncCmd.add("-run"); syncCmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { syncCmd.add(it) }
        syncCmd.add(pkg)

        if (wait) {
            val (exitCode, output) = runCmd(workDir, syncCmd, timeout = (timeoutMs / 1000 + 10).toLong())
            return JsonObject(mapOf(
                "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
                "exitCode" to JsonPrimitive(exitCode.toLong()),
                "output" to JsonPrimitive(output.trim())
            )).toString()
        }

        // Async run with session polling
        val session = SessionManager.create("go_test")
        val asyncCmd = mutableListOf<String>("go", "test")
        if (race) asyncCmd.add("-race")
        if (count > 1) asyncCmd.addAll(listOf("-count", count.toString()))
        runFilter?.let { asyncCmd.add("-run"); asyncCmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { asyncCmd.add(it) }
        asyncCmd.add(pkg)

        try {
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*asyncCmd.toTypedArray())
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
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return JsonObject(mapOf("sessionId" to JsonPrimitive(session.id))).toString()
    }

    private suspend fun runCover(workDir: String, pkg: String, runFilter: String?, extraArgs: String?, coverProfile: String?): String {
        val cmd = mutableListOf<String>("go", "test", "-cover")
        coverProfile?.let { cmd.addAll(listOf("-coverprofile", it)) }
        runFilter?.let { cmd.add("-run"); cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        cmd.add(pkg)
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun runBench(workDir: String, pkg: String, benchFilter: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("go", "test", "-bench=.", "-benchmem")
        benchFilter?.let { cmd.add("-run"); cmd.add("^$"); cmd.add("-bench"); cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        cmd.add(pkg)
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun listTests(workDir: String, pkg: String, runFilter: String?): String {
        val cmd = mutableListOf<String>("go", "test", "-list=.")
        runFilter?.let { cmd.add("-run"); cmd.add(it) }
        cmd.add(pkg)
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 30)
        if (exitCode != 0) mcpFail("go test -list failed:\n$output")
        val tests = output.trim().split("\n").filter { it.startsWith("    ") || it.contains("_test") }.map { it.trim() }
        val testsArray = JsonArray(tests.map { JsonPrimitive(it) })
        return JsonObject(mapOf("tests" to testsArray)).toString()
    }

    private suspend fun runVet(workDir: String, pkg: String): String {
        val (exitCode, output) = runCmd(workDir, listOf("go", "vet", pkg), timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "issues_found"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
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
            .withWorkDirectory(workDir)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}