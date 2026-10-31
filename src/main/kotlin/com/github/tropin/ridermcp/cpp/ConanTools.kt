package com.github.tropin.ridermcp.cpp

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

class ConanToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages C++ packages via Conan 2.x. " +
            "action='install' installs dependencies from conanfile.txt/py. " +
            "action='create' creates a package from recipe. " +
            "action='export' exports recipe to local cache. " +
            "action='search' searches packages in remotes. " +
            "action='list' lists packages in local cache. " +
            "action='remove' removes packages from cache. " +
            "action='remote' manages remotes (list/add/remove). " +
            "action='profile' manages profiles (list/detect/show). " +
            "action='build' builds package from source. " +
            "projectPath scopes to subdirectory (where conanfile.txt/py lives)."
    )
    suspend fun rider_conan(
        @McpDescription("Action: install (default), create, export, search, list, remove, remote, profile, build") action: String = "install",
        @McpDescription("Project-relative path to conanfile.txt/py (omit = project root)") projectPath: String? = null,
        @McpDescription("Build folder relative to projectPath (default: build)") buildFolder: String = "build",
        @McpDescription("Profile name (default: default)") profile: String = "default",
        @McpDescription("Package reference for create/remove (e.g., 'mypkg/1.0@user/channel')") reference: String? = null,
        @McpDescription("Remote name for search/remote actions") remote: String? = null,
        @McpDescription("Package pattern for search/list (e.g., 'zlib/*')") pattern: String? = null,
        @McpDescription("Additional Conan arguments (e.g., '--build=missing', '-s build_type=Release')") extraArgs: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!
        val fullBuildDir = Path.of(workDir, buildFolder).toString()

        return when (action.lowercase()) {
            "install" -> install(workDir, buildFolder, profile, extraArgs)
            "create" -> create(workDir, reference, profile, extraArgs)
            "export" -> export(workDir, reference, extraArgs)
            "search" -> search(workDir, pattern, remote, extraArgs)
            "list" -> list(workDir, pattern, extraArgs)
            "remove" -> remove(workDir, reference, extraArgs)
            "remote" -> remote(workDir, extraArgs)
            "profile" -> profile(workDir, extraArgs)
            "build" -> build(workDir, reference, profile, extraArgs)
            else -> mcpFail("Unknown action '$action'. Use: install, create, export, search, list, remove, remote, profile, build")
        }
    }

    private suspend fun install(workDir: String, buildFolder: String, profile: String, extraArgs: String?): String {
        val conanfile = Path.of(workDir, "conanfile.txt").toFile()
        if (!conanfile.exists()) {
            val py = Path.of(workDir, "conanfile.py").toFile()
            if (!py.exists()) mcpFail("No conanfile.txt or conanfile.py found in $workDir")
        }
        val cmd = mutableListOf<String>("conan", "install", workDir, "--build=missing", "--profile=$profile", "--output-folder=build")
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 300)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "installed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun create(workDir: String, reference: String?, profile: String, extraArgs: String?): String {
        if (reference.isNullOrBlank()) mcpFail("reference is required for create (e.g., 'mypkg/1.0@user/channel')")
        val cmd = mutableListOf<String>("conan", "create", workDir, reference, "--profile=$profile", "--build=missing")
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 600)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "created" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun export(workDir: String, reference: String?, extraArgs: String?): String {
        if (reference.isNullOrBlank()) mcpFail("reference is required for export")
        val cmd = mutableListOf<String>("conan", "export", workDir, reference)
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 120)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "exported" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun search(workDir: String, pattern: String?, remote: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("conan", "search")
        pattern?.let { cmd.addAll(listOf("-p", it)) }
        remote?.let { cmd.addAll(listOf("-r", it)) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun list(workDir: String, pattern: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("conan", "list")
        pattern?.let { cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun remove(workDir: String, reference: String?, extraArgs: String?): String {
        if (reference.isNullOrBlank()) mcpFail("reference is required for remove")
        val cmd = mutableListOf<String>("conan", "remove", reference, "-c")
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "removed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun remote(workDir: String, extraArgs: String?): String {
        val cmd = mutableListOf<String>("conan", "remote", "list")
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun profile(workDir: String, extraArgs: String?): String {
        val cmd = mutableListOf<String>("conan", "profile", "list")
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun build(workDir: String, reference: String?, profile: String, extraArgs: String?): String {
        if (reference.isNullOrBlank()) mcpFail("reference is required for build")
        val cmd = mutableListOf<String>("conan", "build", workDir, "--profile=$profile")
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 600)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "built" else "failed"),
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

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 120): Pair<Int, String> {
        val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
            .withWorkDirectory(coroutineContext.project.basePath!!)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(120).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after 120s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}