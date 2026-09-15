package com.github.tropin.ridermcp.process

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import com.github.tropin.ridermcp.mcpJson

@Serializable
data class ListProcessesArgs(val type: String? = null)

class ListProcessesTool : AbstractMcpTool<ListProcessesArgs>(ListProcessesArgs.serializer()) {
    override val name = "rider_list_processes"
    override val description = "Lists running processes launched by Rider (builds, tests, app runs) with PID and command line. Optional type filter: 'build', 'test', 'run'. Use to check what's running or find process names for rider_kill_process."

    override fun handle(project: Project, args: ListProcessesArgs): Response {
        val typeFilter = args.type?.lowercase()
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
        return Response(mcpJson.encodeToString(JsonArray(processes)))
    }
}

@Serializable
data class KillProcessArgs(val processName: String)

class KillProcessTool : AbstractMcpTool<KillProcessArgs>(KillProcessArgs.serializer()) {
    override val name = "rider_kill_process"
    override val description = "Kills/terminates a running process by its display name (from rider_list_processes). Use to stop a hung build, test, or running application."

    override fun handle(project: Project, args: KillProcessArgs): Response {
        val descriptors = RunContentManager.getInstance(project).allDescriptors
        val target = descriptors.find { d ->
            d.displayName == args.processName && d.processHandler?.let { !it.isProcessTerminated && !it.isProcessTerminating } == true
        } ?: return Response(error = "Process '${args.processName}' not found")

        val handler = target.processHandler
            ?: return Response(error = "No process handler for '${args.processName}'")

        if (handler.isProcessTerminated) return Response(error = "Process already terminated")

        handler.destroyProcess()
        return Response("ok")
    }
}
