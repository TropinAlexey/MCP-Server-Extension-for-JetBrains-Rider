package com.github.tropin.ridermcp.permissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentPermissionConfigsTest {

    // Detection — only entries that point at the IDE MCP server count.

    private val claudeJson = """
        {"mcpServers": {"rider": {"type": "http", "url": "http://127.0.0.1:64482/stream"}, "other": {"command": "npx"},
                        "jetbrains": {"url": "http://127.0.0.1:9999/stream"}},
         "projects": {"/work/app": {"mcpServers": {"ide": {"command": "java", "env": {"IJ_MCP_SERVER_PORT": "64482"}}}}}}
    """.trimIndent()

    @Test
    fun `claude keys match this IDE's port, never the name`() {
        assertEquals(setOf("ide", "rider"), AgentPermissionConfigs.claudeServerKeys(claudeJson, "/work/app", 64482, includeProject = true))
    }

    @Test
    fun `user-wide claude keys skip project-local entries`() {
        assertEquals(setOf("rider"), AgentPermissionConfigs.claudeServerKeys(claudeJson, "/work/app", 64482, includeProject = false))
    }

    @Test
    fun `claude keys ignore other projects and broken files`() {
        val other = """{"projects": {"/other": {"mcpServers": {"rider": {"url": "http://127.0.0.1:64482/stream"}}}}}"""
        assertEquals(emptySet(), AgentPermissionConfigs.claudeServerKeys(other, "/work/app", 64482, includeProject = true))
        assertEquals(emptySet(), AgentPermissionConfigs.claudeServerKeys("{broken", "/work/app", 64482, includeProject = true))
    }

    // Claude Code — rules are only ever appended.

    @Test
    fun `read-only scope lists each tool, all scope the whole server`() {
        assertEquals(
            listOf("mcp__rider__rider_get_context", "mcp__rider__read_file"),
            AgentPermissionConfigs.claudeAllowRules(listOf("rider"), PermissionScope.READ_ONLY, listOf("rider_get_context", "read_file"))
        )
        assertEquals(listOf("mcp__my_ide"), AgentPermissionConfigs.claudeAllowRules(listOf("my.ide"), PermissionScope.ALL, emptyList()))
    }

    @Test
    fun `merge appends missing rules and keeps other settings`() {
        val settings = """{"model": "opus", "permissions": {"allow": ["Bash(ls)"], "deny": ["Read(.env)"]}}"""
        val merged = AgentPermissionConfigs.mergeClaudeSettings(settings, listOf("Bash(ls)", "mcp__rider"))!!
        assertTrue("\"model\": \"opus\"" in merged)
        assertTrue("\"Read(.env)\"" in merged)
        assertTrue(Regex("\"Bash\\(ls\\)\",\\s*\"mcp__rider\"").containsMatchIn(merged))
    }

    @Test
    fun `merge creates settings from nothing and is idempotent`() {
        val created = AgentPermissionConfigs.mergeClaudeSettings(null, listOf("mcp__rider"))!!
        assertNull(AgentPermissionConfigs.mergeClaudeSettings(created, listOf("mcp__rider")))
    }

    @Test
    fun `merge refuses to overwrite a file it cannot parse`() {
        assertFailsWith<IllegalArgumentException> { AgentPermissionConfigs.mergeClaudeSettings("{oops", listOf("mcp__rider")) }
    }

    // Codex — one line inside the server's own section.

    private val codexToml = """
        model = "gpt-5"

        [mcp_servers.rider]
        url = "http://127.0.0.1:64482/stream"

        [mcp_servers.rider.tools.read_file]
        approval_mode = "approve"

        [mcp_servers."docs"]
        command = "npx"

        [mcp_servers.jetbrains]
        url = "http://127.0.0.1:9999/stream"
    """.trimIndent()

    @Test
    fun `codex keys match the IDE section only`() {
        assertEquals(setOf("rider"), AgentPermissionConfigs.codexServerKeys(codexToml, 64482))
    }

    @Test
    fun `codex mode is inserted under the server header`() {
        val updated = AgentPermissionConfigs.setCodexApprovalMode(codexToml, "rider", "writes")!!
        assertTrue("[mcp_servers.rider]\ndefault_tools_approval_mode = \"writes\"\nurl =" in updated)
        assertNull(AgentPermissionConfigs.setCodexApprovalMode(updated, "rider", "writes"))
    }

    @Test
    fun `codex mode is replaced but approve is never narrowed`() {
        val prompt = codexToml.replaceFirst("url = ", "default_tools_approval_mode = \"prompt\"\nurl = ")
        val approved = AgentPermissionConfigs.setCodexApprovalMode(prompt, "rider", "approve")!!
        assertTrue("default_tools_approval_mode = \"approve\"" in approved && "\"prompt\"" !in approved)
        assertNull(AgentPermissionConfigs.setCodexApprovalMode(approved, "rider", "writes"))
    }

    @Test
    fun `codex leaves files without the section alone`() {
        assertNull(AgentPermissionConfigs.setCodexApprovalMode(codexToml, "missing", "writes"))
    }

    // Gemini CLI — trust on an existing entry only.

    @Test
    fun `gemini trust is set on the IDE entry`() {
        val settings = """{"theme": "dark", "mcpServers": {"rider": {"httpUrl": "http://127.0.0.1:64482/stream"}}}"""
        assertEquals(setOf("rider"), AgentPermissionConfigs.geminiServerKeys(settings, 64482))
        val updated = AgentPermissionConfigs.setGeminiTrust(settings, "rider")!!
        assertTrue("\"trust\": true" in updated && "\"theme\": \"dark\"" in updated)
        assertNull(AgentPermissionConfigs.setGeminiTrust(updated, "rider"))
        assertNull(AgentPermissionConfigs.setGeminiTrust(settings, "missing"))
    }
}
