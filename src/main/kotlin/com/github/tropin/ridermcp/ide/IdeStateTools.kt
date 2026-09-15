package com.github.tropin.ridermcp.ide

import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import com.github.tropin.ridermcp.projectDir
import com.github.tropin.ridermcp.relTo

class GetIdeStateTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "rider_get_ide_state"
    override val description = "Returns IDE activity status: progress indicators (indexing, building, analyzing), currently active/focused file, whether IDE is busy or idle. Use to check if IDE is ready before starting builds, tests, or refactoring."

    override fun handle(project: Project, args: NoArgs): Response {
        val activeFile = ApplicationManager.getApplication().runReadAction<String?> {
            FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                val projectDir = project.projectDir()
                editor.virtualFile?.toNioPathOrNull()?.relTo(projectDir) ?: editor.virtualFile?.path
            }
        }

        val result = buildJsonObject {
            put("activeFile", activeFile ?: "none")
        }
        return Response(result.toString())
    }
}

@Serializable
data class NotificationArgs(val limit: Int = 5)

class GetNotificationsTool : AbstractMcpTool<NotificationArgs>(NotificationArgs.serializer()) {
    override val name = "rider_get_notifications"
    override val description = "Returns recent IDE notifications (errors, warnings, info messages). Use to check for build errors, plugin updates, indexing issues, or any IDE alerts. Default limit: 5, pass {\"limit\": N} for more."

    override fun handle(project: Project, args: NotificationArgs): Response {
        val notifications = NotificationsManager.getNotificationsManager()
            .getNotificationsOfType(Notification::class.java, project)
            .takeLast(args.limit)

        val result = buildJsonArray {
            notifications.forEach { n ->
                addJsonObject {
                    n.title.takeIf { it.isNotEmpty() }?.let { put("title", it) }
                    n.content.takeIf { it.isNotEmpty() }?.let { put("content", it) }
                    put("type", n.type.name)
                    put("group", n.groupId)
                }
            }
        }
        return Response(result.toString())
    }
}

@Serializable
data class ListToolWindowsArgs(val all: Boolean = false)

class ListToolWindowsTool : AbstractMcpTool<ListToolWindowsArgs>(ListToolWindowsArgs.serializer()) {
    override val name = "rider_list_tool_windows"
    override val description = "Lists IDE tool windows (panels/panes like Terminal, Build, Debug, NuGet, TODO, Problems, etc.). By default only visible ones; pass {\"all\": true} to discover all available panels. Use to find windowId for rider_get_tool_window_content."

    override fun handle(project: Project, args: ListToolWindowsArgs): Response {
        val twm = ToolWindowManager.getInstance(project)
        val result = buildJsonArray {
            twm.toolWindowIds.forEach { id ->
                val tw = twm.getToolWindow(id) ?: return@forEach
                if (!args.all && !tw.isVisible) return@forEach
                addJsonObject {
                    put("id", id)
                    if (args.all) put("visible", tw.isVisible)
                    if (tw.isActive) put("active", true)
                }
            }
        }
        return Response(result.toString())
    }
}

@Serializable
data class ToolWindowArgs(val windowId: String, val tab: String? = null, val maxLines: Int = 200)

class GetToolWindowContentTool : AbstractMcpTool<ToolWindowArgs>(ToolWindowArgs.serializer()) {
    override val name = "rider_get_tool_window_content"
    override val description = "Reads text content from any IDE tool window/panel (Build output, Problems, NuGet, Database, etc.). Extracts text from editors, consoles, trees, and lists. Use to read build logs, error lists, or any panel content. Pass tab name for a specific tab; omit for the active one. maxLines caps output (default 200)."

    override fun handle(project: Project, args: ToolWindowArgs): Response {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(args.windowId)
            ?: return Response(error = "Tool window '${args.windowId}' not found")

        val cm = tw.contentManager
        val content = if (args.tab != null) {
            cm.contents.firstOrNull { it.displayName.equals(args.tab, ignoreCase = true) }
                ?: return Response(error = "Tab '${args.tab}' not found. Available: ${cm.contents.map { it.displayName }}")
        } else {
            cm.selectedContent ?: cm.contents.firstOrNull()
        } ?: return Response(error = "Tool window '${args.windowId}' has no content")

        val lines = mutableListOf<String>()
        val component = content.component
        extractText(component, lines, args.maxLines)

        val result = buildJsonObject {
            put("windowId", args.windowId)
            put("tab", content.displayName ?: "")
            if (lines.isEmpty()) {
                put("text", "(empty)")
            } else {
                put("text", lines.take(args.maxLines).joinToString("\n"))
                if (lines.size > args.maxLines) put("truncated", true)
            }
            val tabs = cm.contents.map { it.displayName ?: "" }
            if (tabs.size > 1) putJsonArray("otherTabs") { tabs.filter { it != (content.displayName ?: "") }.forEach { add(it) } }
        }
        return Response(result.toString())
    }

    private fun extractText(component: java.awt.Component, lines: MutableList<String>, limit: Int) {
        if (lines.size >= limit) return
        when (component) {
            is com.intellij.openapi.editor.Editor -> {
                val text = component.document.text
                if (text.isNotBlank()) text.lines().forEach { if (lines.size < limit) lines.add(it) }
            }
            is javax.swing.JTree -> {
                val model = component.model ?: return
                val root = model.root ?: return
                collectTreeText(model, root, lines, limit, 0)
            }
            is javax.swing.JList<*> -> {
                val m = component.model
                for (i in 0 until m.size) {
                    if (lines.size >= limit) break
                    lines.add(m.getElementAt(i)?.toString() ?: "")
                }
            }
            is javax.swing.text.JTextComponent -> {
                val text = component.text
                if (!text.isNullOrBlank()) text.lines().forEach { if (lines.size < limit) lines.add(it) }
            }
            is java.awt.Container -> {
                for (i in 0 until component.componentCount) {
                    if (lines.size >= limit) break
                    extractText(component.getComponent(i), lines, limit)
                }
            }
        }
    }

    private fun collectTreeText(model: javax.swing.tree.TreeModel, node: Any, lines: MutableList<String>, limit: Int, depth: Int) {
        if (lines.size >= limit) return
        val indent = "  ".repeat(depth)
        lines.add("$indent${node.toString()}")
        for (i in 0 until model.getChildCount(node)) {
            if (lines.size >= limit) break
            collectTreeText(model, model.getChild(node, i), lines, limit, depth + 1)
        }
    }
}
