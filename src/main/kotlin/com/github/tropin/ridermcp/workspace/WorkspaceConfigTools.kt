package com.github.tropin.ridermcp.workspace

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.projectDir
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class WorkspaceConfigToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Read-only snapshot of the active workspace/build configuration: per-project runnable-project outputs (Configuration, Platform, TargetFramework, exePath, workingDirectory) and the last rider_* build/publish/test operation captured by this extension. " +
            "Use to diagnose phantom errors after Publish, mismatched RIDs, or design-time vs real-build property differences. " +
            "Does NOT start any builds; properties come from Rider's cached runnable-project model and may be stale while the backend is loading."
    )
    suspend fun rider_workspace_config(): String {
        val project = coroutineContext.project
        val projectDir = project.projectDir()

        val runnableModel = try {
            val solutionClass = Class.forName("com.jetbrains.rd.ide.model.Solution")
            val solution = Class.forName("com.jetbrains.rider.projectView.SolutionHostExtensionsKt")
                .getMethod("getSolution", Project::class.java)
                .invoke(null, project)
            solution?.let { s ->
                Class.forName("com.jetbrains.rider.model.RunnableProjectsModel_PregeneratedKt")
                    .getMethod("getRunnableProjectsModel", solutionClass)
                    .invoke(null, s) as? com.jetbrains.rider.model.RunnableProjectsModel
            }
        } catch (_: Throwable) { null }

        val projects = ApplicationManager.getApplication().runReadAction<List<JsonObject>> {
            runnableModel?.projects?.valueOrNull?.map { rp ->
                projectConfig(projectDir, rp)
            } ?: emptyList()
        }

        val lastOp = SessionManager.running().maxByOrNull { it.createdAt }?.let { running ->
            buildJsonObject {
                put("id", running.id)
                put("type", running.type)
                put("status", running.status)
            }
        } ?: SessionManager.listAll().filter { it.status != "running" }.maxByOrNull { it.createdAt }?.let { last ->
            buildJsonObject {
                put("id", last.id)
                put("type", last.type)
                put("status", last.status)
                last.metadata["configName"]?.let { put("configName", it) }
                last.metadata["configuration"]?.let { put("configuration", it) }
                last.metadata["platform"]?.let { put("platform", it) }
                last.metadata["runtimeIdentifier"]?.let { put("runtimeIdentifier", it) }
                last.metadata["publishDir"]?.let { put("publishDir", it) }
                last.metadata["publishProfile"]?.let { put("publishProfile", it) }
                last.metadata["pubxmlPath"]?.let { put("pubxmlPath", it) }
            }
        }

        return buildJsonObject {
            put("projectDir", projectDir?.toString() ?: "unknown")
            putJsonArray("projects") { projects.forEach { add(it) } }
            if (lastOp != null) put("lastOperation", lastOp)
        }.toString()
    }

    private fun projectConfig(projectDir: java.nio.file.Path?, rp: com.jetbrains.rider.model.RunnableProject): JsonObject {
        val outputs = rp.projectOutputs
        val output = outputs.firstOrNull()
        val cfg = output?.configuration
        val tfm = output?.tfm?.presentableName ?: ""

        return buildJsonObject {
            put("name", rp.name)
            put("projectFile", rp.projectFilePath)
            put("kind", rp.kind.toString())
            cfg?.let {
                put("configuration", it.configuration)
                put("platform", it.platform)
            }
            put("targetFramework", tfm)
            output?.let {
                put("exePath", it.exePath)
                put("workingDirectory", it.workingDirectory)
            }
            putJsonArray("outputs") {
                outputs.forEach { o ->
                    addJsonObject {
                        put("tfm", o.tfm?.presentableName ?: "")
                        put("configuration", o.configuration?.configuration ?: "")
                        put("platform", o.configuration?.platform ?: "")
                        put("exePath", o.exePath)
                    }
                }
            }
            putJsonObject("properties") {
                put("Configuration", cfg?.configuration ?: "")
                if (cfg?.platform?.isNotBlank() == true) put("Platform", cfg.platform)
                put("TargetFramework", tfm)
            }
        }
    }
}
