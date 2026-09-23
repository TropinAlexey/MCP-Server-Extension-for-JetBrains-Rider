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
    @McpDescription("Lists live IDE-managed processes (builds, test runs, app runs started from Rider) with display name, inferred type, PID and command line. Optional type filter: 'build', 'test', 'run'. Use to find what is hanging and to resolve processName for rider_kill_process. For rider_* async sessions (with sessionId) prefer rider_get_ide_state.runningSessions + rider_get_output; for finished output use rider_tool_window windowId='Run'. Only non-terminated processes are listed.")
    suspend fun rider_list_processes(
        @McpDescription("Filter by inferred type: build, test, run (omit = all)") type: String? = null
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
    @McpDescription("Kills an IDE-managed process by exact display name from rider_list_processes (use it first — names must match exactly; confirm with the user before killing). Pass pid from the same listing to disambiguate when several processes share a name; without pid, an ambiguous name fails instead of killing the first match. Prefer the tool-native stop when applicable (rider_build action='cancel', rider_debug action='stop'). Fails when already terminated.")
    suspend fun rider_kill_process(
        @McpDescription("Process display name, exact match from rider_list_processes") processName: String,
        @McpDescription("Process ID from rider_list_processes (optional; required when the name is ambiguous)") pid: Long? = null
    ): String {
        val project = coroutineContext.project
        val descriptors = RunContentManager.getInstance(project).allDescriptors
        val candidates = descriptors.filter { d ->
            d.displayName == processName && d.processHandler?.let { !it.isProcessTerminated && !it.isProcessTerminating } == true
        }
        if (candidates.isEmpty()) mcpFail("Process '$processName' not found")
        val target = if (pid != null) {
            candidates.find { (it.processHandler as? OSProcessHandler)?.process?.pid() == pid }
                ?: mcpFail("Process '$processName' with pid $pid not found. Re-list with rider_list_processes — PIDs change on restart.")
        } else {
            if (candidates.size > 1) mcpFail("Ambiguous process name '$processName' (${candidates.size} live matches). Pass pid from rider_list_processes to pick one.")
            candidates.first()
        }

        val handler = target.processHandler
            ?: mcpFail("No process handler for '$processName'")

        if (handler.isProcessTerminated) mcpFail("Process already terminated")

        handler.destroyProcess()
        return "ok"
    }
}
