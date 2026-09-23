package com.github.tropin.ridermcp.profiling

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.jetbrains.rider.model.*
import com.jetbrains.rider.projectView.solution
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class ProfilingToolset : McpToolset {

    @McpTool
    @McpDescription(
        "dotTrace performance profiling: check status or control a session. " +
            "action='state' (default) — returns active session info, snapshots, errors. " +
            "action='control' — sends a command to the active session (command: start, stop, drop, detach, close; optional pid for multi-process). " +
            "Start profiling from Rider (Run → Profile) first."
    )
    suspend fun rider_profiling(
        @McpDescription("Action: state (default), control") action: String = "state",
        @McpDescription("Command for control action: start, stop, drop, detach, close") command: String? = null,
        @McpDescription("Process ID (for multi-process sessions)") pid: Int = 0
    ): String {
        return when (action.lowercase()) {
            "state" -> profilingState()
            "control" -> profilingControl(command, pid)
            else -> mcpFail("Unknown action '$action'. Use: state, control")
        }
    }

    private suspend fun profilingState(): String {
        val project = coroutineContext.project
        val host = project.solution.dotTraceHost

        return buildJsonObject {
            val session = host.activeSession.value
            if (session != null) {
                putJsonObject("activeSession") {
                    val config = session.configurationView
                    put("configName", config.name)
                    put("isFinished", session.sessionIsFinished.valueOrNull ?: false)
                    session.waitingForText.valueOrNull?.let { put("waitingFor", it) }

                    val processes = session.sessionProcesses.toList()
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

                    val snapshots = session.sessionSnapshots.toList()
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

                    val errors = session.errorMessages.toList()
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
            } else {
                put("activeSession", JsonNull)
            }

            val opened = host.openedSnapshots.toList()
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

            put("profilingDisabled", host.disableProfiling.valueOrNull ?: false)
        }.toString()
    }

    private suspend fun profilingControl(command: String?, pid: Int): String {
        if (command.isNullOrBlank()) mcpFail("command is required for action='control'. Use: start, stop, drop, detach, close")
        val project = coroutineContext.project
        val host = project.solution.dotTraceHost
        val session = host.activeSession.value
            ?: mcpFail("No active profiling session. Start profiling from Rider: Run → Profile.")

        val cmd = when (command.lowercase()) {
            "start" -> ProfilerCoreCommand.Start
            "stop" -> ProfilerCoreCommand.StopSave
            "drop" -> ProfilerCoreCommand.Drop
            "detach" -> ProfilerCoreCommand.Detach
            "close" -> ProfilerCoreCommand.Close
            else -> mcpFail("Unknown command '$command'. Use: start, stop, drop, detach, close")
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
