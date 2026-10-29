package com.github.tropin.ridermcp

import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.github.tropin.ridermcp.admin.AdminToolset
import com.github.tropin.ridermcp.analysis.SweaToolset
import com.github.tropin.ridermcp.context.ContextToolset
import com.github.tropin.ridermcp.ide.ConsoleJobToolset
import com.github.tropin.ridermcp.ide.DbConsolesToolset
import com.github.tropin.ridermcp.ide.IdeStateToolset
import com.github.tropin.ridermcp.nuget.NuGetToolset
import com.github.tropin.ridermcp.permissions.ConfigureAgentPermissionsAction
import com.github.tropin.ridermcp.process.ProcessToolset
import com.github.tropin.ridermcp.profiling.ProfilingToolset
import com.github.tropin.ridermcp.publish.PublishToolset
import com.github.tropin.ridermcp.runconfig.RunConfigToolset
import com.github.tropin.ridermcp.session.SessionOutputToolset
import com.github.tropin.ridermcp.settings.SettingsToolset
import com.github.tropin.ridermcp.testing.TestToolset
import com.github.tropin.ridermcp.workspace.WorkspaceConfigToolset
import org.junit.Test
import java.lang.reflect.Method
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ContractTest {

    private val toolsetClasses: List<Class<*>> = listOf(
        AdminToolset::class.java,
        SweaToolset::class.java,
        ContextToolset::class.java,
        ConsoleJobToolset::class.java,
        DbConsolesToolset::class.java,
        IdeStateToolset::class.java,
        NuGetToolset::class.java,
        ConfigureAgentPermissionsAction::class.java,
        ProcessToolset::class.java,
        ProfilingToolset::class.java,
        PublishToolset::class.java,
        RunConfigToolset::class.java,
        SessionOutputToolset::class.java,
        SettingsToolset::class.java,
        TestToolset::class.java,
        WorkspaceConfigToolset::class.java,
    )

    private fun mcToolMethods(clazz: Class<*>): List<java.lang.reflect.Method> =
        clazz.declaredMethods.filter { it.isAnnotationPresent(McpTool::class.java) }.toList()

    private fun allMcpToolMethods(): List<java.lang.reflect.Method> =
        toolsetClasses.flatMap { mcToolMethods(it) }

    @Test
    fun `toolset classes have rider_* methods with @McpTool`() {
        toolsetClasses.forEach { clazz ->
            val methods = mcToolMethods(clazz)
            if (methods.isEmpty()) {
            } else {
                methods.forEach { method ->
                    val name = method.name
                    assertTrue("Method '\$name' in \${clazz.simpleName} must start with 'rider_' or be 'actionPerformed'") {
                        name.startsWith("rider_") || name == "actionPerformed"
                    }
                }
            }
        }
    }

    @Test
    fun `readOnly tools have readOnlyHint and no outputFile or destructiveHint`() {
        allMcpToolMethods().forEach { method ->
            val hints = method.getAnnotation(McpToolHints::class.java)
            val isReadOnly = hints?.readOnlyHint == McpToolHintValue.TRUE
            val toolName = method.name
            
            if (isReadOnly) {
                val hasOutputFile = method.parameters.any { it.name == "outputFile" }
                val isDestructive = hints?.destructiveHint == McpToolHintValue.TRUE
                
                assertTrue("Read-only tool '\$toolName' must not have outputFile parameter") { !hasOutputFile }
                assertTrue("Read-only tool '\$toolName' must not be destructive") { !isDestructive }
            }
        }
    }

    @Test
    fun `destructiveTools have destructiveHint`() {
        val destructiveTools = setOf(
            "rider_kill_process", "rider_invalidate_caches", "rider_execute_console"
        )
        
        allMcpToolMethods().filter { it.name in destructiveTools }.forEach { method ->
            val hints = method.getAnnotation(McpToolHints::class.java)
            val isDestructive = hints?.destructiveHint == McpToolHintValue.TRUE
            assertTrue("Destructive tool '\${method.name}' must have destructiveHint = TRUE") { isDestructive }
        }
    }

    @Test
    fun `pagination parameters exist by position`() {
        allMcpToolMethods().filter { it.name == "rider_tool_window" }.forEach { method ->
            val params = method.parameters
            assertTrue("rider_tool_window must have at least 9 parameters (maxLines at pos 5)") { params.size >= 9 }
            assertTrue("rider_tool_window param 5 must be maxLines (Int)") { params[5].type == Int::class.java }
        }
        
        allMcpToolMethods().filter { it.name == "rider_get_output" }.forEach { method ->
            val params = method.parameters
            assertTrue("rider_get_output must have at least 6 parameters (sessionId at pos 0)") { params.size >= 6 }
            assertTrue("rider_get_output param 0 must be sessionId (String)") { params[0].type == String::class.java }
        }
    }

    @Test
    fun `polling tools use sessionId pattern`() {
        allMcpToolMethods().filter { it.name == "rider_get_output" }.forEach { method ->
            val params = method.parameters
            assertTrue("rider_get_output must have at least 6 parameters") { params.size >= 6 }
            assertTrue("rider_get_output param 0 must be sessionId (String)") { params[0].type == String::class.java }
        }
    }

    @Test
    fun `sessionBased tools exist`() {
        val sessionTools = setOf("rider_tests", "rider_nuget", "rider_publish")
        val found = allMcpToolMethods()
            .filter { it.name in sessionTools }
            .map { it.name }
            .toSet()
        
        assertTrue("Session-based tools found: \$found (expected: \$sessionTools)") { found == sessionTools }
    }

    @Test
    fun `asyncDb tools exist`() {
        val asyncTools = setOf("rider_execute_console", "rider_console_job")
        val found = allMcpToolMethods()
            .filter { it.name in asyncTools }
            .map { it.name }
            .toSet()
        
        assertTrue("Async DB tools found: \$found (expected: \$asyncTools)") { found == asyncTools }
    }
}