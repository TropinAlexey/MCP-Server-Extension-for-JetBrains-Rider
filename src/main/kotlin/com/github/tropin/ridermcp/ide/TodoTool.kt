package com.github.tropin.ridermcp.ide

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class TodoToolset : McpToolset {

    @McpTool
    @McpDescription("Returns TODO, FIXME, HACK comments found across the codebase (from the IDE's TODO tool window). Use to find technical debt, pending work items, or known issues in code. Default limit: 100.")
    suspend fun rider_get_todos(
        @McpDescription("Max items to return") limit: Int = 100
    ): String {
        val project = coroutineContext.project
        var result: String? = null
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val twm = ToolWindowManager.getInstance(project)
                val tw = twm.getToolWindow("TODO")
                    ?: mcpFail("TODO tool window not available")

                val cm = tw.contentManager
                val content = cm.selectedContent ?: cm.contents.firstOrNull()
                    ?: mcpFail("TODO tool window has no content")

                val lines = mutableListOf<String>()
                extractTreeText(content.component, lines, limit)

                result = if (lines.isEmpty()) "[]" else buildJsonObject {
                    put("count", lines.size)
                    if (lines.size >= limit) put("truncated", true)
                    putJsonArray("items") { lines.forEach { add(it) } }
                }.toString()
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw it }
        return result!!
    }

    private fun extractTreeText(component: java.awt.Component, lines: MutableList<String>, limit: Int) {
        if (lines.size >= limit) return
        when (component) {
            is javax.swing.JTree -> {
                val model = component.model ?: return
                val root = model.root ?: return
                collectNodes(model, root, lines, limit, 0)
            }
            is java.awt.Container -> {
                for (i in 0 until component.componentCount) {
                    if (lines.size >= limit) break
                    extractTreeText(component.getComponent(i), lines, limit)
                }
            }
        }
    }

    private fun collectNodes(model: javax.swing.tree.TreeModel, node: Any, lines: MutableList<String>, limit: Int, depth: Int) {
        if (lines.size >= limit) return
        val text = node.toString()
        if (depth > 0 && text.isNotBlank()) {
            lines.add(text)
        }
        for (i in 0 until model.getChildCount(node)) {
            if (lines.size >= limit) break
            collectNodes(model, model.getChild(node, i), lines, limit, depth + 1)
        }
    }
}
