package com.github.tropin.ridermcp.admin

import com.intellij.ide.InvalidateCacheService
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class AdminToolset : McpToolset {

    @McpTool
    @McpDescription("Manages IDE plugins: list installed plugins (filter by keyword), enable or disable a plugin by id. Use to check plugin versions, find plugin ids, or toggle plugins. Enable/disable requires IDE restart.")
    suspend fun rider_manage_plugin(
        @McpDescription("Action: list, enable, disable") action: String,
        @McpDescription("Keyword filter (for list)") filter: String? = null,
        @McpDescription("Plugin ID (for enable/disable)") pluginId: String? = null,
        @McpDescription("Max results (for list)") limit: Int = 30
    ): String {
        return when (action) {
            "list" -> listPlugins(filter, limit)
            "enable" -> togglePlugin(pluginId, true)
            "disable" -> togglePlugin(pluginId, false)
            else -> mcpFail("Unknown action '$action'. Use: list, enable, disable")
        }
    }

    private fun allPlugins(): List<IdeaPluginDescriptor> =
        PluginManagerCore.plugins.toList()

    private fun listPlugins(filter: String?, limit: Int): String {
        var plugins: List<IdeaPluginDescriptor> = allPlugins()

        if (filter != null) {
            val kw = filter.lowercase()
            plugins = plugins.filter { p ->
                p.pluginId.idString.lowercase().contains(kw) ||
                (p.name ?: "").lowercase().contains(kw)
            }
        }

        return buildJsonObject {
            put("total", plugins.size)
            putJsonArray("plugins") {
                plugins.take(limit).forEach { p ->
                    addJsonObject {
                        put("id", p.pluginId.idString)
                        put("name", p.name ?: p.pluginId.idString)
                        p.version?.let { v -> put("version", v) }
                        put("enabled", !PluginManagerCore.isDisabled(p.pluginId))
                        if (p.isBundled) put("bundled", true)
                    }
                }
            }
            if (plugins.size > limit) put("truncated", true)
        }.toString()
    }

    @Suppress("UnstableApiUsage")
    private fun togglePlugin(pluginId: String?, enable: Boolean): String {
        if (pluginId == null) mcpFail("pluginId is required for enable/disable")

        val pid = com.intellij.openapi.extensions.PluginId.getId(pluginId)
        val descriptor = allPlugins().find { p -> p.pluginId == pid }
            ?: mcpFail("Plugin '$pluginId' not found. Use action 'list' to see available plugins.")

        val isCurrentlyEnabled = !PluginManagerCore.isDisabled(pid)
        if (isCurrentlyEnabled == enable) {
            return "Plugin '$pluginId' is already ${if (enable) "enabled" else "disabled"}"
        }

        if (enable) PluginManagerCore.enablePlugin(pid) else PluginManagerCore.disablePlugin(pid)

        return buildJsonObject {
            put("plugin", pluginId)
            put("name", descriptor.name ?: pluginId)
            put("action", if (enable) "enabled" else "disabled")
            put("restartRequired", true)
        }.toString()
    }

    @McpTool
    @McpDescription("Invalidates IDE caches and restarts Rider. Use when IDE shows stale state: wrong syntax highlighting, missing references, broken code completion, indexing stuck, or phantom errors. This is the 'nuclear option' for IDE glitches.")
    suspend fun rider_invalidate_caches(): String {
        val project = coroutineContext.project
        ApplicationManager.getApplication().invokeLater {
            InvalidateCacheService.invalidateCachesAndRestart(project)
        }
        return buildJsonObject { put("status", "invalidating_and_restarting") }.toString()
    }
}
