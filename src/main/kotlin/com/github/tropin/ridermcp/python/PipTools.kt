package com.github.tropin.ridermcp.python

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

class PipToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages Python packages via pip inside a specific environment. " +
            "action='list' shows installed packages (optional outdated=true to check updates). " +
            "action='add' installs package(s) (requires name; optional version, index-url, extra-index-url). " +
            "action='remove' uninstalls package(s) (requires name). " +
            "action='update' upgrades package(s) (optional name for specific). " +
            "action='check' runs pip check (verify dependencies). " +
            "action='audit' runs pip-audit if available (vulnerability scan). " +
            "action='freeze' outputs requirements.txt format. " +
            "envType and envName specify the target environment (venv/conda/poetry/pipenv). " +
            "projectPath scopes to subdirectory. Use rider_python_env to manage environments."
    )
    suspend fun rider_pip(
        @McpDescription("Action: list (default), add, remove, update, check, audit, freeze") action: String = "list",
        @McpDescription("Package name(s), comma-separated (required for add/remove/update)") name: String? = null,
        @McpDescription("Package version for add (e.g. '1.0.0', '>=1.0,<2.0')") version: String? = null,
        @McpDescription("Environment type: venv, conda, poetry, pipenv") envType: String = "venv",
        @McpDescription("Environment name (required)") envName: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Check for outdated packages (list only)") outdated: Boolean = false,
        @McpDescription("Custom index URL (add only)") indexUrl: String? = null,
        @McpDescription("Extra index URL (add only)") extraIndexUrl: String? = null,
        @McpDescription("Install in editable mode (add only)") editable: Boolean = false
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        if (envName.isNullOrBlank()) mcpFail("envName is required (use rider_python_env to list/create environments)")

        val (pipCmd, envWorkDir) = resolvePipCmd(workDir, envType, envName)

        return when (action.lowercase()) {
            "list" -> listPackages(envWorkDir, pipCmd, outdated)
            "add" -> addPackage(envWorkDir, pipCmd, name, version, indexUrl, extraIndexUrl, editable)
            "remove" -> removePackage(envWorkDir, pipCmd, name)
            "update" -> updatePackage(envWorkDir, pipCmd, name)
            "check" -> checkDeps(envWorkDir, pipCmd)
            "audit" -> auditDeps(envWorkDir, pipCmd)
            "freeze" -> freezeDeps(envWorkDir, pipCmd)
            else -> mcpFail("Unknown action '$action'. Use: list, add, remove, update, check, audit, freeze")
        }
    }

    private fun resolvePipCmd(workDir: String, envType: String, envName: String): Pair<List<String>, String> {
        return when (envType.lowercase()) {
            "venv" -> {
                val envPath = Path.of(workDir, envName)
                listOf("${envPath}/bin/pip") to envPath.toString()
            }
            "conda" -> listOf("conda", "run", "-n", envName, "pip") to workDir
            "poetry" -> listOf("poetry", "run", "pip") to workDir
            "pipenv" -> listOf("pipenv", "run", "pip") to workDir
            else -> mcpFail("Unknown env type '$envType'. Use: venv, conda, poetry, pipenv")
        }
    }

    private suspend fun listPackages(envWorkDir: String, pipCmd: List<String>, outdated: Boolean): String {
        val cmd = mutableListOf<String>()
        cmd.addAll(pipCmd)
        cmd.addAll(listOf("list", "--format=json"))
        if (outdated) cmd.add("--outdated")
        val (exitCode, output) = runCmd(envWorkDir, cmd, timeout = 30)
        if (exitCode != 0) mcpFail("pip list failed:\n$output")
        val packagesArray = JsonArray(
            try {
                kotlinx.serialization.json.Json.decodeFromString<List<*>>(output).map { pkg ->
                    val pkgMap = pkg as Map<String, Any>
                    JsonObject(mapOf(
                        "name" to JsonPrimitive(pkgMap["name"]?.toString() ?: ""),
                        "version" to JsonPrimitive(pkgMap["version"]?.toString() ?: ""),
                        "latest_version" to JsonPrimitive(pkgMap["latest_version"]?.toString() ?: ""),
                        "outdated" to JsonPrimitive(pkgMap["latest_version"] != null && pkgMap["version"] != pkgMap["latest_version"])
                    ))
                }
            } catch (_: Exception) {
                emptyList()
            }
        )
        return JsonObject(mapOf("packages" to packagesArray)).toString()
    }

    private suspend fun addPackage(
        envWorkDir: String, pipCmd: List<String>, name: String?, version: String?,
        indexUrl: String?, extraIndexUrl: String?, editable: Boolean
    ): String {
        if (name.isNullOrBlank()) mcpFail("name is required for add")
        val packages = name.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val cmd = mutableListOf<String>()
        cmd.addAll(pipCmd)
        cmd.add("install")
        if (editable) cmd.add("-e")
        indexUrl?.let { cmd.addAll(listOf("--index-url", it)) }
        extraIndexUrl?.let { cmd.addAll(listOf("--extra-index-url", it)) }
        packages.forEach { pkg ->
            val spec = if (version != null) "$pkg==$version" else pkg
            cmd.add(spec)
        }
        val (exitCode, output) = runCmd(envWorkDir, cmd, timeout = 120)
        if (exitCode != 0) mcpFail("pip install failed:\n$output")
        return JsonObject(mapOf("status" to JsonPrimitive("installed"), "packages" to JsonPrimitive(packages.joinToString(", ")))).toString()
    }

    private suspend fun removePackage(envWorkDir: String, pipCmd: List<String>, name: String?): String {
        if (name.isNullOrBlank()) mcpFail("name is required for remove")
        val packages = name.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val cmd = mutableListOf<String>()
        cmd.addAll(pipCmd)
        cmd.addAll(listOf("uninstall", "-y"))
        packages.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(envWorkDir, cmd, timeout = 60)
        if (exitCode != 0) mcpFail("pip uninstall failed:\n$output")
        return JsonObject(mapOf("status" to JsonPrimitive("removed"), "packages" to JsonPrimitive(packages.joinToString(", ")))).toString()
    }

    private suspend fun updatePackage(envWorkDir: String, pipCmd: List<String>, name: String?): String {
        val cmd = mutableListOf<String>()
        cmd.addAll(pipCmd)
        cmd.addAll(listOf("install", "--upgrade"))
        if (name != null) {
            name.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { cmd.add(it) }
        }
        val (exitCode, output) = runCmd(envWorkDir, cmd, timeout = 120)
        if (exitCode != 0) mcpFail("pip update failed:\n$output")
        return JsonObject(mapOf("status" to JsonPrimitive("updated"))).toString()
    }

    private suspend fun checkDeps(envWorkDir: String, pipCmd: List<String>): String {
        val (exitCode, output) = runCmd(envWorkDir, pipCmd + listOf("check"), timeout = 30)
        if (exitCode != 0) return JsonObject(mapOf("status" to JsonPrimitive("issues_found"), "details" to JsonPrimitive(output.trim()))).toString()
        return JsonObject(mapOf("status" to JsonPrimitive("ok"))).toString()
    }

    private suspend fun auditDeps(envWorkDir: String, pipCmd: List<String>): String {
        val (exitCode, output) = runCmd(envWorkDir, pipCmd + listOf("install", "pip-audit"), timeout = 60)
        if (exitCode != 0) mcpFail("Failed to install pip-audit:\n$output")
        val (auditExit, auditOut) = runCmd(envWorkDir, pipCmd + listOf("audit", "--format=json"), timeout = 60)
        val vulnsArray = JsonArray(
            try {
                kotlinx.serialization.json.Json.decodeFromString<List<*>>(auditOut).map { vuln ->
                    val v = vuln as Map<String, Any>
                    JsonObject(mapOf(
                        "package" to JsonPrimitive(v["name"]?.toString() ?: ""),
                        "version" to JsonPrimitive(v["version"]?.toString() ?: ""),
                        "vuln_id" to JsonPrimitive(v["id"]?.toString() ?: ""),
                        "description" to JsonPrimitive(v["description"]?.toString() ?: ""),
                        "fix_version" to JsonPrimitive(v["fix_versions"]?.toString() ?: "")
                    ))
                }
            } catch (_: Exception) {
                emptyList()
            }
        )
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (auditExit == 0) "clean" else "vulnerabilities_found"),
            "vulnerabilities" to vulnsArray
        )).toString()
    }

    private suspend fun freezeDeps(envWorkDir: String, pipCmd: List<String>): String {
        val (exitCode, output) = runCmd(envWorkDir, pipCmd + listOf("freeze"), timeout = 30)
        if (exitCode != 0) mcpFail("pip freeze failed:\n$output")
        return JsonObject(mapOf("requirements" to JsonPrimitive(output.trim()))).toString()
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startsWith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 30): Pair<Int, String> {
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
