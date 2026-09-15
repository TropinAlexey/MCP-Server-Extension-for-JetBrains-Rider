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
    @McpDescription("Lists code inspections (static analysis rules, code quality checks). Filter by keyword in name/shortName/group. enabledOnly=true shows only active rules. Returns shortName needed by rider_toggle_inspection. Use to find and review which code analysis rules are active.")
    suspend fun rider_list_inspections(
        @McpDescription("Filter keyword") filter: String? = null,
        @McpDescription("Show only enabled") enabledOnly: Boolean = false,
        @McpDescription("Max results") limit: Int = 50
    ): String {
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

    @McpTool
    @McpDescription("Enables or disables a code inspection (static analysis rule) by shortName from rider_list_inspections. Use to suppress noisy warnings or enable stricter checks.")
    suspend fun rider_toggle_inspection(
        @McpDescription("Inspection short name") shortName: String,
        @McpDescription("Enable or disable") enabled: Boolean
    ): String {
        val project = coroutineContext.project
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile as? InspectionProfileImpl
            ?: mcpFail("Cannot access inspection profile")

        profile.getToolsOrNull(shortName, null)
            ?: mcpFail("Inspection '$shortName' not found. Use rider_list_inspections to find the shortName.")

        val tool = profile.getToolsOrNull(shortName, null)!!
        if (tool.isEnabled == enabled) {
            return "Inspection '$shortName' is already ${if (enabled) "enabled" else "disabled"}"
        }

        profile.setToolEnabled(shortName, enabled)
        return "Inspection '$shortName' ${if (enabled) "enabled" else "disabled"}"
    }
}
