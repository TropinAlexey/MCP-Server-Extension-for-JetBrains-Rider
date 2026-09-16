package com.github.tropin.ridermcp.ide

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class TerminalToolset : McpToolset {

    @McpTool
    @McpDescription("Lists open terminal tabs/sessions in the IDE with their names and indices. Use before rider_send_terminal_input to find the correct tab index.")
    suspend fun rider_list_terminals(): String {
        val project = coroutineContext.project
        val twm = ToolWindowManager.getInstance(project)
        val tw = twm.getToolWindow("Terminal")
            ?: mcpFail("Terminal tool window not available")

        val cm = tw.contentManager
        return buildJsonArray {
            cm.contents.forEachIndexed { index, content ->
                addJsonObject {
                    put("index", index)
                    put("name", content.displayName ?: "Terminal ${index + 1}")
                    if (cm.selectedContent == content) put("active", true)
                }
            }
        }.toString()
    }

    @McpTool
    @McpDescription("Sends a command or text to an IDE terminal tab (executes it). Use tab index from rider_list_terminals (default 0). Appends newline automatically. Use to run shell commands, scripts, or interact with running processes in the IDE terminal.")
    suspend fun rider_send_terminal_input(
        @McpDescription("Text/command to send") text: String,
        @McpDescription("Terminal tab index") tab: Int = 0
    ): String {
        val project = coroutineContext.project
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
