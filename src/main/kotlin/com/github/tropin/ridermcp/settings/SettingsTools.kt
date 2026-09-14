package com.github.tropin.ridermcp.settings

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.ScopeToolState
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

// === List Inspections ===

@Serializable
data class ListInspectionsArgs(val filter: String? = null, val enabledOnly: Boolean = false, val limit: Int = 50)

class ListInspectionsTool : AbstractMcpTool<ListInspectionsArgs>(ListInspectionsArgs.serializer()) {
    override val name = "rider_list_inspections"
    override val description = "Lists code inspections. Optional filter by keyword in name/shortName. enabledOnly shows only active ones. Returns shortName for use with rider_toggle_inspection."

    override fun handle(project: Project, args: ListInspectionsArgs): Response {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile as? InspectionProfileImpl
            ?: return Response(error = "Cannot access inspection profile")

        var tools: List<ScopeToolState> = profile.getAllTools()

        if (args.filter != null) {
            val kw = args.filter.lowercase()
            tools = tools.filter {
                it.tool.shortName.lowercase().contains(kw) ||
                it.tool.displayName.lowercase().contains(kw) ||
                it.tool.groupDisplayName.lowercase().contains(kw)
            }
        }
        if (args.enabledOnly) {
            tools = tools.filter { it.isEnabled }
        }

        val result = buildJsonObject {
            put("profile", profile.displayName)
            put("total", tools.size)
            putJsonArray("inspections") {
                tools.take(args.limit).forEach { state ->
                    addJsonObject {
                        put("shortName", state.tool.shortName)
                        put("name", state.tool.displayName)
                        put("group", state.tool.groupDisplayName)
                        put("enabled", state.isEnabled)
                        put("level", state.level.name)
                    }
                }
            }
            if (tools.size > args.limit) put("truncated", true)
        }
        return Response(result.toString())
    }
}

// === Toggle Inspection ===

@Serializable
data class ToggleInspectionArgs(val shortName: String, val enabled: Boolean)

class ToggleInspectionTool : AbstractMcpTool<ToggleInspectionArgs>(ToggleInspectionArgs.serializer()) {
    override val name = "rider_toggle_inspection"
    override val description = "Enables or disables a code inspection by shortName (from rider_list_inspections)."

    override fun handle(project: Project, args: ToggleInspectionArgs): Response {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile as? InspectionProfileImpl
            ?: return Response(error = "Cannot access inspection profile")

        val tool = profile.getToolsOrNull(args.shortName, null)
            ?: return Response(error = "Inspection '${args.shortName}' not found. Use rider_list_inspections to find the shortName.")

        if (tool.isEnabled == args.enabled) {
            return Response("Inspection '${args.shortName}' is already ${if (args.enabled) "enabled" else "disabled"}")
        }

        profile.setToolEnabled(args.shortName, args.enabled)
        return Response("Inspection '${args.shortName}' ${if (args.enabled) "enabled" else "disabled"}")
    }
}
