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

class GoplsToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Queries gopls (Go language server) for code intelligence. " +
            "action='diagnostics' returns diagnostics for a file or workspace. " +
            "action='hover' shows hover info at position. " +
            "action='definition' finds definition of symbol at position. " +
            "action='references' finds all references to symbol at position. " +
            "action='completion' shows completions at position. " +
            "action='symbols' lists workspace/document symbols. " +
            "action='format' formats a file. " +
            "action='imports' organizes imports. " +
            "Requires gopls installed and in PATH. projectPath scopes to workspace."
    )
    suspend fun rider_gopls(
        @McpDescription("Action: diagnostics (default), hover, definition, references, completion, symbols, format, imports") action: String = "diagnostics",
        @McpDescription("File path (project-relative) for file-specific actions") file: String? = null,
        @McpDescription("Line (1-indexed) for position-specific actions") line: Int = 1,
        @McpDescription("Column (1-indexed) for position-specific actions") column: Int = 1,
        @McpDescription("Project-relative path to workspace root (omit = project root)") projectPath: String? = null,
        @McpDescription("Symbol query for workspace symbols") query: String? = null,
        @McpDescription("Include tests in diagnostics") includeTests: Boolean = true
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "diagnostics" -> runGopls(workDir, listOf("diagnostics", workDir))
            "hover" -> {
                if (file.isNullOrBlank()) mcpFail("file is required for hover")
                runGopls(workDir, listOf("hover", file, line.toString(), column.toString()))
            }
            "definition" -> {
                if (file.isNullOrBlank()) mcpFail("file is required for definition")
                runGopls(workDir, listOf("definition", file, line.toString(), column.toString()))
            }
            "references" -> {
                if (file.isNullOrBlank()) mcpFail("file is required for references")
                runGopls(workDir, listOf("references", file, line.toString(), column.toString()))
            }
            "completion" -> {
                if (file.isNullOrBlank()) mcpFail("file is required for completion")
                runGopls(workDir, listOf("completion", file, line.toString(), column.toString()))
            }
            "symbols" -> {
                val args = mutableListOf("symbols")
                query?.let { args.add("-q"); args.add(it) }
                runGopls(workDir, args)
            }
            "format" -> {
                if (file.isNullOrBlank()) mcpFail("file is required for format")
                runGopls(workDir, listOf("format", file))
            }
            "imports" -> {
                if (file.isNullOrBlank()) mcpFail("file is required for imports")
                runGopls(workDir, listOf("imports", file))
            }
            else -> mcpFail("Unknown action '$action'. Use: diagnostics, hover, definition, references, completion, symbols, format, imports")
        }
    }

    private suspend fun runGopls(workDir: String, args: List<String>): String {
        val cmd = listOf("gopls") + args
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 60)
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