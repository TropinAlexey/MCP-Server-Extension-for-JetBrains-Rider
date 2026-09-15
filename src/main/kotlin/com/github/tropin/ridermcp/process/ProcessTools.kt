package com.github.tropin.ridermcp.process

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.RunContentManager
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class ProcessToolset : McpToolset {

    @McpTool
    @McpDescription("Lists running processes launched by Rider (builds, tests, app runs) with PID and command line. Optional type filter: 'build', 'test', 'run'. Use to check what's running or find process names for rider_kill_process.")
    suspend fun rider_list_processes(
        @McpDescription("Filter by type: build, test, run") type: String? = null
    ): String {
        val project = coroutineContext.project
        val typeFilter = type?.lowercase()
        val processes = RunContentManager.getInstance(project).allDescriptors.mapNotNull { d ->
            val handler = d.processHandler ?: return@mapNotNull null
            if (handler.isProcessTerminated || handler.isProcessTerminating) return@mapNotNull null
            val name = d.displayName ?: "unknown"
            val nameLower = name.lowercase()
            val cmdLine = (handler as? OSProcessHandler)?.commandLine?.lowercase() ?: ""
            val inferredType = when {
                nameLower.contains("build") || cmdLine.contains("msbuild") -> "build"
                nameLower.contains("test") || cmdLine.contains("testhost") -> "test"
                else -> "run"
            }
            if (typeFilter != null && inferredType != typeFilter) return@mapNotNull null
            buildJsonObject {
                put("name", name)
                put("type", inferredType)
                if (handler is OSProcessHandler) {
                    try { put("pid", handler.process.pid()) } catch (_: Exception) {}
                    handler.commandLine?.let { put("commandLine", it) }
                }
            }
        }
        return JsonArray(processes).toString()
    }

    @McpTool
    @McpDescription("Kills/terminates a running process by its display name (from rider_list_processes). Use to stop a hung build, test, or running application.")
    suspend fun rider_kill_process(
        @McpDescription("Process display name") processName: String
    ): String {
        val project = coroutineContext.project
        val descriptors = RunContentManager.getInstance(project).allDescriptors
        val target = descriptors.find { d ->
            d.displayName == processName && d.processHandler?.let { !it.isProcessTerminated && !it.isProcessTerminating } == true
        } ?: mcpFail("Process '$processName' not found")

        val handler = target.processHandler
            ?: mcpFail("No process handler for '$processName'")

        if (handler.isProcessTerminated) mcpFail("Process already terminated")

        handler.destroyProcess()
        return "ok"
    }
}
