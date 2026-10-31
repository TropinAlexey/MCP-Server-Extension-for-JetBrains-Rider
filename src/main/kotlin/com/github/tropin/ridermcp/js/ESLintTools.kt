package com.github.tropin.ridermcp.js

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

class ESLintToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs ESLint for JavaScript/TypeScript linting and formatting. " +
            "action='lint' lints files (optional pattern, returns issues). " +
            "action='fix' auto-fixes fixable issues. " +
            "action='check' checks if files pass linting (exit code 0/1). " +
            "action='config' shows resolved config for a file. " +
            "pattern specifies files to lint (e.g., 'src/**/*.ts', 'src/**/*.{js,ts}'). " +
            "configFile overrides config file path. " +
            "projectPath scopes to subdirectory."
    )
    suspend fun rider_eslint(
        @McpDescription("Action: lint (default), fix, check, config") action: String = "lint",
        @McpDescription("File pattern to lint (e.g., 'src/**/*.ts', 'src/**/*.{js,ts,jsx,tsx}')") pattern: String = "**/*.{js,ts,jsx,tsx}",
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Custom ESLint config file path") configFile: String? = null,
        @McpDescription("Output format: json, stylish, compact, unix, checkstyle (lint/check only)") format: String = "json",
        @McpDescription("Maximum number of warnings to report (0 = unlimited)") maxWarnings: Int = 0,
        @McpDescription("Ignore pattern (e.g., 'node_modules/,dist/')") ignorePattern: String? = null,
        @McpDescription("Cache results for faster subsequent runs") cache: Boolean = true,
        @McpDescription("Quiet mode (report errors only)") quiet: Boolean = false
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "lint" -> runLint(coroutineContext.project.basePath!!, pattern, format, maxWarnings, ignorePattern, cache, quiet)
            "fix" -> runFix(coroutineContext.project.basePath!!, pattern)
            "check" -> runCheck(coroutineContext.project.basePath!!, pattern)
            "config" -> showConfig(coroutineContext.project.basePath!!)
            else -> mcpFail("Unknown action '$action'. Use: lint, fix, check, config")
        }
    }

    private suspend fun runLint(
        workDir: String, pattern: String, format: String, maxWarnings: Int,
        ignorePattern: String?, cache: Boolean, quiet: Boolean
    ): String {
        val cmd = mutableListOf<String>("npx", "eslint", pattern)
        cmd.add("--format")
        cmd.add(format)
        if (maxWarnings > 0) cmd.addAll(listOf("--max-warnings", maxWarnings.toString()))
        ignorePattern?.let { cmd.addAll(listOf("--ignore-pattern", it)) }
        if (cache) cmd.add("--cache") else cmd.add("--no-cache")
        if (quiet) cmd.add("--quiet")
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 120)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "clean" else "issues_found"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun runFix(workDir: String, pattern: String): String {
        val cmd = listOf("npx", "eslint", pattern, "--fix")
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 120)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "fixed" else "issues_remain"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun runCheck(workDir: String, pattern: String): String {
        val cmd = listOf("npx", "eslint", pattern)
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "pass" else "fail"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun showConfig(workDir: String): String {
        val cmd = listOf("npx", "eslint", "--print-config", ".")
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        if (exitCode != 0) mcpFail("eslint --print-config failed:\n$output")
        return output.trim()
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