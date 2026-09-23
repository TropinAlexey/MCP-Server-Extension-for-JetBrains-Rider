package com.github.tropin.ridermcp.nuget

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.util.Key
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.SessionManager
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private val PACKAGE_NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
private val VERSION_RE = Regex("^[A-Za-z0-9][A-Za-z0-9.*+-]*$")

private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
    if (path.startsWith("-")) return "Project path must not start with '-'"
    val basePath = project.basePath ?: return "No project path"
    val resolved = Path.of(basePath).resolve(path).normalize()
    if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
    if (!resolved.toFile().exists()) return "Project file not found: $path"
    return null
}

class NuGetToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages NuGet packages via 'dotnet list/add/remove/restore'. action='list' (default) shows installed packages (optional projectPath to scope to one .csproj, outdated=true to check updates). " +
            "action='add' installs (requires name; optional version/projectPath). action='remove' uninstalls (requires name; optional projectPath). " +
            "add/remove mutate project files — confirm with the user first. " +
            "action='restore' runs 'dotnet restore' async and returns sessionId — poll with rider_get_output. " +
            "projectPath must stay inside the project dir. Do NOT use for building (rider_build) or running tests (rider_tests)."
    )
    suspend fun rider_nuget(
        @McpDescription("Action: list (default), add, remove, restore (restore returns sessionId)") action: String = "list",
        @McpDescription("NuGet package name, e.g. 'Newtonsoft.Json' (required for add/remove)") name: String? = null,
        @McpDescription("Project-relative .csproj path to scope the operation (omit = whole solution)") projectPath: String? = null,
        @McpDescription("Package version for add, e.g. '13.0.3' (omit = latest)") version: String? = null,
        @McpDescription("Check for available updates (list only, default false)") outdated: Boolean = false
    ): String {
        return when (action.lowercase()) {
            "list" -> listPackages(projectPath, outdated)
            "add" -> managePackage("add", name, projectPath, version)
            "remove" -> managePackage("remove", name, projectPath, null)
            "restore" -> nugetRestore()
            else -> mcpFail("Unknown action '$action'. Use: list, add, remove, restore")
        }
    }

    private suspend fun listPackages(projectPath: String?, outdated: Boolean): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val cmdArgs = mutableListOf("list")
        projectPath?.let { cmdArgs.add(it) }
        cmdArgs.add("package")
        if (outdated) cmdArgs.add("--outdated")

        val (exitCode, output) = runDotnetSync(project, cmdArgs, timeout = 30)
        if (exitCode != 0) mcpFail("dotnet list package failed:\n$output")
        return output.trim().ifEmpty { "No packages found" }
    }

    private suspend fun managePackage(op: String, name: String?, projectPath: String?, version: String?): String {
        if (name.isNullOrBlank()) mcpFail("name is required for action='$op'")
        val project = coroutineContext.project
        if (!name.matches(PACKAGE_NAME_RE)) mcpFail("Invalid package name: $name")
        version?.let { if (!it.matches(VERSION_RE)) mcpFail("Invalid version: $it") }
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }

        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(op)
        projectPath?.let { cmdArgs.add(it) }
        cmdArgs.addAll(listOf("package", name))
        if (op == "add" && version != null) cmdArgs.addAll(listOf("--version", version))

        val (exitCode, output) = runDotnetSync(project, cmdArgs, timeout = 60)
        if (exitCode != 0) mcpFail(output.trim())
        return output.trim()
    }

    private suspend fun nugetRestore(): String {
        val project = coroutineContext.project
        val session = SessionManager.create("nuget")
        session.appendLine("Running: dotnet restore")
        try {
            val cmd = GeneralCommandLine("dotnet", "restore")
                .withWorkDirectory(project.basePath)
            val handler = OSProcessHandler(cmd)
            session.tag = handler
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text.trimEnd('\n', '\r')
                    if (text.isNotEmpty()) session.appendLine(text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    session.exitCode = event.exitCode
                    session.status = if (event.exitCode == 0) "succeeded" else "failed"
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    private fun runDotnetSync(project: com.intellij.openapi.project.Project, args: List<String>, timeout: Long = 30): Pair<Int, String> {
        val projectPath = project.basePath ?: return -1 to "No project path"
        val pb = ProcessBuilder(listOf("dotnet") + args)
            .directory(java.io.File(projectPath))
            .redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val completed = process.waitFor(timeout, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return -1 to "Command timed out after ${timeout}s"
        }
        return process.exitValue() to output
    }
}
