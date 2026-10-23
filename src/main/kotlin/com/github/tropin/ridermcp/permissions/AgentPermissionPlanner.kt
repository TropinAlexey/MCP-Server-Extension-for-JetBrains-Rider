package com.github.tropin.ridermcp.permissions

import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/** One proposed config change. [updated] is null when nothing will be written (see [status]). */
data class PlannedEdit(val client: String, val path: Path, val status: String, val updated: String?)

/**
 * Finds the agent clients connected to this IDE's MCP server and plans the edits that let them
 * call its tools without a prompt. Only clients with a documented, file-based approval setting are
 * edited; the rest are covered by the dialog's manual hints. Only user-owned config files are read:
 * project files (`.mcp.json`, `.codex/`, `.gemini/`) come with the repo and could name any server.
 */
internal class AgentPermissionPlanner(
    private val projectDir: Path,
    private val home: Path,
    private val readOnlyTools: List<String>,
    private val idePort: Int,
    private val env: (String) -> String? = System::getenv,
) {
    // Config dirs the clients let users relocate: CLAUDE_CONFIG_DIR holds both .claude.json and settings.json.
    private val claudeDir = env("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
    private val codexHome = env("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(".codex")

    fun plan(scope: PermissionScope, claudeUserWide: Boolean): List<PlannedEdit> =
        claudeCode(scope, claudeUserWide) + codex(scope) + gemini(scope)

    private fun claudeCode(scope: PermissionScope, userWide: Boolean): List<PlannedEdit> {
        val userSettings = claudeDir?.resolve("settings.json") ?: home.resolve(".claude/settings.json")
        val target = if (userWide) userSettings else projectDir.resolve(".claude/settings.local.json")
        // User-wide rules match the server name in every project, so only user-scope entries count for them.
        val keys = AgentPermissionConfigs.claudeServerKeys(
            read((claudeDir ?: home).resolve(".claude.json")), projectDir.toString(), idePort, includeProject = !userWide
        )
        if (keys.isEmpty()) return listOf(PlannedEdit(CLAUDE, target, "not connected to this IDE — skipped", null))
        val rules = AgentPermissionConfigs.claudeAllowRules(keys, scope, readOnlyTools)
        val updated = runCatching { AgentPermissionConfigs.mergeClaudeSettings(read(target), rules) }
            .getOrElse { return listOf(PlannedEdit(CLAUDE, target, "skipped: ${it.message}", null)) }
        val status = if (updated == null) "already allowed" else describeRules(rules)
        return listOf(PlannedEdit(CLAUDE, target, status, updated))
    }

    private fun codex(scope: PermissionScope): List<PlannedEdit> {
        val mode = AgentPermissionConfigs.codexApprovalMode(scope)
        return configured(CODEX, listOf(codexHome.resolve("config.toml"))) { path, text ->
            val keys = AgentPermissionConfigs.codexServerKeys(text, idePort)
            if (keys.isEmpty()) return@configured null
            val updated = keys.fold(text) { acc, key -> AgentPermissionConfigs.setCodexApprovalMode(acc, key, mode) ?: acc }
            val status = if (updated == text) "already allowed" else "default_tools_approval_mode = \"$mode\" for ${keys.joinToString()}"
            PlannedEdit(CODEX, path, status, updated.takeIf { it != text })
        }
    }

    private fun gemini(scope: PermissionScope): List<PlannedEdit> =
        configured(GEMINI, listOf(home.resolve(".gemini/settings.json"))) { path, text ->
            val keys = AgentPermissionConfigs.geminiServerKeys(text, idePort)
            when {
                keys.isEmpty() -> null
                // Gemini CLI trusts a server only as a whole: there is no read-only subset to grant.
                scope == PermissionScope.READ_ONLY -> PlannedEdit(GEMINI, path, "trusts whole servers only — choose All tools, or approve in its prompt", null)
                else -> {
                    val updated = keys.fold(text) { acc, key -> AgentPermissionConfigs.setGeminiTrust(acc, key) ?: acc }
                    PlannedEdit(GEMINI, path, if (updated == text) "already trusted" else "\"trust\": true for ${keys.joinToString()}", updated.takeIf { it != text })
                }
            }
        }

    /** Edits from every existing config file of a client; a single "not connected" entry when none mention the IDE server. */
    private fun configured(client: String, paths: List<Path>, edit: (Path, String) -> PlannedEdit?): List<PlannedEdit> {
        val edits = paths.distinct().mapNotNull { path -> read(path)?.let { edit(path, it) } }
        return edits.ifEmpty { listOf(PlannedEdit(client, paths.first(), "not connected to this IDE — skipped", null)) }
    }

    private fun describeRules(rules: List<String>): String =
        if (rules.size <= 2) rules.joinToString() else "${rules.size} read-only tool rules (${rules.first()}, …)"

    private fun read(path: Path): String? = runCatching { if (path.exists()) path.readText() else null }.getOrNull()

    companion object {
        const val CLAUDE = "Claude Code"
        const val CODEX = "Codex"
        const val GEMINI = "Gemini CLI"

        /** Writes through a sibling temp file so a crash never leaves a half-written client config. */
        fun write(edit: PlannedEdit) {
            val text = edit.updated ?: return
            Files.createDirectories(edit.path.parent)
            val tmp = Files.createTempFile(edit.path.parent, edit.path.fileName.toString(), ".tmp")
            Files.writeString(tmp, text)
            Files.move(tmp, edit.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        /**
         * Names of read-only tools on the IDE MCP server: built-in tools that declare readOnlyHint,
         * plus this plugin's own toolsets read straight from their @McpToolHints.
         */
        fun readOnlyToolNames(): List<String> {
            val builtIn = McpToolsProvider.EP.extensionList
                .flatMap { runCatching { it.getTools() }.getOrDefault(emptyList()) }
                .filter { it.descriptor.annotations?.readOnlyHint == true }
                .map { it.descriptor.name }
            val own = McpToolset.EP.extensionList.flatMap { toolset ->
                toolset.javaClass.methods.filter { m ->
                    m.isAnnotationPresent(McpTool::class.java) &&
                        m.getAnnotation(McpToolHints::class.java)?.readOnlyHint == McpToolHintValue.TRUE
                }.map { it.name }
            }
            return (builtIn + own).distinct().sorted()
        }
    }
}
