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
import com.github.tropin.ridermcp.SessionManager
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DjangoToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs Django management commands inside a specific Python environment. " +
            "action='run' executes a management command (requires command, e.g., 'migrate', 'runserver', 'shell'). " +
            "action='migrate' runs migrations (optional app_name, --plan, --fake). " +
            "action='makemigrations' creates migrations (optional app_name, --dry-run, --name). " +
            "action='runserver' starts dev server (optional addr:port, --noreload). " +
            "action='shell' opens Django shell (optional --plain). " +
            "action='createsuperuser' creates admin user (interactive or --noinput with env vars). " +
            "action='collectstatic' collects static files (--noinput, --clear). " +
            "action='check' runs system checks (--deploy for production). " +
            "action='dbshell' opens database shell. " +
            "action='test' runs Django tests (optional test labels). " +
            "envType/envName specify the Python environment. " +
            "projectPath must point to Django project root (where manage.py lives). " +
            "settingsModule can override DJANGO_SETTINGS_MODULE."
    )
    suspend fun rider_django(
        @McpDescription("Action: run (default), migrate, makemigrations, runserver, shell, createsuperuser, collectstatic, check, dbshell, test") action: String = "run",
        @McpDescription("Management command for 'run' action (e.g., 'migrate', 'shell', 'loaddata')") command: String? = null,
        @McpDescription("Environment type: venv, conda, poetry, pipenv") envType: String = "venv",
        @McpDescription("Environment name (required)") envName: String? = null,
        @McpDescription("Project-relative path to Django project root (where manage.py lives)") projectPath: String? = null,
        @McpDescription("Additional args for the command (e.g., '--noinput', '--plan', 'myapp')") args: String? = null,
        @McpDescription("Django settings module override (e.g., 'myproject.settings.prod')") settingsModule: String? = null,
        @McpDescription("App name for migrate/makemigrations/test") appName: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        if (envName.isNullOrBlank()) mcpFail("envName is required (use rider_python_env to list/create environments)")

        val (pythonCmd, envWorkDir) = resolvePythonCmd(workDir, envType, envName)

        val managePy = Path.of(envWorkDir, "manage.py")
        if (!managePy.toFile().exists()) mcpFail("manage.py not found in '$envWorkDir'. Ensure projectPath points to Django project root.")

        val baseCmd = pythonCmd + listOf(managePy.toString())
        settingsModule?.let { System.setProperty("DJANGO_SETTINGS_MODULE", it) }

        return when (action.lowercase()) {
            "run" -> runCommand(envWorkDir, baseCmd + listOf(command ?: mcpFail("command is required for action='run'")) + parseArgs(args))
            "migrate" -> {
                val cmd = mutableListOf<String>()
                cmd.addAll(baseCmd)
                cmd.add("migrate")
                cmd.addAll(parseArgs(args))
                appName?.let { cmd.add(0, it) }
                runCommand(envWorkDir, cmd)
            }
            "makemigrations" -> {
                val cmd = mutableListOf<String>()
                cmd.addAll(baseCmd)
                cmd.add("makemigrations")
                cmd.addAll(parseArgs(args))
                appName?.let { cmd.add(0, it) }
                runCommand(envWorkDir, cmd)
            }
            "runserver" -> runServer(envWorkDir, baseCmd, parseArgs(args))
            "shell" -> runCommand(envWorkDir, baseCmd + listOf("shell") + parseArgs(args))
            "createsuperuser" -> runCommand(envWorkDir, baseCmd + listOf("createsuperuser") + parseArgs(args))
            "collectstatic" -> runCommand(envWorkDir, baseCmd + listOf("collectstatic") + parseArgs(args))
            "check" -> runCommand(envWorkDir, baseCmd + listOf("check") + parseArgs(args))
            "dbshell" -> runCommand(envWorkDir, baseCmd + listOf("dbshell") + parseArgs(args))
            "test" -> {
                val cmd = mutableListOf<String>()
                cmd.addAll(baseCmd)
                cmd.add("test")
                cmd.addAll(parseArgs(args))
                appName?.let { cmd.add(0, it) }
                runCommand(envWorkDir, cmd)
            }
            else -> mcpFail("Unknown action '$action'. Use: run, migrate, makemigrations, runserver, shell, createsuperuser, collectstatic, check, dbshell, test")
        }
    }

    private fun resolvePythonCmd(workDir: String, envType: String, envName: String): Pair<List<String>, String> {
        return when (envType.lowercase()) {
            "venv" -> {
                val envPath = Path.of(workDir, envName)
                listOf("${envPath}/bin/python") to envPath.toString()
            }
            "conda" -> listOf("conda", "run", "-n", envName, "python") to workDir
            "poetry" -> listOf("poetry", "run", "python") to workDir
            "pipenv" -> listOf("pipenv", "run", "python") to workDir
            else -> mcpFail("Unknown env type '$envType'. Use: venv, conda, poetry, pipenv")
        }
    }

    private suspend fun runCommand(envWorkDir: String, cmd: List<String>): String {
        val (exitCode, output) = runCmd(envWorkDir, cmd, timeout = 120)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun runServer(envWorkDir: String, baseCmd: List<String>, args: List<String>): String {
        val cmd = baseCmd + listOf("runserver") + args
        val session = SessionManager.create("django_runserver")
        try {
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
                .withWorkDirectory(envWorkDir)
                .withRedirectErrorStream(true)
            session.appendLine("Running: ${commandLine.commandLineString}")
            val handler = com.intellij.execution.process.OSProcessHandler(commandLine)
            session.tag = handler
            handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text.trimEnd('\n', '\r')
                    if (text.isNotEmpty()) session.appendLine(text)
                }
                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                    session.exitCode = event.exitCode
                    session.status = if (event.exitCode == 0) "stopped" else "failed"
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return JsonObject(mapOf("sessionId" to JsonPrimitive(session.id), "message" to JsonPrimitive("Django dev server started. Use rider_get_output to poll output."))).toString()
    }

    private fun parseArgs(args: String?): List<String> {
        val split = args?.split(" ")
        return split?.filter { it.isNotBlank() } ?: emptyList()
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
