package com.github.tropin.ridermcp.ide

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class ListTerminalsTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "rider_list_terminals"
    override val description = "Lists open terminal tabs in the IDE with their names."

    override fun handle(project: Project, args: NoArgs): Response {
        val twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val tw = twm.getToolWindow("Terminal")
            ?: return Response(error = "Terminal tool window not available")

        val cm = tw.contentManager
        val result = buildJsonArray {
            cm.contents.forEachIndexed { index, content ->
                addJsonObject {
                    put("index", index)
                    put("name", content.displayName ?: "Terminal ${index + 1}")
                    if (cm.selectedContent == content) put("active", true)
                }
            }
        }
        return Response(result.toString())
    }
}

@Serializable
data class SendTerminalInputArgs(val text: String, val tab: Int = 0)

class SendTerminalInputTool : AbstractMcpTool<SendTerminalInputArgs>(SendTerminalInputArgs.serializer()) {
    override val name = "rider_send_terminal_input"
    override val description = "Sends text input to a terminal tab. Use tab index from rider_list_terminals (default 0). Appends newline automatically."

    override fun handle(project: Project, args: SendTerminalInputArgs): Response {
        val twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val tw = twm.getToolWindow("Terminal")
            ?: return Response(error = "Terminal tool window not available")

        val cm = tw.contentManager
        val content = cm.contents.getOrNull(args.tab)
            ?: return Response(error = "Terminal tab ${args.tab} not found. Available: ${cm.contents.size}")

        val component = content.component
        val widget = findTerminalWidget(component)
            ?: return Response(error = "Cannot find terminal widget in tab ${args.tab}")

        widget.terminalStarter?.sendString(args.text + "\n", false)
            ?: return Response(error = "Terminal not ready (no process attached)")

        return Response("ok")
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
