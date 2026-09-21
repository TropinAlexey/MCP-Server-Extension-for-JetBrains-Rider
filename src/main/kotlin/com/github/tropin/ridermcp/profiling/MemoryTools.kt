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

    // The generated `solution.dotMemoryHost` extension only exists in newer
    // models, so resolve the host the same way generated code does. The factory
    // never runs when the backend provides the extension; if it doesn't, we
    // fail gracefully into "not available" instead of fabricating a host.
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
    @McpDescription("Returns dotMemory memory profiling state: whether dotMemory is available, and whether a memory profiling session is active or finished. Use to check if memory profiling is running before taking snapshots. The stock tools only analyze dotTrace snapshots — live memory sessions are visible only here.")
    suspend fun rider_memory_state(): String {
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

    @McpTool
    @McpDescription("Controls an active dotMemory profiling session. Commands: 'snapshot' (collect a memory snapshot for the process, requires pid), 'open' (open a saved .dmw workspace, requires path), 'detach' (detach profiler, process keeps running), 'kill' (kill the profiled process — destructive, use to stop a hung profiled app). Use rider_memory_state first to check session status. Start memory profiling from Rider (Run → Profile with dotMemory).")
    suspend fun rider_memory_control(
        @McpDescription("Command: snapshot, open, detach, kill") command: String,
        @McpDescription("Process ID (required for snapshot)") pid: Int = 0,
        @McpDescription("Workspace path (required for open)") path: String? = null
    ): String {
        val project = coroutineContext.project
        val host = dotMemoryHost(project) ?: mcpFail("dotMemory is not available in this IDE")

        when (command.lowercase()) {
            "snapshot" -> {
                if (pid == 0) mcpFail("snapshot requires pid. Use rider_memory_state or rider_list_processes to find it.")
                try {
                    // start(TReq) is deprecated in favor of the lifetime overload;
                    // Eternal matches the old default (fire-and-forget snapshot request).
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
