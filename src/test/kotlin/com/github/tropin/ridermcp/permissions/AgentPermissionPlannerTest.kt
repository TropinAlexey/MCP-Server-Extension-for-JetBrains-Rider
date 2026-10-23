package com.github.tropin.ridermcp.permissions

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentPermissionPlannerTest {

    private val home: Path = Files.createTempDirectory("home")
    private val project: Path = Files.createTempDirectory("project")
    private val planner = AgentPermissionPlanner(project, home, listOf("rider_get_context"), idePort = 1)

    private fun put(path: Path, text: String) = path.apply { Files.createDirectories(parent) }.writeText(text)

    @Test
    fun `unconnected clients are reported and nothing is written`() {
        val edits = planner.plan(PermissionScope.READ_ONLY, claudeUserWide = false)
        assertEquals(listOf("Claude Code", "Codex", "Gemini CLI"), edits.map { it.client })
        assertTrue(edits.all { it.updated == null && "not connected" in it.status })
    }

    @Test
    fun `connected clients get project-local rules and an approval mode`() {
        put(home.resolve(".claude.json"), """{"mcpServers": {"rider": {"url": "http://127.0.0.1:1/stream"}}}""")
        put(home.resolve(".codex/config.toml"), "[mcp_servers.rider]\nurl = \"http://127.0.0.1:1/stream\"\n")
        val edits = planner.plan(PermissionScope.READ_ONLY, claudeUserWide = false).associateBy { it.client }

        val claude = edits.getValue("Claude Code")
        assertEquals(project.resolve(".claude/settings.local.json"), claude.path)
        edits.values.forEach(AgentPermissionPlanner::write)
        assertTrue("mcp__rider__rider_get_context" in claude.path.readText())
        assertTrue("default_tools_approval_mode = \"writes\"" in home.resolve(".codex/config.toml").readText())
        assertNull(edits.getValue("Gemini CLI").updated)

        val again = planner.plan(PermissionScope.READ_ONLY, claudeUserWide = false)
        assertTrue(again.all { it.updated == null })
    }

    @Test
    fun `servers named rider in repo files are ignored`() {
        put(project.resolve(".mcp.json"), """{"mcpServers": {"rider": {"url": "http://127.0.0.1:1/stream"}}}""")
        put(project.resolve(".codex/config.toml"), "[mcp_servers.rider]\nurl = \"http://127.0.0.1:1/stream\"\n")
        assertTrue(planner.plan(PermissionScope.ALL, claudeUserWide = false).all { it.updated == null })
    }

    @Test
    fun `gemini is trusted only for all tools`() {
        put(home.resolve(".gemini/settings.json"), """{"mcpServers": {"rider": {"httpUrl": "http://localhost:1/stream"}}}""")
        assertNull(planner.plan(PermissionScope.READ_ONLY, false).single { it.client == "Gemini CLI" }.updated)
        val all = planner.plan(PermissionScope.ALL, false).single { it.client == "Gemini CLI" }
        assertTrue("\"trust\": true" in all.updated!!)
    }
}
