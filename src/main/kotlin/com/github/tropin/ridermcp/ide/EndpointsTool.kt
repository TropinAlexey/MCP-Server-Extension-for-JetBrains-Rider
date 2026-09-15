package com.github.tropin.ridermcp.ide

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import com.github.tropin.ridermcp.projectDir
import com.github.tropin.ridermcp.relTo

class GetEndpointsTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "rider_get_endpoints"
    override val description = "Returns HTTP API endpoints (routes) detected by the IDE: HTTP method (GET/POST/PUT/DELETE), URL pattern, and handler location. Use to discover REST API routes, check available endpoints, or understand the API surface of the project."

    override fun handle(project: Project, args: NoArgs): Response {
        val twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val tw = twm.getToolWindow("Endpoints")
            ?: return Response(error = "Endpoints tool window not available. Open it first in Rider (View → Tool Windows → Endpoints).")

        val cm = tw.contentManager
        val content = cm.selectedContent ?: cm.contents.firstOrNull()
            ?: return Response(error = "Endpoints tool window has no content")

        val lines = mutableListOf<String>()
        extractEndpointText(content.component, lines, 500)

        if (lines.isEmpty()) return Response(error = "No endpoints found. Make sure the project is indexed.")

        val result = buildJsonObject {
            put("count", lines.size)
            putJsonArray("endpoints") { lines.forEach { add(it) } }
        }
        return Response(result.toString())
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
