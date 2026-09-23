package com.github.tropin.ridermcp.settings

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.ScopeToolState
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class SettingsToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages Rider static-analysis inspections. action='list' (default) searches rules by keyword (filter matches shortName/name/group), enabledOnly narrows to active, limit caps results. " +
            "action='toggle' enables/disables one rule (requires shortName from list + enabled flag). " +
            "For actual code problems use rider_tool_window(windowId='Problems'), not this catalog tool."
    )
    suspend fun rider_inspections(
        @McpDescription("Action: list (default) searches rules, toggle enables/disables one rule") action: String = "list",
        @McpDescription("Keyword filter over shortName/displayName/group (list only)") filter: String? = null,
        @McpDescription("Show only enabled rules (list only, default false)") enabledOnly: Boolean = false,
        @McpDescription("Max rules returned (list only, default 50)") limit: Int = 50,
        @McpDescription("Inspection shortName from list (required for toggle)") shortName: String? = null,
        @McpDescription("true = enable, false = disable (toggle only, default true)") enabled: Boolean = true
    ): String {
        return when (action.lowercase()) {
            "list" -> listInspections(filter, enabledOnly, limit)
            "toggle" -> toggleInspection(shortName, enabled)
            else -> mcpFail("Unknown action '$action'. Use: list, toggle")
        }
    }

    private suspend fun listInspections(filter: String?, enabledOnly: Boolean, limit: Int): String {
        val project = coroutineContext.project
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile as? InspectionProfileImpl
            ?: mcpFail("Cannot access inspection profile")

        var tools: List<ScopeToolState> = profile.getAllTools()

        if (filter != null) {
            val kw = filter.lowercase()
            tools = tools.filter {
                it.tool.shortName.lowercase().contains(kw) ||
                it.tool.displayName.lowercase().contains(kw) ||
                it.tool.groupDisplayName.lowercase().contains(kw)
            }
        }
        if (enabledOnly) {
            tools = tools.filter { it.isEnabled }
        }

        return buildJsonObject {
            put("profile", profile.displayName)
            put("total", tools.size)
            putJsonArray("inspections") {
                tools.take(limit).forEach { state ->
                    addJsonObject {
                        put("shortName", state.tool.shortName)
                        put("name", state.tool.displayName)
                        put("group", state.tool.groupDisplayName)
                        put("enabled", state.isEnabled)
                        put("level", state.level.name)
                    }
                }
            }
            if (tools.size > limit) put("truncated", true)
        }.toString()
    }

    private suspend fun toggleInspection(shortName: String?, enabled: Boolean): String {
        if (shortName.isNullOrBlank()) mcpFail("shortName is required for action='toggle'")
        val project = coroutineContext.project
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile as? InspectionProfileImpl
            ?: mcpFail("Cannot access inspection profile")

        profile.getToolsOrNull(shortName, null)
            ?: mcpFail("Inspection '$shortName' not found. Use rider_inspections(action='list') to find the shortName.")

        val tool = profile.getToolsOrNull(shortName, null)!!
        if (tool.isEnabled == enabled) {
            return "Inspection '$shortName' is already ${if (enabled) "enabled" else "disabled"}"
        }

        profile.setToolEnabled(shortName, enabled)
        return "Inspection '$shortName' ${if (enabled) "enabled" else "disabled"}"
    }
}
