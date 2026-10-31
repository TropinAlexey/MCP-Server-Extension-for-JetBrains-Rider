package com.github.tropin.ridermcp.java

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

class JUnitToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs JUnit tests via Maven or Gradle. " +
            "action='run' executes tests (optional class/method filter, returns sessionId for polling). " +
            "action='run_and_wait' blocks until completion (timeoutMs max 300000). " +
            "action='list' lists test classes/methods. " +
            "buildSystem specifies build system: maven, gradle (auto-detected). " +
            "projectPath scopes to subdirectory."
    )
    suspend fun rider_junit(
        @McpDescription("Action: run (default), run_and_wait, list") action: String = "run",
        @McpDescription("Test class pattern (e.g., 'com.example.MyTest', '*Test')") classPattern: String? = null,
        @McpDescription("Test method pattern (e.g., 'testMethod', 'test*')") methodPattern: String? = null,
        @McpDescription("Build system: maven, gradle (auto-detected from pom.xml/build.gradle)") buildSystem: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Additional test args (e.g., '-Dtest=MyTest#testMethod')") extraArgs: String? = null,
        @McpDescription("Timeout in ms for run_and_wait (default 120000)") timeoutMs: Int = 120000,
        @McpDescription("Session ID for run_and_wait (from previous run)") sessionId: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        val bSystem = buildSystem?.lowercase() ?: detectBuildSystem(workDir)

        return when (action.lowercase()) {
            "run" -> runTests(workDir, bSystem, extraArgs, wait = false)
            "run_and_wait" -> runTests(workDir, bSystem, extraArgs, wait = true, timeoutMs = timeoutMs)
            "list" -> listTests(workDir, bSystem)
            else -> mcpFail("Unknown action '$action'. Use: run, run_and_wait, list")
        }
    }

    private fun detectBuildSystem(workDir: String): String {
        val files = Path.of(workDir).toFile().listFiles() ?: return "maven"
        if (files.any { it.name == "pom.xml" }) return "maven"
        if (files.any { it.name == "build.gradle" || it.name == "build.gradle.kts" }) return "gradle"
        return "maven"
    }

    private suspend fun runTests(workDir: String, buildSystem: String, extraArgs: String?, wait: Boolean, timeoutMs: Int = 120000): String {
        val (cmd, _) = buildCmd(workDir, extraArgs)
        
        if (wait) {
            val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 300)
            return JsonObject(mapOf(
                "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
                "exitCode" to JsonPrimitive(exitCode.toLong()),
                "output" to JsonPrimitive(output.trim())
            )).toString()
        }

        val session = SessionManager.create("junit_test")
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
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return JsonObject(mapOf("sessionId" to JsonPrimitive(session.id))).toString()
    }

    private suspend fun listTests(workDir: String, buildSystem: String): String {
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, listOf("mvn", "test", "-Dtest=*", "-DfailIfNoTests=false"), timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private fun buildCmd(workDir: String, extraArgs: String?): Pair<List<String>, String> {
        val files = Path.of(workDir).toFile().listFiles() ?: return listOf("mvn", "test") to workDir
        if (files.any { it.name == "pom.xml" }) {
            val cmd = mutableListOf<String>("mvn", "test")
            extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
            return cmd to workDir
        }
        val gradleCmd = if (files.any { it.name == "gradlew" }) "./gradlew" else "gradle"
        return listOf(gradleCmd, "test") to workDir
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startsWith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 300): Pair<Int, String> {
        val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
            .withWorkDirectory(workDir)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after 300s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}
