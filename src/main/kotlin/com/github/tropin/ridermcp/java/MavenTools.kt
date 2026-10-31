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

class MavenToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages Maven projects. " +
            "action='compile' compiles the project. " +
            "action='test' runs tests. " +
            "action='package' packages the project (jar/war). " +
            "action='install' installs artifact to local repository. " +
            "action='deploy' deploys artifact to remote repository. " +
            "action='clean' cleans target directory. " +
            "action='verify' runs integration tests and checks. " +
            "action='dependency:tree' prints dependency tree. " +
            "action='dependency:analyze' analyzes dependencies. " +
            "action='versions:display-plugin-updates' checks for plugin updates. " +
            "action='versions:display-dependency-updates' checks for dependency updates. " +
            "action='help:describe' describes a plugin goal. " +
            "projectPath scopes to subdirectory. profiles activates Maven profiles."
    )
    suspend fun rider_maven(
        @McpDescription("Action: compile, test, package, install, deploy, clean, verify, dependency:tree, dependency:analyze, versions:display-plugin-updates, versions:display-dependency-updates, help:describe") action: String = "compile",
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Maven profiles to activate (comma-separated)") profiles: String? = null,
        @McpDescription("Skip tests (for package/install/deploy)") skipTests: Boolean = false,
        @McpDescription("Offline mode") offline: Boolean = false,
        @McpDescription("Additional Maven arguments (e.g., '-DskipTests', '-Dmaven.javadoc.skip=true')") extraArgs: String? = null,
        @McpDescription("Specific goal for 'help:describe' (e.g., 'compiler:compile')") goal: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return runMaven(workDir, action, profiles, skipTests, offline, extraArgs, goal)
    }

    private suspend fun runMaven(
        workDir: String, action: String, profiles: String?, skipTests: Boolean,
        offline: Boolean, extraArgs: String?, goal: String?
    ): String {
        val cmd = mutableListOf<String>("mvn")
        if (offline) cmd.add("-o")
        if (skipTests) cmd.add("-DskipTests")
        profiles?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { cmd.add("-P"); cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        
        cmd.add(action)
        goal?.let { cmd.add(it) }

        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 300)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
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