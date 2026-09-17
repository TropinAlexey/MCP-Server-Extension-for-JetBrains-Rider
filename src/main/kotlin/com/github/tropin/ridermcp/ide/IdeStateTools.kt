package com.github.tropin.ridermcp.ide

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.TruncateMode
import com.github.tropin.ridermcp.parseTruncateMode
import com.github.tropin.ridermcp.projectDir
import com.github.tropin.ridermcp.relTo
import com.github.tropin.ridermcp.truncateLines
import kotlin.coroutines.coroutineContext

class IdeStateToolset : McpToolset {

    @McpTool
    @McpDescription("Returns IDE activity status: progress indicators (indexing, building, analyzing), currently active/focused file, whether IDE is busy or idle. Use to check if IDE is ready before starting builds, tests, or refactoring.")
    suspend fun rider_get_ide_state(): String {
        val project = coroutineContext.project
        val activeFile = readAction {
            FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                val projectDir = project.projectDir()
                editor.virtualFile?.toNioPathOrNull()?.relTo(projectDir) ?: editor.virtualFile?.path
            }
        }

        return buildJsonObject {
            put("activeFile", activeFile ?: "none")
        }.toString()
    }

    @McpTool
    @McpDescription("Returns recent IDE notifications (errors, warnings, info messages). Use to check for build errors, plugin updates, indexing issues, or any IDE alerts. Default limit: 5, pass limit for more.")
    suspend fun rider_get_notifications(
        @McpDescription("Max notifications to return") limit: Int = 5
    ): String {
        val project = coroutineContext.project
        val notifications = NotificationsManager.getNotificationsManager()
            .getNotificationsOfType(Notification::class.java, project)
            .takeLast(limit)

        return buildJsonArray {
            notifications.forEach { n ->
                addJsonObject {
                    n.title.takeIf { it.isNotEmpty() }?.let { put("title", it) }
                    n.content.takeIf { it.isNotEmpty() }?.let { put("content", it) }
                    put("type", n.type.name)
                    put("group", n.groupId)
                }
            }
        }.toString()
    }

    @McpTool
    @McpDescription("Lists IDE tool windows (panels/panes like Terminal, Build, Debug, NuGet, TODO, Problems, etc.). By default only visible ones; pass all=true to discover all available panels. Use to find windowId for rider_get_tool_window_content.")
    suspend fun rider_list_tool_windows(
        @McpDescription("Show all tool windows, not just visible") all: Boolean = false
    ): String {
        val project = coroutineContext.project
        val twm = ToolWindowManager.getInstance(project)
        return buildJsonArray {
            twm.toolWindowIds.forEach { id ->
                val tw = twm.getToolWindow(id) ?: return@forEach
                if (!all && !tw.isVisible) return@forEach
                addJsonObject {
                    put("id", id)
                    if (all) put("visible", tw.isVisible)
                    if (tw.isActive) put("active", true)
                }
            }
        }.toString()
    }

    @McpTool
    @McpDescription("Reads text content from any IDE tool window/panel (Build output, Problems, NuGet, Database, etc.). Extracts text from editors, consoles, trees, and lists. Use to read build logs, error lists, or any panel content. Pass tab name for a specific tab; omit for the active one. maxLines caps output (default 200). truncateMode controls which part to keep: START trims beginning (returns last N lines — best for Debug/Build logs where errors are at the end), END trims end (returns first N lines, default), MIDDLE keeps head+tail, NONE returns everything.")
    suspend fun rider_get_tool_window_content(
        @McpDescription("Tool window ID") windowId: String,
        @McpDescription("Tab name (omit for active)") tab: String? = null,
        @McpDescription("Max output lines") maxLines: Int = 200,
        @McpDescription("Which part to truncate: START (keep tail), END (keep head, default), MIDDLE (keep head+tail), NONE") truncateMode: String = "END"
    ): String {
        val project = coroutineContext.project
        val tw = ToolWindowManager.getInstance(project).getToolWindow(windowId)
            ?: mcpFail("Tool window '$windowId' not found")

        val cm = tw.contentManager
        val content = if (tab != null) {
            cm.contents.firstOrNull { it.displayName.equals(tab, ignoreCase = true) }
                ?: mcpFail("Tab '$tab' not found. Available: ${cm.contents.map { it.displayName }}")
        } else {
            cm.selectedContent ?: cm.contents.firstOrNull()
        } ?: mcpFail("Tool window '$windowId' has no content")

        val mode = parseTruncateMode(truncateMode, TruncateMode.END)
        val allLines = mutableListOf<String>()
        val component = content.component
        extractText(component, allLines, Int.MAX_VALUE)

        val (lines, truncated) = truncateLines(allLines, maxLines, mode)

        return buildJsonObject {
            put("windowId", windowId)
            put("tab", content.displayName ?: "")
            if (lines.isEmpty()) {
                put("text", "(empty)")
            } else {
                put("text", lines.joinToString("\n"))
                if (truncated) put("truncated", true)
                if (truncated) put("totalLines", allLines.size)
            }
            val tabs = cm.contents.map { it.displayName ?: "" }
            if (tabs.size > 1) putJsonArray("otherTabs") { tabs.filter { it != (content.displayName ?: "") }.forEach { add(it) } }
        }.toString()
    }

    private fun extractText(component: java.awt.Component, lines: MutableList<String>, limit: Int) {
        if (lines.size >= limit) return
        when {
            // EditorComponentImpl is the actual Swing wrapper; Editor interface is not a Component
            component is com.intellij.openapi.editor.impl.EditorComponentImpl -> {
                val text = component.editor.document.text
                if (text.isNotBlank()) text.lines().forEach { if (lines.size < limit) lines.add(it) }
            }
            // JBTabs only exposes selected tab as Swing child; iterate all tabs explicitly
            component is com.intellij.ui.tabs.JBTabs -> {
                for (tabInfo in component.tabs) {
                    if (lines.size >= limit) break
                    val before = lines.size
                    extractText(tabInfo.component, lines, limit)
                    if (lines.size > before) {
                        lines.add(before, "--- ${tabInfo.text} ---")
                    }
                }
            }
            component is javax.swing.JTree -> {
                val model = component.model ?: return
                val root = model.root ?: return
                collectTreeText(model, root, lines, limit, 0)
            }
            component is javax.swing.JList<*> -> {
                val m = component.model
                for (i in 0 until m.size) {
                    if (lines.size >= limit) break
                    lines.add(m.getElementAt(i)?.toString() ?: "")
                }
            }
            component is javax.swing.text.JTextComponent -> {
                val text = component.text
                if (!text.isNullOrBlank()) text.lines().forEach { if (lines.size < limit) lines.add(it) }
            }
            component is java.awt.Container -> {
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
