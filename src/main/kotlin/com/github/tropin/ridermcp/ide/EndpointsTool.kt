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

class EndpointsToolset : McpToolset {

    @McpTool
    @McpDescription("Returns HTTP API endpoints (routes) detected by the IDE: HTTP method (GET/POST/PUT/DELETE), URL pattern, and handler location. Use to discover REST API routes, check available endpoints, or understand the API surface of the project.")
    suspend fun rider_get_endpoints(): String {
        val project = coroutineContext.project
        var result: String? = null
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val twm = ToolWindowManager.getInstance(project)
                val tw = twm.getToolWindow("Endpoints")
                    ?: mcpFail("Endpoints tool window not available. Open it first in Rider (View → Tool Windows → Endpoints).")

                val cm = tw.contentManager
                val content = cm.selectedContent ?: cm.contents.firstOrNull()
                    ?: mcpFail("Endpoints tool window has no content")

                val lines = mutableListOf<String>()
                extractEndpointText(content.component, lines, 500)

                if (lines.isEmpty()) mcpFail("No endpoints found. Make sure the project is indexed.")

                result = buildJsonObject {
                    put("count", lines.size)
                    putJsonArray("endpoints") { lines.forEach { add(it) } }
                }.toString()
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw it }
        return result!!
    }

    private fun extractEndpointText(component: java.awt.Component, lines: MutableList<String>, limit: Int) {
        if (lines.size >= limit) return
        when (component) {
            is javax.swing.JTree -> {
                val model = component.model ?: return
                val root = model.root ?: return
                collectTreeNodes(model, root, lines, limit, 0)
            }
            is javax.swing.JList<*> -> {
                val m = component.model
                for (i in 0 until m.size) {
                    if (lines.size >= limit) break
                    lines.add(m.getElementAt(i)?.toString() ?: "")
                }
            }
            is java.awt.Container -> {
                for (i in 0 until component.componentCount) {
                    if (lines.size >= limit) break
                    extractEndpointText(component.getComponent(i), lines, limit)
                }
            }
        }
    }

    private fun collectTreeNodes(model: javax.swing.tree.TreeModel, node: Any, lines: MutableList<String>, limit: Int, depth: Int) {
        if (lines.size >= limit) return
        val text = node.toString()
        if (depth > 0 && text.isNotBlank()) {
            lines.add(text)
        }
        for (i in 0 until model.getChildCount(node)) {
            if (lines.size >= limit) break
            collectTreeNodes(model, model.getChild(node, i), lines, limit, depth + 1)
        }
    }
}
