package com.github.tropin.ridermcp.ide

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.runOnEdt
import kotlin.coroutines.coroutineContext

class TerminalToolset : McpToolset {

    @McpTool
    @McpDescription("Lists IDE-integrated terminal tabs (name + index, marks active). Call first to resolve the tab index for rider_send_terminal_input. This is for interactive shells only — NOT for run/debug consoles (use rider_get_output or rider_tool_window windowId='Run'/'Debug') and NOT for tool-window text dumps (use rider_tool_window).")
    suspend fun rider_list_terminals(): String {
        val project = coroutineContext.project
        return runOnEdt {
            val twm = ToolWindowManager.getInstance(project)
            val tw = twm.getToolWindow("Terminal")
                ?: mcpFail("Terminal tool window not available")

            val cm = tw.contentManager
            buildJsonArray {
                cm.contents.forEachIndexed { index, content ->
                    addJsonObject {
                        put("index", index)
                        put("name", content.displayName ?: "Terminal ${index + 1}")
                        if (cm.selectedContent == content) put("active", true)
                    }
                }
            }.toString()
        }
    }

    @McpTool
    @McpDescription("Sends text/command to an IDE terminal tab and executes it (newline appended). Resolves the tab via rider_list_terminals (default tab=0). Fire-and-forget: there is NO reliable read-back of terminal output via tools — for output you need, use rider_build/rider_tests/rider_nuget instead. Confirm destructive shell commands with the user first. Fails when the tab has no attached shell process.")
    suspend fun rider_send_terminal_input(
        @McpDescription("Text/command to execute (newline appended automatically)") text: String,
        @McpDescription("Terminal tab index from rider_list_terminals (default 0)") tab: Int = 0
    ): String {
        val project = coroutineContext.project
        runOnEdt {
            val twm = ToolWindowManager.getInstance(project)
            val tw = twm.getToolWindow("Terminal")
                ?: mcpFail("Terminal tool window not available")

            val cm = tw.contentManager
            val content = cm.contents.getOrNull(tab)
                ?: mcpFail("Terminal tab $tab not found. Available: ${cm.contents.size}")

            val component = content.component
            val widget = findTerminalWidget(component)
                ?: mcpFail("Cannot find terminal widget in tab $tab")

            val connector = widget.ttyConnector
                ?: mcpFail("Terminal not ready (no process attached)")
            connector.write(text + "\n")
        }

        return "ok"
    }

    private fun findTerminalWidget(component: java.awt.Component): com.jediterm.terminal.ui.JediTermWidget? {
        if (component is com.jediterm.terminal.ui.JediTermWidget) return component
        if (component is java.awt.Container) {
            for (i in 0 until component.componentCount) {
                findTerminalWidget(component.getComponent(i))?.let { return it }
            }
        }
        return null
    }
}
