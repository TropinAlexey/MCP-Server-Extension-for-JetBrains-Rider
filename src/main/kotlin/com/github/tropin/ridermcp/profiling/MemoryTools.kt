package com.github.tropin.ridermcp.profiling

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.jetbrains.rider.model.DotMemoryHost
import com.jetbrains.rider.model.DotMemoryHostSessionCommandType
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.projectView.solution
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class MemoryToolset : McpToolset {

    private fun dotMemoryHost(project: com.intellij.openapi.project.Project): DotMemoryHost? {
        return try {
            project.solution.getOrCreateExtension("dotMemoryHost", DotMemoryHost::class) {
                throw UnsupportedOperationException("dotMemoryHost extension not provided by the backend")
            }
        } catch (_: Exception) {
            null
        }
    }

    @McpTool
    @McpDescription(
        "dotMemory memory profiling: check status or control a session. " +
            "action='state' (default) — returns availability and active session info. " +
            "action='control' — sends a command (command: snapshot (requires pid), open (requires path), detach, kill). " +
            "Start memory profiling from Rider (Run → Profile with dotMemory) first."
    )
    suspend fun rider_memory(
        @McpDescription("Action: state (default), control") action: String = "state",
        @McpDescription("Command for control: snapshot, open, detach, kill") command: String? = null,
        @McpDescription("Process ID (required for snapshot)") pid: Int = 0,
        @McpDescription("Workspace path (required for open)") path: String? = null
    ): String {
        return when (action.lowercase()) {
            "state" -> memoryState()
            "control" -> memoryControl(command, pid, path)
            else -> mcpFail("Unknown action '$action'. Use: state, control")
        }
    }

    private suspend fun memoryState(): String {
        val project = coroutineContext.project
        val host = dotMemoryHost(project) ?: return """{"available":false}"""

        return buildJsonObject {
            put("available", host.isDotMemoryAvailable.valueOrNull ?: false)
            val session = host.profilingSession.value
            if (session != null) {
                putJsonObject("activeSession") {
                    put("isFinished", session.isFinished.valueOrNull ?: false)
                }
            } else {
                put("activeSession", JsonNull)
            }
        }.toString()
    }

    private suspend fun memoryControl(command: String?, pid: Int, path: String?): String {
        if (command.isNullOrBlank()) mcpFail("command is required for action='control'. Use: snapshot, open, detach, kill")
        val project = coroutineContext.project
        val host = dotMemoryHost(project) ?: mcpFail("dotMemory is not available in this IDE")

        when (command.lowercase()) {
            "snapshot" -> {
                if (pid == 0) mcpFail("snapshot requires pid. Use rider_list_processes to find it.")
                try {
                    host.getSnapshot.start(Lifetime.Eternal, pid)
                } catch (e: Exception) {
                    mcpFail("Failed to request snapshot: ${e.message}")
                }
            }
            "open" -> {
                if (path.isNullOrBlank()) mcpFail("open requires path to a .dmw workspace")
                host.importWorkspace.fire(path)
            }
            "detach", "kill" -> {
                val session = host.profilingSession.value
                    ?: mcpFail("No active memory profiling session. Start profiling from Rider first.")
                val cmd = if (command.lowercase() == "detach") {
                    DotMemoryHostSessionCommandType.Detach
                } else {
                    DotMemoryHostSessionCommandType.KillProcess
                }
                session.sessionCommand.fire(cmd)
            }
            else -> mcpFail("Unknown command '$command'. Use: snapshot, open, detach, kill")
        }

        return buildJsonObject {
            put("command", command.lowercase())
            if (pid != 0) put("pid", pid)
            path?.let { put("path", it) }
            put("sent", true)
        }.toString()
    }
}
