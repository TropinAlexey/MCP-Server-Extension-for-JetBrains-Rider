package com.github.tropin.ridermcp.nuget

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.mcpJson
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val PACKAGE_NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
private val VERSION_RE = Regex("^[A-Za-z0-9][A-Za-z0-9.*+-]*$")

private fun validateProjectPath(project: Project, path: String): String? {
    if (path.startsWith("-")) return "Project path must not start with '-'"
    val basePath = project.basePath ?: return "No project path"
    val resolved = Path.of(basePath).resolve(path).normalize()
    if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
    if (!resolved.toFile().exists()) return "Project file not found: $path"
    return null
}

// === List Packages ===

@Serializable
data class ListPackagesArgs(val project: String? = null, val outdated: Boolean = false)

class ListPackagesTool : AbstractMcpTool<ListPackagesArgs>(ListPackagesArgs.serializer()) {
    override val name = "rider_list_packages"
    override val description = "Lists installed NuGet packages (dependencies) for the solution or a specific project. Pass outdated=true to check for available package updates. Optional project path (relative .csproj)."

    override fun handle(project: Project, args: ListPackagesArgs): Response {
        args.project?.let { validateProjectPath(project, it)?.let { err -> return Response(error = err) } }
        val cmdArgs = mutableListOf("list")
        args.project?.let { cmdArgs.add(it) }
        cmdArgs.add("package")
        if (args.outdated) cmdArgs.add("--outdated")

        val (exitCode, output) = runDotnetSync(project, cmdArgs, timeout = 30)
        if (exitCode != 0) return Response(error = "dotnet list package failed:\n$output")
        return Response(output.trim().ifEmpty { "No packages found" })
    }
}

// === Add / Remove Package ===

@Serializable
data class ManagePackageArgs(val action: String, val name: String, val project: String? = null, val version: String? = null)

class ManagePackageTool : AbstractMcpTool<ManagePackageArgs>(ManagePackageArgs.serializer()) {
    override val name = "rider_manage_package"
    override val description = "Adds or removes a NuGet package dependency. action: 'add' (install package) or 'remove' (uninstall). Optional project (.csproj path) and version. Use to manage .NET dependencies."

    override fun handle(project: Project, args: ManagePackageArgs): Response {
        if (!args.name.matches(PACKAGE_NAME_RE)) return Response(error = "Invalid package name: ${args.name}")
        args.version?.let { if (!it.matches(VERSION_RE)) return Response(error = "Invalid version: $it") }
        args.project?.let { validateProjectPath(project, it)?.let { err -> return Response(error = err) } }

        val cmdArgs = mutableListOf<String>()
        when (args.action) {
            "add" -> {
                cmdArgs.add("add")
                args.project?.let { cmdArgs.add(it) }
                cmdArgs.addAll(listOf("package", args.name))
                args.version?.let { cmdArgs.addAll(listOf("--version", it)) }
            }
            "remove" -> {
                cmdArgs.add("remove")
                args.project?.let { cmdArgs.add(it) }
                cmdArgs.addAll(listOf("package", args.name))
            }
            else -> return Response(error = "Unknown action: ${args.action}. Use: add, remove")
        }

        val (exitCode, output) = runDotnetSync(project, cmdArgs, timeout = 60)
        if (exitCode != 0) return Response(error = output.trim())
        return Response(output.trim())
    }
}

// === NuGet Restore ===

class NuGetRestoreTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "rider_nuget_restore"
    override val description = "Runs NuGet package restore (dotnet restore) to download missing dependencies. Returns sessionId — poll with rider_get_output until complete. Use after adding packages or when dependencies are missing."

    override fun handle(project: Project, args: NoArgs): Response {
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
        return Response(mcpJson.encodeToString(mapOf("sessionId" to session.id)))
    }
}

// === Helpers ===

private fun runDotnetSync(project: Project, args: List<String>, timeout: Long = 30): Pair<Int, String> {
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
