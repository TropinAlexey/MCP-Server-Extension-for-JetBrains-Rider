package com.github.tropin.ridermcp.permissions

import com.intellij.mcpserver.impl.McpServerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.JComponent

/**
 * Tools → Configure Agent Permissions: lets the agent clients connected to this IDE call its MCP
 * tools without a prompt. Client permissions belong to the client, so nothing is written until the
 * user reviews the planned edits and presses OK.
 */
class ConfigureAgentPermissionsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectDir = project.basePath?.let(Path::of) ?: project.guessProjectDir()?.toNioPath() ?: return
        val server = McpServerService.getInstance()
        if (!server.isRunning) {
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Agent permissions", "Enable the MCP server first: Settings → Tools → MCP Server.", NotificationType.WARNING)
                .notify(project)
            return
        }
        val planner = AgentPermissionPlanner(
            projectDir, Path.of(System.getProperty("user.home")), AgentPermissionPlanner.readOnlyToolNames(), server.port
        )
        val dialog = ConfigureAgentPermissionsDialog(project, planner)
        if (!dialog.showAndGet()) return

        val edits = dialog.plan().filter { it.updated != null }
        val failed = edits.mapNotNull { edit -> runCatching { AgentPermissionPlanner.write(edit) }.exceptionOrNull()?.let { edit to it } }
        LocalFileSystem.getInstance().refreshNioFiles(edits.map { it.path })

        val written = edits.filter { e -> failed.none { it.first == e } }
        val message = buildString {
            if (written.isEmpty() && failed.isEmpty()) append("Nothing to change — connected agents already have these permissions.")
            written.forEach { append("${it.client}: ${it.path}<br>") }
            failed.forEach { (edit, error) -> append("${edit.client}: failed to write ${edit.path} — ${StringUtil.escapeXmlEntities(error.message.orEmpty())}<br>") }
            if (written.isNotEmpty()) append("Restart the agent sessions to pick up the change.")
        }
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Agent permissions", message, if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
            .notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "MCP Server Extension"
    }
}

private class ConfigureAgentPermissionsDialog(project: Project, private val planner: AgentPermissionPlanner) : DialogWrapper(project) {

    private val readOnly = JBRadioButton("Read-only tools (recommended)", true)
    private val all = JBRadioButton("All tools of the IDE MCP server — includes built-in terminal, file edits and SQL")
    private val claudeProject = JBRadioButton("This project only (.claude/settings.local.json, not committed)", true)
    private val claudeUser = JBRadioButton("All projects (user settings.json — ~/.claude or CLAUDE_CONFIG_DIR)")
    private val preview = JBLabel()

    init {
        title = "Configure Agent Permissions"
        ButtonGroup().apply { add(readOnly); add(all) }
        ButtonGroup().apply { add(claudeProject); add(claudeUser) }
        listOf(readOnly, all, claudeProject, claudeUser).forEach { it.addActionListener { refresh() } }
        init()
        refresh()
    }

    fun plan(): List<PlannedEdit> =
        planner.plan(if (all.isSelected) PermissionScope.ALL else PermissionScope.READ_ONLY, claudeUser.isSelected)

    private fun refresh() {
        val edits = plan()
        preview.text = "<html>" + edits.joinToString("<br>") { edit ->
            val mark = if (edit.updated != null) "<b>will change</b>" else "no change"
            "<b>${edit.client}</b> — $mark: ${StringUtil.escapeXmlEntities(edit.status)}<br>&nbsp;&nbsp;<code>${StringUtil.escapeXmlEntities(edit.path.toString())}</code>"
        } + "</html>"
        isOKActionEnabled = edits.any { it.updated != null }
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            comment(
                "Agent clients ask before every MCP tool call until you allow it. These settings belong to the client: " +
                    "review the changes below — only rules are added, nothing is removed."
            )
        }
        group("Tools to Allow Without Asking") {
            row { cell(readOnly) }
            row { cell(all) }
        }
        group("Claude Code Rules") {
            row { cell(claudeProject) }
            row { cell(claudeUser) }
        }
        group("Planned Changes") {
            row { cell(preview) }
        }
        group("Other Clients (Set Up in the Client)") {
            row {
                comment(
                    "GitHub Copilot CLI: start with <code>--allow-tool '<server>'</code> (server name from its MCP config, e.g. <code>rider</code>).<br>" +
                        "OpenCode: add <code>\"permission\": {\"mcp_rider_\": \"allow\"}</code> to <code>~/.config/opencode/opencode.json</code> (global) or project <code>opencode.json</code>.<br>" +
                        "Continue: add <code>allow: [\"mcp_rider_*\"]</code> to <code>~/.continue/permissions.yaml</code> (global) or project <code>.continue/permissions.yaml</code>.<br>" +
                        "Zed: add <code>\"agent.tool_permissions.tools\": {\"mcp:rider:*\": {\"default\": \"allow\"}}</code> to <code>~/.config/zed/settings.json</code>.<br>" +
                        "Cline: configure MCP servers in <code>~/.cline/data/settings/cline_mcp_settings.json</code> (VS Code) or <code>~/.cline/mcp.json</code> (CLI); set tool permissions via MCP panel UI.<br>" +
                        "Cursor, VS Code, Windsurf, Junie and others: pick “Always allow” / “Allow for session” in the client's tool prompt or its MCP tool settings."
                )
            }
        }
    }
}
