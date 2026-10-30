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

class PythonEnvToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages Python environments: venv, conda, poetry, pipenv. " +
            "action='create' creates a new environment (type: venv/conda/poetry/pipenv, name, pythonVersion?). " +
            "action='list' shows available environments. " +
            "action='remove' deletes an environment. " +
            "action='sync' installs dependencies from requirements.txt/pyproject.toml/poetry.lock/Pipfile.lock. " +
            "action='info' shows environment details (path, python version, packages). " +
            "Use projectPath to scope to a subdirectory (must be inside project)."
    )
    suspend fun rider_python_env(
        @McpDescription("Action: create (default), list, remove, sync, info") action: String = "list",
        @McpDescription("Environment type: venv, conda, poetry, pipenv") envType: String = "venv",
        @McpDescription("Environment name (required for create/remove/sync/info)") name: String? = null,
        @McpDescription("Python version for create (e.g. '3.11', '3.12')") pythonVersion: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Requirements file for sync (omit = auto-detect)") requirementsFile: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "create" -> createEnv(workDir, envType, name, pythonVersion)
            "list" -> listEnvs(workDir)
            "remove" -> removeEnv(workDir, envType, name)
            "sync" -> syncEnv(workDir, envType, name, requirementsFile)
            "info" -> envInfo(workDir, envType, name)
            else -> mcpFail("Unknown action '$action'. Use: create, list, remove, sync, info")
        }
    }

    private suspend fun createEnv(workDir: String, envType: String, name: String?, pythonVersion: String?): String {
        if (name.isNullOrBlank()) mcpFail("name is required for create")
        val cmd = when (envType.lowercase()) {
            "venv" -> {
                val py = pythonVersion?.let { "python$it" } ?: "python3"
                listOf(py, "-m", "venv", name)
            }
            "conda" -> listOf("conda", "create", "-n", name, "python=${pythonVersion ?: "3.11"}", "-y")
            "poetry" -> listOf("poetry", "env", "use", pythonVersion ?: "3.11")
            "pipenv" -> listOf("pipenv", "--python", pythonVersion ?: "3.11")
            else -> mcpFail("Unknown env type '$envType'. Use: venv, conda, poetry, pipenv")
        }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 120)
        if (exitCode != 0) mcpFail("Failed to create $envType environment:\n$output")
        return JsonObject(mapOf("status" to JsonPrimitive("created"), "name" to JsonPrimitive(name), "type" to JsonPrimitive(envType))).toString()
    }

    private suspend fun listEnvs(workDir: String): String {
        val results = mutableListOf<EnvInfo>()
        // venv: look for .venv, venv, env folders
        Path.of(workDir).toFile().listFiles()?.forEach { dir ->
            if (dir.isDirectory && (dir.name == ".venv" || dir.name == "venv" || dir.name == "env")) {
                val py = Path.of(dir.path, "bin", "python")
                val version = if (py.toFile().exists()) runCmd(dir.path, listOf(py.toString(), "--version"), timeout = 10).second.trim() else "unknown"
                results.add(EnvInfo("venv", dir.name, dir.path, version))
            }
        }
        // conda envs
        runCmd(workDir, listOf("conda", "env", "list", "--json"), timeout = 30).second.trim().let { output ->
            if (output.isNotBlank()) {
                try {
                    val json = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(output)
                    (json["envs"] as? List<*>)?.forEach { env ->
                        val envMap = env as Map<String, Any>
                        results.add(EnvInfo("conda", envMap["name"]?.toString() ?: "", envMap["prefix"]?.toString() ?: "", ""))
                    }
                } catch (_: Exception) {}
            }
        }
        // poetry envs
        runCmd(workDir, listOf("poetry", "env", "list", "--json"), timeout = 30).second.trim().let { output ->
            if (output.isNotBlank()) {
                try {
                    val list = kotlinx.serialization.json.Json.decodeFromString<List<*>>(output)
                    list.forEach { env ->
                        val envMap = env as Map<String, Any>
                        results.add(EnvInfo("poetry", envMap["name"]?.toString() ?: "", envMap["path"]?.toString() ?: "", envMap["version"]?.toString() ?: ""))
                    }
                } catch (_: Exception) {}
            }
        }
        // pipenv
        runCmd(workDir, listOf("pipenv", "--venv"), timeout = 10).second.trim().let { path ->
            if (path.isNotBlank() && Path.of(path).toFile().exists()) {
                results.add(EnvInfo("pipenv", "pipenv", path, ""))
            }
        }
        val envsArray = JsonArray(results.map { r ->
            JsonObject(mapOf(
                "type" to JsonPrimitive(r.type),
                "name" to JsonPrimitive(r.name),
                "path" to JsonPrimitive(r.path),
                "python" to JsonPrimitive(r.python)
            ))
        })
        return JsonObject(mapOf("environments" to envsArray)).toString()
    }

    private suspend fun removeEnv(workDir: String, envType: String, name: String?): String {
        if (name.isNullOrBlank()) mcpFail("name is required for remove")
        val cmd = when (envType.lowercase()) {
            "venv" -> {
                val path = Path.of(workDir, name)
                if (path.toFile().exists()) java.nio.file.Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach { it.toFile().delete() }
                listOf("echo", "removed")
            }
            "conda" -> listOf("conda", "env", "remove", "-n", name, "-y")
            "poetry" -> listOf("poetry", "env", "remove", name)
            "pipenv" -> listOf("pipenv", "--rm")
            else -> mcpFail("Unknown env type '$envType'")
        }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 60)
        if (exitCode != 0) mcpFail("Failed to remove $envType environment:\n$output")
        return JsonObject(mapOf("status" to JsonPrimitive("removed"), "name" to JsonPrimitive(name))).toString()
    }

    private suspend fun syncEnv(workDir: String, envType: String, name: String?, requirementsFile: String?): String {
        if (name.isNullOrBlank()) mcpFail("name is required for sync")
        val pair: Pair<List<String>, String> = when (envType.lowercase()) {
            "venv" -> {
                val envPath = Path.of(workDir, name)
                val pipPath = "${envPath}/bin/pip"
                Pair(listOf(pipPath), envPath.toString())
            }
            "conda" -> Pair(listOf("conda", "run", "-n", name, "pip"), workDir)
            "poetry" -> Pair(listOf("poetry", "run", "pip"), workDir)
            "pipenv" -> Pair(listOf("pipenv", "run", "pip"), workDir)
            else -> mcpFail("Unknown env type '$envType'")
        }
        val (pipCmd, envWorkDir) = pair
        val reqFile = requirementsFile ?: autoDetectReqFile(envWorkDir) ?: mcpFail("No requirements file found (requirements.txt, pyproject.toml, poetry.lock, Pipfile.lock)")
        val cmd = pipCmd + listOf("install", "-r", reqFile)
        val (exitCode, output) = runCmd(envWorkDir, cmd, timeout = 180)
        if (exitCode != 0) mcpFail("Sync failed:\n$output")
        return JsonObject(mapOf("status" to JsonPrimitive("synced"), "requirements" to JsonPrimitive(reqFile))).toString()
    }

    private fun autoDetectReqFile(workDir: String): String? {
        val candidates = listOf("requirements.txt", "requirements.lock", "pyproject.toml", "poetry.lock", "Pipfile.lock")
        return candidates.firstOrNull { Path.of(workDir, it).toFile().exists() }
    }

    private suspend fun envInfo(workDir: String, envType: String, name: String?): String {
        if (name.isNullOrBlank()) mcpFail("name is required for info")
        val (pipCmd, envWorkDir) = when (envType.lowercase()) {
            "venv" -> listOf("${Path.of(workDir, name)}/bin/pip") to Path.of(workDir, name).toString()
            "conda" -> listOf("conda", "run", "-n", name, "pip") to workDir
            "poetry" -> listOf("poetry", "run", "pip") to workDir
            "pipenv" -> listOf("pipenv", "run", "pip") to workDir
            else -> mcpFail("Unknown env type '$envType'")
        }
        val (exitCode, output) = runCmd(envWorkDir, pipCmd + listOf("list", "--format=json"), timeout = 30)
        if (exitCode != 0) mcpFail("Failed to get env info:\n$output")
        val packagesArray = JsonArray(
            try {
                kotlinx.serialization.json.Json.decodeFromString<List<*>>(output).map { pkg ->
                    val pkgMap = pkg as Map<String, Any>
                    JsonObject(mapOf(
                        "name" to JsonPrimitive(pkgMap["name"]?.toString() ?: ""),
                        "version" to JsonPrimitive(pkgMap["version"]?.toString() ?: "")
                    ))
                }
            } catch (_: Exception) {
                emptyList()
            }
        )
        return JsonObject(mapOf(
            "name" to JsonPrimitive(name),
            "type" to JsonPrimitive(envType),
            "path" to JsonPrimitive(envWorkDir),
            "packages" to packagesArray
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

    private data class EnvInfo(val type: String, val name: String, val path: String, val python: String)
}