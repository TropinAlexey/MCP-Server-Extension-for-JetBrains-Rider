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
    @McpDescription("Lists installed NuGet packages (dependencies) for the solution or a specific project. Pass outdated=true to check for available package updates. Optional project path (relative .csproj).")
    suspend fun rider_list_packages(
        @McpDescription("Relative .csproj path") projectPath: String? = null,
        @McpDescription("Check for updates") outdated: Boolean = false
    ): String {
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

    @McpTool
    @McpDescription("Adds or removes a NuGet package dependency. action: 'add' (install package) or 'remove' (uninstall). Optional project (.csproj path) and version. Use to manage .NET dependencies.")
    suspend fun rider_manage_package(
        @McpDescription("Action: add or remove") action: String,
        @McpDescription("Package name") name: String,
        @McpDescription("Relative .csproj path") projectPath: String? = null,
        @McpDescription("Package version (for add)") version: String? = null
    ): String {
        val project = coroutineContext.project
        if (!name.matches(PACKAGE_NAME_RE)) mcpFail("Invalid package name: $name")
        version?.let { if (!it.matches(VERSION_RE)) mcpFail("Invalid version: $it") }
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }

        val cmdArgs = mutableListOf<String>()
        when (action) {
            "add" -> {
                cmdArgs.add("add")
                projectPath?.let { cmdArgs.add(it) }
                cmdArgs.addAll(listOf("package", name))
                version?.let { cmdArgs.addAll(listOf("--version", it)) }
            }
            "remove" -> {
                cmdArgs.add("remove")
                projectPath?.let { cmdArgs.add(it) }
                cmdArgs.addAll(listOf("package", name))
            }
            else -> mcpFail("Unknown action: $action. Use: add, remove")
        }

        val (exitCode, output) = runDotnetSync(project, cmdArgs, timeout = 60)
        if (exitCode != 0) mcpFail(output.trim())
        return output.trim()
    }

    @McpTool
    @McpDescription("Runs NuGet package restore (dotnet restore) to download missing dependencies. Returns sessionId — poll with rider_get_output until complete. Use after adding packages or when dependencies are missing. If the IDE is busy (indexing, build), check rider_get_ide_state first.")
    suspend fun rider_nuget_restore(): String {
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
