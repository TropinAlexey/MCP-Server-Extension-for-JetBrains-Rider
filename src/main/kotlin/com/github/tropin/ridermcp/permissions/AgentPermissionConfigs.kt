package com.github.tropin.ridermcp.permissions

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.Yaml
import java.util.regex.Pattern

/** Which tools of the IDE MCP server an agent may call without asking. */
enum class PermissionScope { READ_ONLY, ALL }

/**
 * Pure text transforms over agent client config files. Each `set*` / `merge*` returns the
 * updated file text, or null when the file already grants what was asked (nothing to write).
 * Clients only ever get rules added — existing rules are never removed or rewritten.
 */
internal object AgentPermissionConfigs {

    // An entry is this IDE's server only when it reaches this IDE's port: a local /sse or /stream URL,
    // or the JetBrains stdio proxy pointed at it via IJ_MCP_SERVER_PORT. Names are never trusted —
    // any config can call a foreign server "rider".
    private val ideUrl = Regex("""^https?://(127\.0\.0\.1|localhost):(\d+)/(sse|stream)/?$""")

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun isIdeUrl(url: String?, port: Int): Boolean =
        url != null && ideUrl.matchEntire(url.trim())?.groupValues?.get(2) == port.toString()

    // --- Claude Code: permissions.allow in settings.json ---

    /**
     * IDE server keys from `~/.claude.json`: user scope, plus this project's local scope when
     * [includeProject]. The repo's `.mcp.json` is never read — a cloned repo controls it.
     */
    fun claudeServerKeys(claudeJson: String?, projectPath: String, port: Int, includeProject: Boolean): Set<String> {
        val root = claudeJson?.let(::parseObject)
        val servers = listOfNotNull(
            root?.obj("mcpServers"),
            root?.obj("projects")?.obj(projectPath)?.obj("mcpServers")?.takeIf { includeProject },
        )
        return servers.flatMap { ideKeys(it, port) }.toSortedSet()
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

    fun codexServerKeys(toml: String, port: Int): Set<String> {
        val lines = toml.lines()
        val stdioPort = Regex("""IJ_MCP_SERVER_PORT\s*=\s*"?$port"?(\D|$)""")
        return codexSections(lines).filter { (_, range) ->
            val section = lines.slice(range)
            isIdeUrl(section.firstNotNullOfOrNull { codexUrl.find(it)?.groupValues?.get(1) }, port) ||
                section.any { stdioPort.containsMatchIn(it) }
        }.map { it.first }.toSortedSet()
    }

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

    fun geminiServerKeys(settings: String, port: Int): Set<String> =
        parseObject(settings)?.obj("mcpServers")?.let { ideKeys(it, port) }.orEmpty().toSortedSet()

    fun setGeminiTrust(settings: String, key: String): String? {
        val root = parseObject(settings) ?: return null
        val servers = root.obj("mcpServers") ?: return null
        val entry = servers.obj(key) ?: return null
        if ((entry["trust"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val updated = JsonObject(servers + (key to JsonObject(entry + ("trust" to JsonPrimitive(true)))))
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("mcpServers" to updated))) + "\n"
    }

    // --- helpers ---

    private fun ideKeys(servers: JsonObject, port: Int): List<String> = servers.mapNotNull { (key, value) ->
        val entry = value as? JsonObject
        val url = (entry?.get("url") ?: entry?.get("httpUrl")) as? JsonPrimitive
        val stdioPort = (entry?.obj("env")?.get("IJ_MCP_SERVER_PORT") as? JsonPrimitive)?.contentOrNull
        key.takeIf { isIdeUrl(url?.contentOrNull, port) || stdioPort == port.toString() }
    }

    private fun parseObject(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    // --- OpenCode: permission.mcp_rider_ in opencode.json ---

    private val opencodeUrl = Regex("""^https?://(127\.0\.0\.1|localhost):(\d+)/?$""")

    private fun isOpencodeIdeUrl(url: String?, port: Int): Boolean =
        url != null && opencodeUrl.matchEntire(url.trim())?.groupValues?.get(2) == port.toString()

    fun opencodeServerKeys(opencodeJson: String, port: Int): Set<String> {
        val root = parseObject(opencodeJson) ?: return emptySet()
        val mcp = root.obj("mcp") ?: return emptySet()
        return mcp.mapNotNull { (key, value) ->
            val entry = value as? JsonObject
            val url = entry?.get("url") as? JsonPrimitive
            key.takeIf { isOpencodeIdeUrl(url?.contentOrNull, port) }
        }.toSortedSet()
    }

    fun opencodeRule(scope: PermissionScope): Pair<String, String> = when (scope) {
        PermissionScope.READ_ONLY -> "permission.mcp_rider_" to "allow"
        PermissionScope.ALL -> "permission.mcp_rider_" to "allow"
    }

    fun setOpencodePermission(opencodeJson: String, key: String, ruleKey: String, ruleValue: String): String? {
        val root = parseObject(opencodeJson) ?: return null
        val mcp = root.obj("mcp") ?: return null
        val entry = mcp.obj(key) ?: return null
        val permission = entry.obj("permission") ?: JsonObject(emptyMap())
        val current = permission[ruleKey] as? JsonPrimitive
        if (current?.contentOrNull == ruleValue) return null
        val updatedPermission = JsonObject(permission + (ruleKey to JsonPrimitive(ruleValue)))
        val updatedEntry = JsonObject(entry + ("permission" to updatedPermission))
        val updatedMcp = JsonObject(mcp + (key to updatedEntry))
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("mcp" to updatedMcp))) + "\n"
    }

    // --- Continue: allow in permissions.yaml ---

    private val continueServerKey = Regex("""^  (?:mcp_)?([^:]+):$""")

    fun continueServerKeys(continueYaml: String, port: Int): Set<String> {
        // Continue stores MCP servers in config.yaml, but permissions.yaml only has allow/ask/exclude lists
        // We match mcp_rider_* patterns in the allow/ask lists
        val servers = mutableSetOf<String>()
        val yaml = try {
            Yaml().load(continueYaml) as? Map<*, *>
        } catch (_: Exception) {
            return emptySet()
        }
        if (yaml == null) return emptySet()
        val allow = (yaml["allow"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val ask = (yaml["ask"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        (allow + ask).forEach { entry ->
            val prefix = entry.substringBefore("_").takeIf { it.startsWith("mcp_") }
            prefix?.let { servers.add(it) }
        }
        return servers.toSortedSet()
    }

    fun continueRule(scope: PermissionScope): String = when (scope) {
        PermissionScope.READ_ONLY -> "mcp_rider_*"
        PermissionScope.ALL -> "mcp_rider_*"
    }

    fun setContinuePermission(continueYaml: String, key: String, rule: String): String? {
        val yaml = try {
            Yaml().load(continueYaml) as? MutableMap<String, Any>
        } catch (_: Exception) {
            return null
        }
        if (yaml == null) return null
        val allow = (yaml["allow"] as? MutableList<*>)?.mapNotNull { it as? String }?.toMutableList() ?: mutableListOf()
        if (rule in allow) return null
        allow.add(rule)
        yaml["allow"] = allow
        return Yaml().dump(yaml)
    }

    // --- Zed: agent.tool_permissions.tools["mcp:rider:*"] in settings.json ---

    private val zedUrl = Regex("""^https?://(127\.0\.0\.1|localhost):(\d+)/?$""")

    private fun isZedIdeUrl(url: String?, port: Int): Boolean =
        url != null && zedUrl.matchEntire(url.trim())?.groupValues?.get(2) == port.toString()

    fun zedServerKeys(zedJson: String, port: Int): Set<String> {
        val root = parseObject(zedJson) ?: return emptySet()
        val contextServers = root.obj("context_servers") ?: return emptySet()
        return contextServers.mapNotNull { (key, value) ->
            val entry = value as? JsonObject
            val command = entry?.obj("command")
            val args = command?.get("args") as? JsonArray
            val url = entry?.get("url") as? JsonPrimitive
            val env = entry?.obj("env")
            val portFromEnv = env?.get("IJ_MCP_SERVER_PORT") as? JsonPrimitive
            val matches = isZedIdeUrl(url?.contentOrNull, port) || portFromEnv?.contentOrNull == port.toString() ||
                (args?.joinToString(" ")?.contains(port.toString()) == true)
            key.takeIf { matches }
        }.toSortedSet()
    }

    fun zedRule(scope: PermissionScope): String = when (scope) {
        PermissionScope.READ_ONLY -> "allow"
        PermissionScope.ALL -> "allow"
    }

    fun setZedPermission(zedJson: String, key: String, ruleKey: String, ruleValue: String): String? {
        val root = parseObject(zedJson) ?: return null
        val agent = root.obj("agent") ?: JsonObject(emptyMap())
        val toolPermissions = agent.obj("tool_permissions") ?: JsonObject(emptyMap())
        val tools = toolPermissions.obj("tools") ?: JsonObject(emptyMap())
        val current = tools[ruleKey] as? JsonObject
        if (current != null && (current["default"] as? JsonPrimitive)?.contentOrNull == ruleValue) return null
        val toolEntry = JsonObject(mapOf("default" to JsonPrimitive(ruleValue)))
        val updatedTools = JsonObject(tools + (ruleKey to toolEntry))
        val updatedToolPermissions = JsonObject(toolPermissions + ("tools" to updatedTools))
        val updatedAgent = JsonObject(agent + ("tool_permissions" to updatedToolPermissions))
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("agent" to updatedAgent))) + "\n"
    }
}
