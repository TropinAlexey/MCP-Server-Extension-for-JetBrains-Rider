package com.github.tropin.ridermcp.admin

import com.intellij.ide.InvalidateCacheService
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

// === Manage Plugin ===

@Serializable
data class ManagePluginArgs(
    val action: String,
    val filter: String? = null,
    val pluginId: String? = null,
    val limit: Int = 30
)

class ManagePluginTool : AbstractMcpTool<ManagePluginArgs>(ManagePluginArgs.serializer()) {
    override val name = "rider_manage_plugin"
    override val description = "Manages IDE plugins: list installed plugins (filter by keyword), enable or disable a plugin by id. Use to check plugin versions, find plugin ids, or toggle plugins. Enable/disable requires IDE restart."

    override fun handle(project: Project, args: ManagePluginArgs): Response {
        return when (args.action) {
            "list" -> listPlugins(args)
            "enable" -> togglePlugin(args.pluginId, true)
            "disable" -> togglePlugin(args.pluginId, false)
            else -> Response(error = "Unknown action '${args.action}'. Use: list, enable, disable")
        }
    }

    private fun allPlugins(): List<IdeaPluginDescriptor> =
        PluginManagerCore.plugins.toList()

    private fun listPlugins(args: ManagePluginArgs): Response {
        var plugins: List<IdeaPluginDescriptor> = allPlugins()

        if (args.filter != null) {
            val kw = args.filter.lowercase()
            plugins = plugins.filter { p ->
                p.pluginId.idString.lowercase().contains(kw) ||
                (p.name ?: "").lowercase().contains(kw)
            }
        }

        val result = buildJsonObject {
            put("total", plugins.size)
            putJsonArray("plugins") {
                plugins.take(args.limit).forEach { p ->
                    addJsonObject {
                        put("id", p.pluginId.idString)
                        put("name", p.name ?: p.pluginId.idString)
                        p.version?.let { v -> put("version", v) }
                        put("enabled", !PluginManagerCore.isDisabled(p.pluginId))
                        if (p.isBundled) put("bundled", true)
                    }
                }
            }
            if (plugins.size > args.limit) put("truncated", true)
        }
        return Response(result.toString())
    }

    // ponytail: no public API for enable/disable plugins in IntelliJ Platform
    @Suppress("UnstableApiUsage")
    private fun togglePlugin(pluginId: String?, enable: Boolean): Response {
        if (pluginId == null) return Response(error = "pluginId is required for enable/disable")

        val pid = com.intellij.openapi.extensions.PluginId.getId(pluginId)
        val descriptor = allPlugins().find { p -> p.pluginId == pid }
            ?: return Response(error = "Plugin '$pluginId' not found. Use action 'list' to see available plugins.")

        val isCurrentlyEnabled = !PluginManagerCore.isDisabled(pid)
        if (isCurrentlyEnabled == enable) {
            return Response("Plugin '$pluginId' is already ${if (enable) "enabled" else "disabled"}")
        }

        if (enable) PluginManagerCore.enablePlugin(pid) else PluginManagerCore.disablePlugin(pid)

        val result = buildJsonObject {
            put("plugin", pluginId)
            put("name", descriptor.name ?: pluginId)
            put("action", if (enable) "enabled" else "disabled")
            put("restartRequired", true)
        }
        return Response(result.toString())
    }
}

// === Invalidate Caches ===

class InvalidateCachesTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "rider_invalidate_caches"
    override val description = "Invalidates IDE caches and restarts Rider. Use when IDE shows stale state: wrong syntax highlighting, missing references, broken code completion, indexing stuck, or phantom errors. This is the 'nuclear option' for IDE glitches."

    override fun handle(project: Project, args: NoArgs): Response {
        ApplicationManager.getApplication().invokeLater {
            InvalidateCacheService.invalidateCachesAndRestart(project)
        }
        return Response(buildJsonObject { put("status", "invalidating_and_restarting") }.toString())
    }
}
