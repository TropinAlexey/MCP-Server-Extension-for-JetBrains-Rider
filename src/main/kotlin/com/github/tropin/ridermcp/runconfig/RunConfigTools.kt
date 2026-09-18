package com.github.tropin.ridermcp.runconfig

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class RunConfigToolset : McpToolset {

    @McpTool
    @McpDescription("Creates a new run/debug configuration (launch profile). Specify typeId from get_run_configurations. Optional: env vars (comma-separated key=value pairs) and programArgs. Use to set up how the app is launched for running or debugging.")
    suspend fun rider_create_run_config(
        @McpDescription("Configuration name") name: String,
        @McpDescription("Configuration type ID") typeId: String,
        @McpDescription("Program arguments") programArgs: String? = null,
        @McpDescription("Environment variables, comma-separated key=value pairs") env: String? = null
    ): String {
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)

        if (runManager.allSettings.any { it.name == name }) {
            mcpFail("Configuration '$name' already exists. Use rider_update_run_config to modify.")
        }

        val configType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            .firstOrNull { it.id == typeId }
            ?: mcpFail("Unknown typeId '$typeId'. Use get_run_configurations to list available types.")

        val factory = configType.configurationFactories.firstOrNull()
            ?: mcpFail("No factory for type '$typeId'")

        val settings = runManager.createConfiguration(name, factory)
        val config = settings.configuration

        if (config is CommonProgramRunConfigurationParameters) {
            programArgs?.let { config.programParameters = it }
            env?.let { config.envs = parseEnvString(it) }
        } else if (programArgs != null || env != null) {
            mcpFail("Type '$typeId' doesn't support programArgs/env")
        }

        runManager.addConfiguration(settings)

        return buildJsonObject {
            put("created", name)
            put("type", configType.displayName)
        }.toString()
    }

    @McpTool
    @McpDescription("Updates an existing run/debug configuration: change program arguments or rename it.")
    suspend fun rider_update_run_config(
        @McpDescription("Configuration name") name: String,
        @McpDescription("New program arguments") programArgs: String? = null,
        @McpDescription("Environment variables, comma-separated key=value pairs") env: String? = null,
        @McpDescription("New name") newName: String? = null
    ): String {
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == name }
            ?: mcpFail("Configuration '$name' not found")

        val config = settings.configuration
        val changes = mutableListOf<String>()

        if (config is CommonProgramRunConfigurationParameters) {
            programArgs?.let { config.programParameters = it; changes.add("programArgs") }
            env?.let { config.envs = parseEnvString(it); changes.add("env") }
        } else if (programArgs != null || env != null) {
            mcpFail("This config type doesn't support programArgs/env")
        }

        newName?.let {
            if (runManager.allSettings.any { s -> s.name == it && s !== settings }) {
                mcpFail("Configuration '$it' already exists")
            }
            settings.name = it
            changes.add("renamed to '$it'")
        }

        if (changes.isEmpty()) mcpFail("Nothing to update. Pass programArgs or newName.")

        return buildJsonObject {
            put("updated", newName ?: name)
            putJsonArray("changes") { changes.forEach { add(it) } }
        }.toString()
    }

    @McpTool
    @McpDescription("Deletes a run/debug configuration by name. Use to clean up unused launch profiles.")
    suspend fun rider_delete_run_config(
        @McpDescription("Configuration name") name: String
    ): String {
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == name }
            ?: mcpFail("Configuration '$name' not found")

        runManager.removeConfiguration(settings)

        return buildJsonObject { put("deleted", name) }.toString()
    }

    private fun parseEnvString(env: String): Map<String, String> {
        if (env.isBlank()) return emptyMap()
        return env.split(",").associate { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) mcpFail("Invalid env entry '${pair.trim()}', expected key=value")
            pair.substring(0, idx).trim() to pair.substring(idx + 1).trim()
        }
    }
}
