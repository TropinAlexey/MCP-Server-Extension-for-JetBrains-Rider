package com.github.tropin.ridermcp.runconfig

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class CreateRunConfigArgs(
    val name: String,
    val typeId: String,
    val env: Map<String, String>? = null,
    val programArgs: String? = null
)

class CreateRunConfigTool : AbstractMcpTool<CreateRunConfigArgs>(CreateRunConfigArgs.serializer()) {
    override val name = "rider_create_run_config"
    override val description = "Creates a run configuration. typeId: use stock get_run_configurations to see available types. Optional: env (map), programArgs."

    override fun handle(project: Project, args: CreateRunConfigArgs): Response {
        val runManager = RunManager.getInstance(project)

        if (runManager.allSettings.any { it.name == args.name }) {
            return Response(error = "Configuration '${args.name}' already exists. Use rider_update_run_config to modify.")
        }

        val configType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            .firstOrNull { it.id == args.typeId }
            ?: return Response(error = "Unknown typeId '${args.typeId}'. Use get_run_configurations to list available types.")

        val factory = configType.configurationFactories.firstOrNull()
            ?: return Response(error = "No factory for type '${args.typeId}'")

        val settings = runManager.createConfiguration(args.name, factory)
        val config = settings.configuration

        if (config is CommonProgramRunConfigurationParameters) {
            args.env?.let { config.envs = it }
            args.programArgs?.let { config.programParameters = it }
        } else {
            if (args.env != null || args.programArgs != null) {
                return Response(error = "Type '${args.typeId}' doesn't support env/programArgs")
            }
        }

        runManager.addConfiguration(settings)

        val result = buildJsonObject {
            put("created", args.name)
            put("type", configType.displayName)
        }
        return Response(result.toString())
    }
}

@Serializable
data class UpdateRunConfigArgs(
    val name: String,
    val env: Map<String, String>? = null,
    val programArgs: String? = null,
    val newName: String? = null
)

class UpdateRunConfigTool : AbstractMcpTool<UpdateRunConfigArgs>(UpdateRunConfigArgs.serializer()) {
    override val name = "rider_update_run_config"
    override val description = "Updates a run configuration: env vars, program args, or rename."

    override fun handle(project: Project, args: UpdateRunConfigArgs): Response {
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == args.name }
            ?: return Response(error = "Configuration '${args.name}' not found")

        val config = settings.configuration
        val changes = mutableListOf<String>()

        if (config is CommonProgramRunConfigurationParameters) {
            args.env?.let { config.envs = it; changes.add("env") }
            args.programArgs?.let { config.programParameters = it; changes.add("programArgs") }
        } else if (args.env != null || args.programArgs != null) {
            return Response(error = "This config type doesn't support env/programArgs")
        }

        args.newName?.let {
            if (runManager.allSettings.any { s -> s.name == it && s !== settings }) {
                return Response(error = "Configuration '$it' already exists")
            }
            settings.name = it
            changes.add("renamed to '$it'")
        }

        if (changes.isEmpty()) return Response(error = "Nothing to update. Pass env, programArgs, or newName.")

        val result = buildJsonObject {
            put("updated", args.newName ?: args.name)
            putJsonArray("changes") { changes.forEach { add(it) } }
        }
        return Response(result.toString())
    }
}

@Serializable
data class DeleteRunConfigArgs(val name: String)

class DeleteRunConfigTool : AbstractMcpTool<DeleteRunConfigArgs>(DeleteRunConfigArgs.serializer()) {
    override val name = "rider_delete_run_config"
    override val description = "Deletes a run configuration by name."

    override fun handle(project: Project, args: DeleteRunConfigArgs): Response {
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == args.name }
            ?: return Response(error = "Configuration '${args.name}' not found")

        runManager.removeConfiguration(settings)

        return Response(buildJsonObject { put("deleted", args.name) }.toString())
    }
}
