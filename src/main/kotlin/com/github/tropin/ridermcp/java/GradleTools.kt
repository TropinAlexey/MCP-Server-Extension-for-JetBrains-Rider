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
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GradleToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages Gradle projects. " +
            "action='tasks' lists available tasks. " +
            "action='build' builds the project. " +
            "action='test' runs tests. " +
            "action='assemble' assembles artifacts. " +
            "action='check' runs all checks. " +
            "action='clean' cleans build directory. " +
            "action='dependencies' prints dependency tree. " +
            "action='dependencyInsight' analyzes a specific dependency. " +
            "action='wrapper' generates/updates Gradle wrapper. " +
            "action='help' shows help for a task. " +
            "projectPath scopes to subdirectory."
    )
    suspend fun rider_gradle(
        @McpDescription("Action: tasks, build, test, assemble, check, clean, dependencies, dependencyInsight, wrapper, help") action: String = "tasks",
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Gradle task name for 'help' action") task: String? = null,
        @McpDescription("Dependency notation for 'dependencyInsight' (e.g., 'org.springframework:spring-core')") dependency: String? = null,
        @McpDescription("Additional Gradle arguments (e.g., '--info', '--stacktrace', '-PmyProp=value')") extraArgs: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return runGradle(workDir, action, extraArgs)
    }

    private suspend fun runGradle(workDir: String, action: String, extraArgs: String?): String {
        val gradleCmd = detectGradleCmd(workDir)
        val cmd = mutableListOf<String>(gradleCmd)
        cmd.add(action)
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 300)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private fun detectGradleCmd(workDir: String): String {
        val files = Path.of(workDir).toFile().listFiles() ?: return "gradle"
        if (files.any { it.name == "gradlew" }) return "./gradlew"
        if (files.any { it.name == "gradlew.bat" }) return "gradlew.bat"
        return "gradle"
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
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}
