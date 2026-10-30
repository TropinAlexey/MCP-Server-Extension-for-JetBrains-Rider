package com.github.tropin.ridermcp.cargo

import com.intellij.execution.process.CapturingProcessHandler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private val CRATE_NAME_RE = Regex("^[a-z][a-z0-9_-]*$")
private val VERSION_RE = Regex("^[0-9]+(\\.[0-9]+)*(-[a-zA-Z0-9.-]+)?(\\+[a-zA-Z0-9.-]+)?$")

private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
    if (path.startsWith("-")) return "Project path must not start with '-'"
    val basePath = project.basePath ?: return "No project path"
    val resolved = Path.of(basePath).resolve(path).normalize()
    if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
    if (!resolved.toFile().exists()) return "Project file not found: $path"
    return null
}

class CargoToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages Cargo packages via 'cargo add/remove/update/list/tree'. " +
            "action='list' (default) shows installed dependencies (optional projectPath to scope to one Cargo.toml, tree=true for dependency tree). " +
            "action='add' installs a crate (requires name; optional version/projectPath/features). " +
            "action='remove' uninstalls a crate (requires name; optional projectPath). " +
            "action='update' runs 'cargo update' (optional package to update specific crate). " +
            "action='tree' shows dependency tree. " +
            "add/remove/update mutate Cargo.toml/Cargo.lock — confirm with the user first. " +
            "projectPath must stay inside the project dir. Do NOT use for building (built-in build_solution_start) or running tests (rider_tests)."
    )
    suspend fun rider_cargo(
        @McpDescription("Action: list (default), add, remove, update, tree") action: String = "list",
        @McpDescription("Crate name, e.g. 'serde' or 'tokio' (required for add/remove)") name: String? = null,
        @McpDescription("Project-relative Cargo.toml path to scope the operation (omit = workspace root)") projectPath: String? = null,
        @McpDescription("Crate version for add, e.g. '1.0.0' (omit = latest)") version: String? = null,
        @McpDescription("Features to enable for add, comma-separated (e.g. 'full,derive')") features: String? = null,
        @McpDescription("Package to update (update only, omit = all)") `package`: String? = null,
        @McpDescription("Show dependency tree (list only, default false)") tree: Boolean = false
    ): String {
        return when (action.lowercase()) {
            "list" -> listPackages(projectPath, tree)
            "add" -> managePackage("add", name, projectPath, version, features)
            "remove" -> managePackage("remove", name, projectPath, null, null)
            "update" -> updatePackages(`package`)
            "tree" -> showTree(projectPath)
            else -> mcpFail("Unknown action '$action'. Use: list, add, remove, update, tree")
        }
    }

    private suspend fun listPackages(projectPath: String?, tree: Boolean): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val cmdArgs = mutableListOf<String>()
        if (tree) {
            cmdArgs.add("tree")
        } else {
            cmdArgs.add("metadata")
            cmdArgs.add("--format-version=1")
        }
        projectPath?.let { cmdArgs.add("--manifest-path"); cmdArgs.add(it) }

        val (exitCode, output) = runCargoSync(project, cmdArgs, timeout = 30)
        if (exitCode != 0) mcpFail("cargo ${if (tree) "tree" else "metadata"} failed:\n$output")
        return output.trim().ifEmpty { "No packages found" }
    }

    private suspend fun managePackage(op: String, name: String?, projectPath: String?, version: String?, features: String?): String {
        if (name.isNullOrBlank()) mcpFail("name is required for action='$op'")
        val project = coroutineContext.project
        if (!name.matches(CRATE_NAME_RE)) mcpFail("Invalid crate name: $name (must be lowercase, hyphens/underscores only)")
        version?.let { if (!it.matches(VERSION_RE)) mcpFail("Invalid version: $it") }
        features?.let { if (it.contains(" ")) mcpFail("Features must be comma-separated without spaces") }
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }

        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(op)
        projectPath?.let { cmdArgs.add("--manifest-path"); cmdArgs.add(it) }
        cmdArgs.add(name)
        if (op == "add" && version != null) cmdArgs.addAll(listOf("--version", version))
        if (op == "add" && features != null && features.isNotBlank()) cmdArgs.addAll(listOf("--features", features))

        val (exitCode, output) = runCargoSync(project, cmdArgs, timeout = 60)
        if (exitCode != 0) mcpFail(output.trim())
        return output.trim()
    }

    private suspend fun updatePackages(targetPackage: String?): String {
        val project = coroutineContext.project
        val cmdArgs = mutableListOf("update")
        targetPackage?.let { cmdArgs.addAll(listOf("--package", it)) }
        val (exitCode, output) = runCargoSync(project, cmdArgs, timeout = 120)
        if (exitCode != 0) mcpFail("cargo update failed:\n$output")
        return output.trim()
    }

    private suspend fun showTree(projectPath: String?): String {
        return listPackages(projectPath, true)
    }

    // Timeouts stay under the ~60 s MCP call limit; the handler kills the process on timeout.
    private suspend fun runCargoSync(project: com.intellij.openapi.project.Project, args: List<String>, timeout: Long = 30): Pair<Int, String> {
        val projectPath = project.basePath ?: return -1 to "No project path"
        val cargoCmd = findCargo(project)
        val cmd = com.intellij.execution.configurations.GeneralCommandLine(cargoCmd)
            .withWorkDirectory(projectPath)
            .withParameters(*args.toTypedArray())
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(cmd).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: cargo ${args.joinToString(" ")}"
        return result.exitCode to result.stdout
    }

    private fun findCargo(project: com.intellij.openapi.project.Project): String {
        // Use the cargo from the Rust toolchain configured in the IDE
        // Try to find cargo via rustup or system PATH
        val env = System.getenv()
        val cargoHome = env["CARGO_HOME"] ?: env["RUSTUP_HOME"]?.let { "$it/cargo" } ?: System.getProperty("user.home") + "/.cargo"
        val cargoBin = Path.of(cargoHome, "bin", if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo")
        if (cargoBin.toFile().exists()) return cargoBin.toString()
        // Fallback to PATH
        return "cargo"
    }
}