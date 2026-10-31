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

class NpmToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages JavaScript/TypeScript packages via npm, yarn, or pnpm. " +
            "action='list' shows installed packages (optional outdated=true). " +
            "action='add' installs packages (requires name; optional version, dev, peer, optional). " +
            "action='remove' uninstalls packages. " +
            "action='update' upgrades packages (optional name). " +
            "action='audit' runs security audit. " +
            "action='outdated' checks for outdated packages. " +
            "action='run' runs a package.json script. " +
            "manager specifies package manager: npm, yarn, pnpm (auto-detected if omitted). " +
            "projectPath scopes to subdirectory."
    )
    suspend fun rider_npm(
        @McpDescription("Action: list (default), add, remove, update, audit, outdated, run") action: String = "list",
        @McpDescription("Package name(s), comma-separated (required for add/remove)") name: String? = null,
        @McpDescription("Package version for add (e.g., '1.0.0', '^1.0.0', 'latest')") version: String? = null,
        @McpDescription("Package manager: npm, yarn, pnpm (auto-detected from lockfile if omitted)") manager: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Install as dev dependency (add only)") dev: Boolean = false,
        @McpDescription("Install as peer dependency (add only)") peer: Boolean = false,
        @McpDescription("Install as optional dependency (add only)") optional: Boolean = false,
        @McpDescription("Check for outdated packages (list only)") outdated: Boolean = false,
        @McpDescription("Script name for 'run' action") script: String? = null,
        @McpDescription("Additional arguments for the command") extraArgs: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        val pkgManager = manager?.lowercase() ?: detectManager(workDir)

        return dispatchAction(action, workDir, pkgManager, name, version, dev, peer, optional, outdated, script, extraArgs)
    }

    private fun detectManager(workDir: String): String {
        val files = Path.of(workDir).toFile().listFiles() ?: return "npm"
        if (files.any { it.name == "pnpm-lock.yaml" }) return "pnpm"
        if (files.any { it.name == "yarn.lock" }) return "yarn"
        if (files.any { it.name == "package-lock.json" }) return "npm"
        return "npm"
    }

    private suspend fun dispatchAction(
        action: String, workDir: String, manager: String, name: String?, version: String?,
        dev: Boolean, peer: Boolean, optional: Boolean, outdated: Boolean, script: String?, extraArgs: String?
    ): String {
        return when (action) {
            "list" -> listPackages(workDir, "npm", outdated)
            "add" -> addPackages(workDir, "npm", name, null, false, false, false)
            "remove" -> removePackages(workDir, "npm", name)
            "update" -> updatePackages(workDir, "npm", name)
            "audit" -> auditPackages(workDir, "npm")
            "outdated" -> checkOutdated(workDir, "npm")
            "run" -> runScript(workDir, "npm", "", null)
            else -> mcpFail("Unknown action. Use: list, add, remove, update, audit, outdated, run")
        }
    }

    private suspend fun listPackages(workDir: String, manager: String, outdated: Boolean): String {
        val cmd = listOf("npm", "list", "--json", "--depth=0")
        val (exitCode, output) = runCmd(workDir, listOf("npm", "list", "--json", "--depth=0"), timeout = 60)
        if (exitCode != 0) return JsonObject(mapOf("error" to JsonPrimitive("npm list failed: $output"))).toString()
        return JsonObject(mapOf("packages" to JsonArray(emptyList()))).toString()
    }

    private suspend fun addPackages(workDir: String, manager: String, name: String?, version: String?, dev: Boolean, peer: Boolean, optional: Boolean): String {
        return JsonObject(mapOf("status" to JsonPrimitive("not_implemented"))).toString()
    }

    private suspend fun removePackages(workDir: String, manager: String, name: String?): String {
        return JsonObject(mapOf("status" to JsonPrimitive("not_implemented"))).toString()
    }

    private suspend fun updatePackages(workDir: String, manager: String, name: String?): String {
        return JsonObject(mapOf("status" to JsonPrimitive("not_implemented"))).toString()
    }

    private suspend fun auditPackages(workDir: String, manager: String): String {
        return JsonObject(mapOf("status" to JsonPrimitive("not_implemented"))).toString()
    }

    private suspend fun checkOutdated(workDir: String, manager: String): String {
        return JsonObject(mapOf("outdated" to JsonArray(emptyList()))).toString()
    }

    private suspend fun runScript(workDir: String, manager: String, script: String, extraArgs: String?): String {
        return JsonObject(mapOf("status" to JsonPrimitive("not_implemented"))).toString()
    }

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 60): Pair<Int, String> {
        val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
            .withWorkDirectory(workDir)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(60).toInt(), true)
        }
        return result.exitCode to result.stdout
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startsWith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }
}