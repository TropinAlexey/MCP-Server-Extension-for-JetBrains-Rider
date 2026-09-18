package com.github.tropin.ridermcp.ide

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.paginateLines
import com.github.tropin.ridermcp.projectDir
import com.github.tropin.ridermcp.relTo
import com.github.tropin.ridermcp.runOnEdt
import kotlin.coroutines.coroutineContext

class IdeStateToolset : McpToolset {

    @McpTool
    @McpDescription("Returns IDE activity status: progress indicators (indexing, building, analyzing), currently active/focused file, whether IDE is busy or idle. Use to check if IDE is ready before starting builds, tests, or refactoring.")
    suspend fun rider_get_ide_state(): String {
        val project = coroutineContext.project
        // FileEditorManager model reads require the EDT.
        val activeFile = runOnEdt {
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
        val notifications = runOnEdt {
            NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(Notification::class.java, project)
                .takeLast(limit)
        }

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
        return runOnEdt {
            val twm = ToolWindowManager.getInstance(project)
            buildJsonArray {
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
    }

    @McpTool
    @McpDescription("Lists tabs of an IDE tool window (debug sessions, run configurations, terminal tabs, etc.). Returns tab names and which one is selected. Use before rider_get_tool_window_content to choose the tab parameter.")
    suspend fun rider_list_tabs(
        @McpDescription("Tool window ID (from rider_list_tool_windows)") windowId: String
    ): String {
        val project = coroutineContext.project
        return runOnEdt {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(windowId)
                ?: mcpFail("Tool window '$windowId' not found")
            val cm = tw.contentManager
            if (cm.contentCount == 0) mcpFail("Tool window '$windowId' has no tabs. Open it in Rider first (View → Tool Windows → $windowId).")
            buildJsonObject {
                put("windowId", windowId)
                put("selected", cm.selectedContent?.displayName ?: "")
                putJsonArray("tabs") {
                    cm.contents.forEach { add(it.displayName ?: "") }
                }
            }.toString()
        }
    }

    @McpTool
    @McpDescription("Reads text content from any IDE tool window/panel (Build output, Problems, Debug, NuGet, Database, etc.). TIP: pass section to read one sub-tab only — for application logs use windowId='Debug', section='Console'. The default Debug view mixes app output with the debugger trace; section='Debug Output' returns only that trace (Loaded Assembly / Started|Exited Thread). Supports pagination, tail reading, and regex filtering. Response always includes totalLines so you know the full size. Use fromEnd=true for last N lines (errors, recent logs). Use pattern for regex grep (case-insensitive; matched lines prefixed with [lineNo]). Use rider_list_tabs to discover tab names. The response always includes availableSections so you don't have to guess section names.")
    suspend fun rider_get_tool_window_content(
        @McpDescription("Tool window ID") windowId: String,
        @McpDescription("Tab name (omit for active). Use rider_list_tabs to discover names") tab: String? = null,
        @McpDescription("Sub-section name (sub-tab title substring, case-insensitive). Debug examples: 'Console' for app stdout, 'Debug Output' for debugger trace. Omit for full content. See availableSections in the response for valid values.") section: String? = null,
        @McpDescription("Max output lines (default 200, 0 = unlimited)") maxLines: Int = 200,
        @McpDescription("Start from this line (0-based). Mutually exclusive with fromEnd (error if combined). Pages within pattern matches when pattern is set") offset: Int? = null,
        @McpDescription("Return last maxLines lines instead of first (default false). Best for Debug/Build logs where errors are at the end") fromEnd: Boolean = false,
        @McpDescription("Regex filter — return only matching lines (case-insensitive). E.g. 'error|exception|warn'") pattern: String? = null
    ): String {
        val project = coroutineContext.project
        return runOnEdt {
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
                val actualSections = mutableListOf<String>().also { collectTabTitles(component, it) }
                val availableSections = actualSections.toMutableList()
                if (isDebugWindow(windowId)) {
                    // Documented Debug sub-tabs even when they are not JBTabs in the Swing tree
                    // (the app console is fetched via the debugger API, not from Swing).
                    if (availableSections.none { it.equals("Console", ignoreCase = true) }) availableSections.add(0, "Console")
                    if (availableSections.none { it.equals("Debug Output", ignoreCase = true) }) availableSections.add("Debug Output")
                }

                val sectionTitle: String?
                val allLines = mutableListOf<String>()
                if (!section.isNullOrBlank()) {
                    val resolved = resolveSection(project, windowId, tab, component, section, actualSections)
                    sectionTitle = resolved.first
                    allLines.add("--- $sectionTitle ---")
                    allLines.addAll(resolved.second)
                } else {
                    sectionTitle = null
                    extractText(component, allLines, Int.MAX_VALUE)
                }

                val page = paginateLines(allLines, maxLines, offset, fromEnd, pattern)

                buildJsonObject {
                    put("windowId", windowId)
                    put("tab", content.displayName ?: "")
                    sectionTitle?.let { put("section", it) }
                    putJsonArray("availableSections") { availableSections.forEach { add(it) } }
                    put("totalLines", page.totalLines)
                    putJsonObject("returnedRange") {
                        put("from", page.returnedFrom)
                        put("to", page.returnedTo)
                    }
                    if (page.lines.isEmpty()) {
                        put("text", "(empty)")
                    } else {
                        put("text", page.lines.joinToString("\n"))
                        if (page.truncated) put("truncated", true)
                        page.matchedLines?.let { put("matchedLines", it) }
                    }
                    val tabs = cm.contents.map { it.displayName ?: "" }
                    if (tabs.size > 1) putJsonArray("otherTabs") { tabs.filter { it != (content.displayName ?: "") }.forEach { add(it) } }
                }.toString()
        }
    }

    private fun isDebugWindow(windowId: String) = windowId.equals("Debug", ignoreCase = true)

    // Resolves a named sub-section (sub-tab) inside tool window content.
    // Runs on EDT. Returns the section title and its already-extracted text lines.
    // actualSections are titles really found in the Swing tree — the error path
    // lists only those, never the synthetic Debug titles from availableSections.
    private fun resolveSection(
        project: Project,
        windowId: String,
        tab: String?,
        component: java.awt.Component,
        section: String,
        actualSections: List<String>
    ): Pair<String, List<String>> {
        val isConsoleQuery = isDebugWindow(windowId) && section.contains("console", ignoreCase = true)
        if (isConsoleQuery) {
            // The debugger's process console (app stdout) is not reliably a Swing sub-tab
            // of the Debug tool window content — fetch it via the debugger API first.
            // (RunContentManager lookup by processHandler identity can resolve to a wrong,
            // empty descriptor, so session.consoleView is the source of truth.)
            val lines = debugConsoleLines(project, tab)
            if (lines.any { it.isNotBlank() }) return "Console" to lines
            // Fall through to Swing search if the API console is empty — the real
            // stdout may still be reachable via merged-content traversal.
        }

        findSectionComponent(component, section)?.let { (title, target) ->
            val lines = mutableListOf<String>()
            extractText(target, lines, Int.MAX_VALUE)
            return title to lines
        }

        // "Debug Output" is advertised even when it is not a Swing sub-tab —
        // resolve it to the full-window extraction (debugger trace).
        if (!isConsoleQuery && isDebugWindow(windowId) && section.contains("output", ignoreCase = true)) {
            val lines = mutableListOf<String>()
            extractText(component, lines, Int.MAX_VALUE)
            return "Debug Output" to lines
        }

        if (isConsoleQuery) mcpFail("Debug console is empty — the session captured no app stdout")
        mcpFail("Section '$section' not found. Available: $actualSections")
    }

    // Depth-first search for a sub-tab whose title contains the query.
    // Supports both JBTabs (IntelliJ tab layout) and JTabbedPane (Swing tabs).
    private fun findSectionComponent(component: java.awt.Component, query: String): Pair<String, java.awt.Component>? {
        if (component is com.intellij.ui.tabs.JBTabs) {
            component.tabs.firstOrNull { it.text.contains(query, ignoreCase = true) }
                ?.let { return it.text to it.component }
            for (tabInfo in component.tabs) {
                findSectionComponent(tabInfo.component, query)?.let { return it }
            }
            return null
        }
        if (component is javax.swing.JTabbedPane) {
            for (i in 0 until component.tabCount) {
                if (component.getTitleAt(i).contains(query, ignoreCase = true)) {
                    return component.getTitleAt(i) to component.getComponentAt(i)
                }
            }
            for (i in 0 until component.tabCount) {
                findSectionComponent(component.getComponentAt(i), query)?.let { return it }
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
            is javax.swing.JTabbedPane -> {
                for (i in 0 until component.tabCount) out.add(component.getTitleAt(i))
                for (i in 0 until component.tabCount) collectTabTitles(component.getComponentAt(i), out)
            }
            is java.awt.Container -> {
                for (i in 0 until component.componentCount) collectTabTitles(component.getComponent(i), out)
            }
        }
    }

    // App stdout lines of a debug session, independent of UI layout.
    // Source of truth is XDebugSession.consoleView; its document is read directly
    // because Swing traversal of the console component can come up empty
    // (virtualized output, custom console implementations).
    private fun debugConsoleLines(project: Project, tab: String?): List<String> {
        val mgr = XDebuggerManager.getInstance(project)
        val session = if (tab != null) {
            mgr.debugSessions.firstOrNull { it.sessionName.equals(tab, ignoreCase = true) }
                ?: mcpFail("Debug session '$tab' not found. Active: ${mgr.debugSessions.map { it.sessionName }}")
        } else {
            mgr.currentSession ?: mcpFail("No active debug session")
        }

        // 1. Direct document text from the session console view.
        try {
            val consoleView = session.consoleView
            if (consoleView != null) {
                consoleTextFromConsole(consoleView)?.takeIf { it.isNotBlank() }?.let { return it.lines() }
                val swingLines = mutableListOf<String>()
                extractText(consoleView.component, swingLines, Int.MAX_VALUE)
                if (swingLines.any { it.isNotBlank() }) return swingLines
            }
        } catch (_: Throwable) {
            // fall through to RunContentManager lookup
        }

        // 2. Fallback: RunContentDescriptor with the same process handler.
        val handler = session.debugProcess.processHandler
        val descriptor = com.intellij.execution.ui.RunContentManager.getInstance(project).allDescriptors
            .firstOrNull { it.processHandler === handler }
            ?: mcpFail("Debug session '${session.sessionName}' has no process console. The app may use an external console window.")
        val console = descriptor.executionConsole
            ?: mcpFail("Debug session '${session.sessionName}' has no execution console")
        consoleTextFromConsole(console)?.takeIf { it.isNotBlank() }?.let { return it.lines() }
        val lines = mutableListOf<String>()
        extractText(console.component, lines, Int.MAX_VALUE)
        return lines
    }

    // Best-effort raw text of an ExecutionConsole without walking Swing children.
    // Uses reflection for getEditor()/getEditors() so no dependency on impl classes.
    private fun consoleTextFromConsole(console: ExecutionConsole): String? {
        // DuplexConsoleView (debug console + output): concatenate both sides.
        try {
            if (console.javaClass.name.contains("Duplex")) {
                val sb = StringBuilder()
                for (name in listOf("getPrimaryConsoleView", "getSecondaryConsoleView")) {
                    try {
                        val m = console.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                            ?: continue
                        val sub = m.invoke(console) as? ExecutionConsole ?: continue
                        consoleTextFromConsole(sub)?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                    } catch (_: Throwable) {
                    }
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
        } catch (_: Throwable) {
        }
        try {
            console.javaClass.methods.firstOrNull { it.name == "getEditor" && it.parameterCount == 0 }?.let { m ->
                try {
                    val editor = m.invoke(console) as? Editor ?: return@let
                    return ApplicationManager.getApplication().runReadAction<String> { editor.document.text }
                } catch (_: Throwable) {
                }
            }
            console.javaClass.methods.firstOrNull { it.name == "getEditors" && it.parameterCount == 0 }?.let { m ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    val editors = m.invoke(console) as? Array<Editor>
                    val parts = editors?.mapNotNull { ed ->
                        try {
                            ApplicationManager.getApplication().runReadAction<String> { ed.document.text }
                        } catch (_: Throwable) {
                            null
                        }
                    }?.filter { it.isNotBlank() }
                    if (!parts.isNullOrEmpty()) return parts.joinToString("\n")
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        return null
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
