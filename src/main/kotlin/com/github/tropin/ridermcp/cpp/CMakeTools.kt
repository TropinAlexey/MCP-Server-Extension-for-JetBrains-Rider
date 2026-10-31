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

class CMakeToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages CMake projects. " +
            "action='configure' configures the project (generates build files). " +
            "action='build' builds the project. " +
            "action='test' runs tests via CTest. " +
            "action='install' installs the project. " +
            "action='clean' cleans the build directory. " +
            "action='targets' lists available targets. " +
            "action='cache' shows CMake cache variables. " +
            "generator specifies generator (e.g., 'Ninja', 'Unix Makefiles', 'Visual Studio 17 2022'). " +
            "buildType specifies build type (Debug, Release, RelWithDebInfo, MinSizeRel). " +
            "projectPath scopes to subdirectory (where CMakeLists.txt lives)."
    )
    suspend fun rider_cmake(
        @McpDescription("Action: configure (default), build, test, install, clean, targets, cache") action: String = "configure",
        @McpDescription("Project-relative path to CMakeLists.txt directory (omit = project root)") projectPath: String? = null,
        @McpDescription("Build directory relative to projectPath (default: build)") buildDir: String = "build",
        @McpDescription("CMake generator (e.g., 'Ninja', 'Unix Makefiles', 'Visual Studio 17 2022')") generator: String? = null,
        @McpDescription("Build type: Debug, Release, RelWithDebInfo, MinSizeRel") buildType: String = "Debug",
        @McpDescription("Additional CMake arguments (e.g., '-DCMAKE_CXX_FLAGS=-Wall')") extraArgs: String? = null,
        @McpDescription("Additional build arguments (e.g., '--target mytarget', '-- -j4')") buildArgs: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!
        val fullBuildDir = Path.of(workDir, buildDir).toString()

        return when (action.lowercase()) {
            "configure" -> configure(workDir, fullBuildDir, generator, buildType, extraArgs)
            "build" -> build(fullBuildDir, buildArgs)
            "test" -> test(fullBuildDir, extraArgs)
            "install" -> install(fullBuildDir, extraArgs)
            "clean" -> clean(fullBuildDir)
            "targets" -> targets(fullBuildDir)
            "cache" -> cache(fullBuildDir)
            else -> mcpFail("Unknown action '$action'. Use: configure, build, test, install, clean, targets, cache")
        }
    }

    private suspend fun configure(workDir: String, buildDir: String, generator: String?, buildType: String, extraArgs: String?): String {
        val cmd = mutableListOf<String>("cmake")
        generator?.let { cmd.addAll(listOf("-G", it)) }
        cmd.addAll(listOf("-DCMAKE_BUILD_TYPE=$buildType", "-B", buildDir, "-S", workDir))
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 120)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "configured" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun build(buildDir: String, buildArgs: String?): String {
        val cmd = mutableListOf<String>("cmake", "--build", buildDir)
        buildArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 300)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "built" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun test(buildDir: String, extraArgs: String?): String {
        val cmd = mutableListOf<String>("ctest", "--test-dir", buildDir, "--output-on-failure")
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 300)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun install(buildDir: String, extraArgs: String?): String {
        val cmd = mutableListOf<String>("cmake", "--install", buildDir)
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 120)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "installed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun clean(buildDir: String): String {
        val cmd = listOf("cmake", "--build", buildDir, "--target", "clean")
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "cleaned" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun targets(buildDir: String): String {
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, listOf("cmake", "--build", buildDir, "--target", "help"), timeout = 30)
        val targets = output.trim().split("\n").filter { it.startsWith("...") }.map { it.substring(3).trim() }
        val targetsArray = JsonArray(targets.map { JsonPrimitive(it) })
        return JsonObject(mapOf("targets" to targetsArray)).toString()
    }

    private suspend fun cache(buildDir: String): String {
        val cacheFile = Path.of(buildDir, "CMakeCache.txt").toFile()
        if (!cacheFile.exists()) return JsonObject(mapOf("error" to JsonPrimitive("CMakeCache.txt not found"))).toString()
        val content = cacheFile.readText()
        val vars = content.trim().split("\n").filter { it.contains("=") && !it.startsWith("#") }.map { line ->
            val parts = line.split("=", limit = 2)
            JsonObject(mapOf("name" to JsonPrimitive(parts[0].trim()), "value" to JsonPrimitive(parts[1].trim())))
        }
        val varsArray = JsonArray(vars)
        return JsonObject(mapOf("cache" to varsArray)).toString()
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
            .withWorkDirectory(workDir)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}