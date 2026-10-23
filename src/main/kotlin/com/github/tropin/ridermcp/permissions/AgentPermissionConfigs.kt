package com.github.tropin.ridermcp.permissions

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/** Which tools of the IDE MCP server an agent may call without asking. */
enum class PermissionScope { READ_ONLY, ALL }

/**
 * Pure text transforms over agent client config files. Each `set*` / `merge*` returns the
 * updated file text, or null when the file already grants what was asked (nothing to write).
 * Clients only ever get rules added — existing rules are never removed or rewritten.
 */
internal object AgentPermissionConfigs {

    // Server keys the JetBrains MCP Server writes on Auto-Configure (product name, legacy key),
    // plus any entry pointing at a local /sse or /stream endpoint — the IDE server's URL shape.
    private val knownKeys = setOf("rider", "jetbrains")
    private val ideUrl = Regex("""^https?://(127\.0\.0\.1|localhost)(:\d+)?/(sse|stream)/?$""")

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun isIdeServer(key: String, url: String?): Boolean =
        key.lowercase() in knownKeys || (url != null && ideUrl.matches(url.trim()))

    // --- Claude Code: permissions.allow in settings.json ---

    /** IDE server keys from `~/.claude.json` (user + this project's entry) and the project's `.mcp.json`. */
    fun claudeServerKeys(claudeJson: String?, mcpJson: String?, projectPath: String): Set<String> {
        val root = claudeJson?.let(::parseObject)
        val servers = listOfNotNull(
            root?.obj("mcpServers"),
            root?.obj("projects")?.obj(projectPath)?.obj("mcpServers"),
            mcpJson?.let(::parseObject)?.obj("mcpServers"),
        )
        return servers.flatMap { ideKeys(it) }.toSortedSet()
    }

    fun claudeAllowRules(keys: Collection<String>, scope: PermissionScope, readOnlyTools: Collection<String>): List<String> =
        keys.map { it.replace(Regex("[^A-Za-z0-9_-]"), "_") }.flatMap { key ->
            when (scope) {
                PermissionScope.ALL -> listOf("mcp__$key")
                PermissionScope.READ_ONLY -> readOnlyTools.map { "mcp__${key}__$it" }
            }
        }

    fun mergeClaudeSettings(settings: String?, rules: List<String>): String? {
        val root = settings?.takeIf { it.isNotBlank() }
            ?.let { parseObject(it) ?: throw IllegalArgumentException("not a JSON object, left untouched") }
            ?: JsonObject(emptyMap())
        val permissions = root.obj("permissions") ?: JsonObject(emptyMap())
        val allow = (permissions["allow"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val missing = rules.filter { it !in allow }
        if (missing.isEmpty()) return null
        val updatedPermissions = JsonObject(permissions + ("allow" to JsonArray((allow + missing).map(::JsonPrimitive))))
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("permissions" to updatedPermissions))) + "\n"
    }

    // --- Codex: default_tools_approval_mode in config.toml ---

    private val codexHeader = Regex("""^\s*\[\s*mcp_servers\.(?:"([^"]+)"|([A-Za-z0-9_-]+))\s*]\s*(#.*)?$""")
    private val codexUrl = Regex("""^\s*url\s*=\s*"([^"]*)"""")
    private val codexMode = Regex("""^\s*default_tools_approval_mode\s*=""")

    /**
     * Codex approval mode: `writes` prompts only for tools not marked read-only, `approve` never prompts.
     * An existing `approve` is never narrowed back to `writes`.
     */
    fun codexApprovalMode(scope: PermissionScope): String = if (scope == PermissionScope.ALL) "approve" else "writes"

    fun codexServerKeys(toml: String): Set<String> =
        codexSections(toml.lines()).filter { (key, range) ->
            isIdeServer(key, toml.lines().slice(range).firstNotNullOfOrNull { codexUrl.find(it)?.groupValues?.get(1) })
        }.map { it.first }.toSortedSet()

    fun setCodexApprovalMode(toml: String, key: String, mode: String): String? {
        val lines = toml.lines().toMutableList()
        val (_, range) = codexSections(lines).firstOrNull { it.first == key } ?: return null
        val line = "default_tools_approval_mode = \"$mode\""
        val existing = range.drop(1).firstOrNull { codexMode.containsMatchIn(lines[it]) }
        when {
            existing == null -> lines.add(range.first + 1, line)
            lines[existing].substringBefore('#').trim() == line -> return null
            lines[existing].contains("\"approve\"") -> return null
            else -> lines[existing] = line
        }
        return lines.joinToString("\n")
    }

    /** `[mcp_servers.<key>]` sections: key to line range (header included, up to the next header). */
    private fun codexSections(lines: List<String>): List<Pair<String, IntRange>> {
        val headers = lines.indices.filter { lines[it].trimStart().startsWith("[") }
        return headers.mapIndexedNotNull { i, start ->
            val m = codexHeader.find(lines[start]) ?: return@mapIndexedNotNull null
            val end = (headers.getOrNull(i + 1) ?: lines.size) - 1
            (m.groupValues[1].ifEmpty { m.groupValues[2] }) to (start..end)
        }
    }

    // --- Gemini CLI: per-server "trust": true in settings.json ---

    fun geminiServerKeys(settings: String): Set<String> =
        parseObject(settings)?.obj("mcpServers")?.let(::ideKeys).orEmpty().toSortedSet()

    fun setGeminiTrust(settings: String, key: String): String? {
        val root = parseObject(settings) ?: return null
        val servers = root.obj("mcpServers") ?: return null
        val entry = servers.obj(key) ?: return null
        if ((entry["trust"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val updated = JsonObject(servers + (key to JsonObject(entry + ("trust" to JsonPrimitive(true)))))
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("mcpServers" to updated))) + "\n"
    }

    // --- helpers ---

    private fun ideKeys(servers: JsonObject): List<String> = servers.mapNotNull { (key, value) ->
        val entry = value as? JsonObject
        val url = (entry?.get("url") ?: entry?.get("httpUrl")) as? JsonPrimitive
        key.takeIf { isIdeServer(it, url?.contentOrNull) }
    }

    private fun parseObject(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
