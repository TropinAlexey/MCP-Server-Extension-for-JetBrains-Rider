package com.github.tropin.ridermcp.ide

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class GetTodosArgs(val limit: Int = 100)

class GetTodosTool : AbstractMcpTool<GetTodosArgs>(GetTodosArgs.serializer()) {
    override val name = "rider_get_todos"
    override val description = "Returns TODO, FIXME, HACK comments found across the codebase (from the IDE's TODO tool window). Use to find technical debt, pending work items, or known issues in code. Default limit: 100."

    override fun handle(project: Project, args: GetTodosArgs): Response {
        val twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val tw = twm.getToolWindow("TODO")
            ?: return Response(error = "TODO tool window not available")

        val cm = tw.contentManager
        val content = cm.selectedContent ?: cm.contents.firstOrNull()
            ?: return Response(error = "TODO tool window has no content")

        val lines = mutableListOf<String>()
        extractTreeText(content.component, lines, args.limit)

        if (lines.isEmpty()) return Response("[]")

        val result = buildJsonObject {
            put("count", lines.size)
            if (lines.size >= args.limit) put("truncated", true)
            putJsonArray("items") { lines.forEach { add(it) } }
        }
        return Response(result.toString())
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
