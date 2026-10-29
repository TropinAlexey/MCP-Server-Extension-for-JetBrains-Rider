package com.github.tropin.ridermcp.profiling

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.jetbrains.rider.model.*
import com.jetbrains.rider.model.DotMemoryHost
import com.jetbrains.rider.model.DotMemoryHostSessionCommandType
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.projectView.solution
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class ProfilingToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Controls a live dotTrace/dotMemory profiling session. " +
            "action='state' (default) returns active session info for both CPU (dotTrace) and memory (dotMemory). " +
            "action='control' sends command: start/stop/drop/detach/close for dotTrace; snapshot/open/detach/kill for dotMemory (requires pid). " +
            "Start profiling from Rider (Run → Profile) first. " +
            "For dotMemory: use action='control' with command=snapshot (requires pid), open (requires .dmw path), detach, kill."
    )
    suspend fun rider_profiling(
        @McpDescription("Action: state (default) inspects, control sends a command") action: String = "state",
        @McpDescription("Control command: start/stop/drop/detach/close (dotTrace) OR snapshot/open/detach/kill (dotMemory)") command: String? = null,
        @McpDescription("Target process ID (required for dotMemory snapshot, optional for dotTrace in multi-process)") pid: Int = 0,
        @McpDescription("Workspace .dmw path (required for dotMemory open command)") path: String? = null
    ): String {
        return when (action.lowercase()) {
            "state" -> profilingState()
            "control" -> profilingControl(command, pid, path)
            else -> mcpFail("Unknown action '$action'. Use: state, control")
        }
    }

    private suspend fun profilingState(): String {
        val project = coroutineContext.project

        // dotTrace state
        val dotTraceHost = project.solution.dotTraceHost
        val dotTraceSession = dotTraceHost.activeSession.value

        // dotMemory state
        val dotMemoryHost = try {
            project.solution.getOrCreateExtension("dotMemoryHost", DotMemoryHost::class) {
                throw UnsupportedOperationException("dotMemoryHost extension not provided")
            }
        } catch (_: Exception) {
            null
        }
        val dotMemoryAvailable = dotMemoryHost?.isDotMemoryAvailable.valueOrNull ?: false
        val dotMemorySession = dotMemoryHost?.profilingSession.value

        return buildJsonObject {
            // dotTrace section
            if (dotTraceSession != null) {
                putJsonObject("dotTrace") {
                    val config = dotTraceSession.configurationView
                    put("configName", config.name)
                    put("isFinished", dotTraceSession.sessionIsFinished.valueOrNull ?: false)
                    dotTraceSession.waitingForText.valueOrNull?.let { put("waitingFor", it) }

                    val processes = dotTraceSession.sessionProcesses.toList()
                    if (processes.isNotEmpty()) {
                        putJsonArray("processes") {
                            processes.forEach { p ->
                                addJsonObject {
                                    put("pid", p.pid)
                                    p.name.valueOrNull?.let { put("name", it) }
                                    p.status.valueOrNull?.let { put("status", it.name) }
                                }
                            }
                        }
                    }

                    val snapshots = dotTraceSession.sessionSnapshots.toList()
                    if (snapshots.isNotEmpty()) {
                        putJsonArray("snapshots") {
                            snapshots.forEach { s ->
                                addJsonObject {
                                    put("pid", s.pid)
                                    s.process.valueOrNull?.let { put("process", it) }
                                    s.status.valueOrNull?.let { put("status", it.name) }
                                    s.path.valueOrNull?.let { put("path", it) }
                                    s.size.valueOrNull?.let { put("size", it) }
                                    s.duration.valueOrNull?.let { put("durationMs", it) }
                                }
                            }
                        }
                    }

                    val errors = dotTraceSession.errorMessages.toList()
                    if (errors.isNotEmpty()) {
                        putJsonArray("errors") {
                            errors.forEach { e ->
                                addJsonObject {
                                    put("pid", e.pid)
                                    put("message", e.message)
                                    if (e.critical) put("critical", true)
                                }
                            }
                        }
                    }
                }
            }

            // dotMemory section
            put("dotMemory", buildJsonObject {
                put("available", dotMemoryAvailable)
                if (dotMemorySession != null) {
                    putJsonObject("activeSession") {
                        put("isFinished", dotMemorySession.isFinished.valueOrNull ?: false)
                    }
                }
            })

            val opened = project.solution.dotTraceHost.openedSnapshots.toList()
            if (opened.isNotEmpty()) {
                putJsonArray("openedSnapshots") {
                    opened.forEach { s ->
                        addJsonObject {
                            put("path", s.path)
                            s.tabName.valueOrNull?.let { put("tab", it) }
                        }
                    }
                }
            }

            put("profilingDisabled", project.solution.dotTraceHost.disableProfiling.valueOrNull ?: false)
        }.toString()
    }

    private suspend fun profilingControl(command: String?, pid: Int, path: String?): String {
        if (command.isNullOrBlank()) mcpFail("command is required for action='control'")

        val project = coroutineContext.project

        // Check if it's a dotMemory command
        val isDotMemoryCmd = listOf("snapshot", "open", "detach", "kill").contains(command?.lowercase())

        if (isDotMemoryCmd) {
            val dotMemoryHost = try {
                project.solution.getOrCreateExtension("dotMemoryHost", DotMemoryHost::class.java) {
                    throw UnsupportedOperationException("dotMemoryHost not available")
                }
            } catch (_: Exception) {
                mcpFail("dotMemory is not available in this IDE")
            }

            val session = dotMemoryHost.profilingSession.value
                ?: mcpFail("No active memory profiling session. Start profiling from Rider first.")

            when (command?.lowercase()) {
                "snapshot" -> {
                    if (pid == 0) mcpFail("snapshot requires pid. Use rider_list_processes to find it.")
                    dotMemoryHost.getSnapshot.start(Lifetime.Eternal, pid)
                }
                "open" -> {
                    if (path.isNullOrBlank()) mcpFail("open requires path to a .dmw workspace")
                    if (!path.endsWith(".dmw", ignoreCase = true)) mcpFail("open requires a .dmw workspace file, got '$path'")
                    if (!java.io.File(path).exists()) mcpFail("Workspace file not found: '$path'")
                    dotMemoryHost.importWorkspace.fire(path)
                }
                "detach", "kill" -> {
                    val cmd = if (command.lowercase() == "detach") {
                        DotMemoryHostSessionCommandType.Detach
                    } else {
                        DotMemoryHostSessionCommandType.KillProcess
                    }
                    session.sessionCommand.fire(cmd)
                }
                else -> mcpFail("Unknown dotMemory command '$command'. Use: snapshot, open, detach, kill")
            }

            return buildJsonObject {
                put("command", command.lowercase())
                if (pid != 0) put("pid", pid)
                path?.let { put("path", it) }
                put("sent", true)
            }.toString()
        }

        // dotTrace commands
        val dotTraceHost = project.solution.dotTraceHost
        val session = dotTraceHost.activeSession.value
            ?: mcpFail("No active profiling session. Start profiling from Rider: Run → Profile.")

        val cmd = when (command?.lowercase()) {
            "start" -> ProfilerCoreCommand.Start
            "stop" -> ProfilerCoreCommand.StopSave
            "drop" -> ProfilerCoreCommand.Drop
            "detach" -> ProfilerCoreCommand.Detach
            "close" -> ProfilerCoreCommand.Close
            else -> mcpFail("Unknown command '$command'. Use: start, stop, drop, detach, close (dotTrace) or snapshot, open, detach, kill (dotMemory)")
        }

        if (pid != 0) {
            session.coreCommand.fire(CommandDef(cmd, pid))
        } else {
            session.sessionCommand.fire(cmd)
        }

        return buildJsonObject {
            put("command", command.lowercase())
            if (pid != 0) put("pid", pid)
            put("sent", true)
        }.toString()
    }
}
