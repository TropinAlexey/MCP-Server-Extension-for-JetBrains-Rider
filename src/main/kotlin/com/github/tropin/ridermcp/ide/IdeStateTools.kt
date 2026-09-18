package com.github.tropin.ridermcp.ide

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.paginateLines
import com.github.tropin.ridermcp.projectDir
import com.github.tropin.ridermcp.relTo
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
        var result: String? = null
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val twm = ToolWindowManager.getInstance(project)
                result = buildJsonArray {
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
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw it }
        return result!!
    }

    @McpTool
    @McpDescription("Lists tabs of an IDE tool window (debug sessions, run configurations, terminal tabs, etc.). Returns tab names and which one is selected. Use before rider_get_tool_window_content to choose the tab parameter.")
    suspend fun rider_list_tabs(
        @McpDescription("Tool window ID (from rider_list_tool_windows)") windowId: String
    ): String {
        val project = coroutineContext.project
        var result: String? = null
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val tw = ToolWindowManager.getInstance(project).getToolWindow(windowId)
                    ?: mcpFail("Tool window '$windowId' not found")
                val cm = tw.contentManager
                if (cm.contentCount == 0) mcpFail("Tool window '$windowId' has no tabs. Open it in Rider first (View → Tool Windows → $windowId).")
                result = buildJsonObject {
                    put("windowId", windowId)
                    put("selected", cm.selectedContent?.displayName ?: "")
                    putJsonArray("tabs") {
                        cm.contents.forEach { add(it.displayName ?: "") }
                    }
                }.toString()
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw it }
        return result!!
    }

    @McpTool
    @McpDescription("Reads text content from any IDE tool window/panel (Build output, Problems, Debug, NuGet, Database, etc.). Supports pagination, tail reading, and regex filtering. Response always includes totalLines so you know the full size. Use fromEnd=true for last N lines (errors, recent logs). Use pattern for regex grep (case-insensitive; matched lines prefixed with [lineNo]). Use offset for random access to a specific range. Use rider_list_tabs to discover tab names. Use section to read one sub-tab only (e.g. section=console for the Debug window returns the debugged app's stdout instead of the debugger trace).")
    suspend fun rider_get_tool_window_content(
        @McpDescription("Tool window ID") windowId: String,
        @McpDescription("Tab name (omit for active). Use rider_list_tabs to discover names") tab: String? = null,
        @McpDescription("Sub-section name (sub-tab title substring, case-insensitive). E.g. 'console' for the Debug window reads the debugged process stdout; omit for full content") section: String? = null,
        @McpDescription("Max output lines (default 200, 0 = unlimited)") maxLines: Int = 200,
        @McpDescription("Start from this line (0-based). Mutually exclusive with fromEnd") offset: Int? = null,
        @McpDescription("Return last maxLines lines instead of first (default false). Best for Debug/Build logs where errors are at the end") fromEnd: Boolean = false,
        @McpDescription("Regex filter — return only matching lines (case-insensitive). E.g. 'error|exception|warn'") pattern: String? = null
    ): String {
        val project = coroutineContext.project
        var result: String? = null
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val tw = ToolWindowManager.getInstance(project).getToolWindow(windowId)
                    ?: mcpFail("Tool window '$windowId' not found")

                val cm = tw.contentManager
                val content = if (tab != null) {
                    cm.contents.firstOrNull { it.displayName.equals(tab, ignoreCase = true) }
                        ?: mcpFail("Tab '$tab' not found. Available: ${cm.contents.map { it.displayName }}")
                } else {
                    cm.selectedContent ?: cm.contents.firstOrNull()
                } ?: mcpFail("Tool window '$windowId' has no content. Open it in Rider first (View → Tool Windows → $windowId).")

                val component = content.component
                val (sectionTitle, targetComponent) = if (!section.isNullOrBlank()) {
                    resolveSection(project, windowId, tab, component, section)
                } else {
                    null to component
                }

                val allLines = mutableListOf<String>()
                if (sectionTitle != null) allLines.add("--- $sectionTitle ---")
                extractText(targetComponent, allLines, Int.MAX_VALUE)

                val page = paginateLines(allLines, maxLines, offset, fromEnd, pattern)

                result = buildJsonObject {
                    put("windowId", windowId)
                    put("tab", content.displayName ?: "")
                    sectionTitle?.let { put("section", it) }
                    put("totalLines", page.totalLines)
                    if (page.lines.isEmpty()) {
                        put("text", "(empty)")
                    } else {
                        putJsonObject("returnedRange") {
                            put("from", page.returnedFrom)
                            put("to", page.returnedTo)
                        }
                        put("text", page.lines.joinToString("\n"))
                        if (page.truncated) put("truncated", true)
                        page.matchedLines?.let { put("matchedLines", it) }
                    }
                    val tabs = cm.contents.map { it.displayName ?: "" }
                    if (tabs.size > 1) putJsonArray("otherTabs") { tabs.filter { it != (content.displayName ?: "") }.forEach { add(it) } }
                }.toString()
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw it }
        return result!!
    }

    // Resolves a named sub-section (sub-tab) inside tool window content.
    // Runs on EDT. Returns the section title and the component to extract text from.
    private fun resolveSection(
        project: Project,
        windowId: String,
        tab: String?,
        component: java.awt.Component,
        section: String
    ): Pair<String, java.awt.Component> {
        findSectionComponent(component, section)?.let { return it }

        // The debugger's process console (app stdout) is not always a Swing sub-tab
        // of the Debug tool window content — fetch it via the debugger API instead.
        if (windowId.equals("Debug", ignoreCase = true) && section.contains("console", ignoreCase = true)) {
            return "Console" to debugProcessConsole(project, tab)
        }

        val available = mutableListOf<String>()
        collectTabTitles(component, available)
        mcpFail("Section '$section' not found. Available: $available")
    }

    // Depth-first search for a JBTabs sub-tab whose title contains the query.
    private fun findSectionComponent(component: java.awt.Component, query: String): Pair<String, java.awt.Component>? {
        if (component is com.intellij.ui.tabs.JBTabs) {
            component.tabs.firstOrNull { it.text.contains(query, ignoreCase = true) }
                ?.let { return it.text to it.component }
            for (tabInfo in component.tabs) {
                findSectionComponent(tabInfo.component, query)?.let { return it }
            }
            return null
        }
        if (component is java.awt.Container) {
            for (i in 0 until component.componentCount) {
                findSectionComponent(component.getComponent(i), query)?.let { return it }
            }
        }
        return null
    }

    private fun collectTabTitles(component: java.awt.Component, out: MutableList<String>) {
        when (component) {
            is com.intellij.ui.tabs.JBTabs -> {
                component.tabs.forEach { out.add(it.text) }
                component.tabs.forEach { collectTabTitles(it.component, out) }
            }
            is java.awt.Container -> {
                for (i in 0 until component.componentCount) collectTabTitles(component.getComponent(i), out)
            }
        }
    }

    // Process console (stdout/stdin) of the active debug session, independent of UI layout.
    private fun debugProcessConsole(project: Project, tab: String?): java.awt.Component {
        val mgr = XDebuggerManager.getInstance(project)
        val session = if (tab != null) {
            mgr.debugSessions.firstOrNull { it.sessionName.equals(tab, ignoreCase = true) }
                ?: mcpFail("Debug session '$tab' not found. Active: ${mgr.debugSessions.map { it.sessionName }}")
        } else {
            mgr.currentSession ?: mcpFail("No active debug session")
        }
        val handler = session.debugProcess.processHandler
        val descriptor = com.intellij.execution.ui.RunContentManager.getInstance(project).allDescriptors
            .firstOrNull { it.processHandler === handler }
            ?: mcpFail("Debug session '${session.sessionName}' has no process console. The app may use an external console window.")
        return descriptor.executionConsole
            ?.component
            ?: mcpFail("Debug session '${session.sessionName}' has no execution console")
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
