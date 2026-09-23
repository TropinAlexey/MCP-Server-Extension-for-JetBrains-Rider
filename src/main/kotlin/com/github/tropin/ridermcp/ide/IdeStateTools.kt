package com.github.tropin.ridermcp.ide

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
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

// Hard cap on Swing/console text extraction per call: tool-window reads run on
// the EDT, so unbounded extraction would freeze the Rider UI on huge consoles.
private const val MAX_WINDOW_LINES = 20000

class IdeStateToolset : McpToolset {

    @McpTool
    @McpDescription("Returns IDE readiness: activeFile, indexing (true while Rider indexes — wait before builds/tests/refactoring), busy (indexing OR any rider_* async session still running), runningSessions (sessions started via rider_build/rider_tests/rider_nuget/rider_start_debug). Call before starting heavy work. Note: only tracks indexing globally + own sessions; manual user builds in the UI are not listed. For session logs use rider_get_output; for panel errors use rider_tool_window(windowId='Problems'); for TODOs use rider_tool_window(windowId='TODO'); for endpoints prefer the built-in service/symbol search with rider_tool_window(windowId='Endpoints') as fallback.")
    suspend fun rider_get_ide_state(): String {
        val project = coroutineContext.project
        val activeFile = runOnEdt {
            FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                val projectDir = project.projectDir()
                editor.virtualFile?.toNioPathOrNull()?.relTo(projectDir) ?: editor.virtualFile?.path
            }
        }
        val indexing = DumbService.getInstance(project).isDumb
        val running = com.github.tropin.ridermcp.SessionManager.running()

        return buildJsonObject {
            put("activeFile", activeFile ?: "none")
            put("indexing", indexing)
            put("busy", indexing || running.isNotEmpty())
            putJsonArray("runningSessions") {
                running.forEach { addJsonObject { put("id", it.id); put("type", it.type) } }
            }
        }.toString()
    }

    @McpTool
    @McpDescription(
        "Reads any IDE tool window (Build, Run, Debug, Problems, TODO, Terminal, Endpoints, Services, NuGet, Database, ...). " +
            "action='list' lists window IDs (all=true includes hidden). action='tabs' lists tabs of one window (requires windowId). " +
            "action='content' (default) reads text of a window (requires windowId; optional tab for multi-tab windows, section for a single sub-tab, maxLines/offset/fromEnd/pattern for paging). " +
            "Navigate: list → tabs → content. Every content response includes availableSections — use an entry from there for section instead of guessing. " +
            "Panels may show secrets/tokens (terminal scrollback, DB URLs, debug values) — do not paste into logs or chats. " +
            "Routing: app stdout of a debug session = windowId='Debug', section='Console'; debugger trace (assemblies/threads) = section='Debug Output' (NOT app logs); finished run output = windowId='Run' + tab name; live build/test/debug streams = rider_get_output(sessionId), panels are snapshots. " +
            "TIP: fromEnd=true for tails/errors, pattern for grep; Problems/TODO trees usually need no fromEnd."
    )
    suspend fun rider_tool_window(
        @McpDescription("Action: content (default) reads text, list lists window IDs, tabs lists tabs of one window") action: String = "content",
        @McpDescription("Tool window ID from action='list', e.g. 'Problems', 'Build', 'Run', 'Debug', 'TODO' (required for tabs/content)") windowId: String? = null,
        @McpDescription("Include hidden windows (list only, default false)") all: Boolean = false,
        @McpDescription("Tab display name from action='tabs' (content only; default = selected tab)") tab: String? = null,
        @McpDescription("Sub-tab/section name fragment, case-insensitive — use an entry from availableSections, e.g. 'Console' (content only)") section: String? = null,
        @McpDescription("Max lines for content (default 200, 0 = unlimited — avoid on huge windows)") maxLines: Int = 200,
        @McpDescription("Start line, 0-based, for paging (content only; mutually exclusive with fromEnd)") offset: Int? = null,
        @McpDescription("Return last maxLines lines — use for tails/errors (content only, default false)") fromEnd: Boolean = false,
        @McpDescription("Regex filter, case-insensitive, e.g. 'error|exception|warn' (content only)") pattern: String? = null
    ): String {
        return when (action.lowercase()) {
            "list" -> listToolWindows(all)
            "tabs" -> listTabs(windowId)
            "content" -> getToolWindowContent(windowId, tab, section, maxLines, offset, fromEnd, pattern)
            else -> mcpFail("Unknown action '$action'. Use: content, list, tabs")
        }
    }

    private suspend fun listToolWindows(all: Boolean): String {
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

    private suspend fun listTabs(windowId: String?): String {
        if (windowId.isNullOrBlank()) mcpFail("windowId is required for action='tabs'")
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

    private suspend fun getToolWindowContent(
        windowId: String?, tab: String?, section: String?,
        maxLines: Int, offset: Int?, fromEnd: Boolean, pattern: String?
    ): String {
        if (windowId.isNullOrBlank()) mcpFail("windowId is required for action='content'")
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
                    extractText(component, allLines, MAX_WINDOW_LINES)
                }
                if (allLines.size >= MAX_WINDOW_LINES) {
                    allLines.add("[truncated at $MAX_WINDOW_LINES lines — narrow with section/pattern/maxLines]")
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
            val lines = debugConsoleLines(project, tab)
            if (lines.any { it.isNotBlank() }) return "Console" to lines
        }

        findSectionComponent(component, section)?.let { (title, target) ->
            val lines = mutableListOf<String>()
            extractText(target, lines, MAX_WINDOW_LINES)
            return title to lines
        }

        if (!isConsoleQuery && isDebugWindow(windowId) && section.contains("output", ignoreCase = true)) {
            val lines = mutableListOf<String>()
            extractText(component, lines, MAX_WINDOW_LINES)
            return "Debug Output" to lines
        }

        if (isConsoleQuery) mcpFail("Debug console is empty — the session captured no app stdout")
        mcpFail("Section '$section' not found. Available: $actualSections")
    }

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

    private fun debugConsoleLines(project: Project, tab: String?): List<String> {
        val mgr = XDebuggerManager.getInstance(project)
        val session = if (tab != null) {
            mgr.debugSessions.firstOrNull { it.sessionName.equals(tab, ignoreCase = true) }
                ?: mcpFail("Debug session '$tab' not found. Active: ${mgr.debugSessions.map { it.sessionName }}")
        } else {
            mgr.currentSession ?: mcpFail("No active debug session")
        }

        try {
            val consoleView = session.consoleView
            if (consoleView != null) {
                consoleTextFromConsole(consoleView)?.takeIf { it.isNotBlank() }?.let { return it.lines().take(MAX_WINDOW_LINES) }
                val swingLines = mutableListOf<String>()
                extractText(consoleView.component, swingLines, MAX_WINDOW_LINES)
                if (swingLines.any { it.isNotBlank() }) return swingLines
            }
        } catch (_: Throwable) {
        }

        val handler = session.debugProcess.processHandler
        val descriptor = com.intellij.execution.ui.RunContentManager.getInstance(project).allDescriptors
            .firstOrNull { it.processHandler === handler }
            ?: mcpFail("Debug session '${session.sessionName}' has no process console. The app may use an external console window.")
        val console = descriptor.executionConsole
            ?: mcpFail("Debug session '${session.sessionName}' has no execution console")
        consoleTextFromConsole(console)?.takeIf { it.isNotBlank() }?.let { return it.lines().take(MAX_WINDOW_LINES) }
        val lines = mutableListOf<String>()
        extractText(console.component, lines, MAX_WINDOW_LINES)
        return lines
    }

    private fun consoleTextFromConsole(console: ExecutionConsole): String? {
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
            component is com.intellij.openapi.editor.impl.EditorComponentImpl -> {
                val text = component.editor.document.text
                if (text.isNotBlank()) text.lines().forEach { if (lines.size < limit) lines.add(it) }
            }
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
