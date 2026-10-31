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
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GoModToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages Go modules via 'go mod' commands. " +
            "action='tidy' adds missing and removes unused modules. " +
            "action='download' downloads modules to local cache. " +
            "action='verify' verifies dependencies have expected content. " +
            "action='graph' prints module requirement graph. " +
            "action='why' explains why a module is needed. " +
            "action='vendor' creates vendored copy of dependencies. " +
            "action='init' initializes new module (optional module path). " +
            "projectPath scopes to subdirectory (must be inside project)."
    )
    suspend fun rider_go_mod(
        @McpDescription("Action: tidy (default), download, verify, graph, why, vendor, init") action: String = "tidy",
        @McpDescription("Module path for 'init' (e.g., 'github.com/user/repo')") modulePath: String? = null,
        @McpDescription("Module or package for 'why' (e.g., 'github.com/user/pkg')") target: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "tidy" -> runGoMod(workDir, listOf("tidy"))
            "download" -> runGoMod(workDir, listOf("download"))
            "verify" -> runGoMod(workDir, listOf("verify"))
            "graph" -> runGoMod(workDir, listOf("graph"))
            "why" -> {
                if (target.isNullOrBlank()) mcpFail("target is required for action='why'")
                runGoMod(workDir, listOf("why", target))
            }
            "vendor" -> runGoMod(workDir, listOf("vendor"))
            "init" -> {
                val args = mutableListOf("init")
                modulePath?.let { args.add(it) }
                runGoMod(workDir, args)
            }
            else -> mcpFail("Unknown action '$action'. Use: tidy, download, verify, graph, why, vendor, init")
        }
    }

    private suspend fun runGoMod(workDir: String, args: List<String>): String {
        val cmd = listOf("go", "mod") + args
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 120)
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