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
    @McpDescription(
        "CRUD for IDE run/debug configurations (does NOT launch anything — launch with rider_start_debug, rider_tests, or the built-in execute tools). " +
            "action='create' (default) requires name + typeId (type IDs come from the built-in get_run_configurations tool, if exposed — otherwise pick from IDE Run → Edit Configurations); optional programArgs/env for runnable types (single-factory types only; env values with commas must be double-quoted). " +
            "action='update' requires name; optional programArgs/env/newName (at least one required). " +
            "action='delete' removes the config by exact name (confirm with the user — re-creation is manual)."
    )
    suspend fun rider_run_config(
        @McpDescription("Action: create (default), update, delete") action: String = "create",
        @McpDescription("Configuration name, exact match (required for all actions)") name: String,
        @McpDescription("Configuration type ID from built-in get_run_configurations (required for create only; fallback: IDE Run → Edit Configurations)") typeId: String? = null,
        @McpDescription("Program arguments (runnable configs only)") programArgs: String? = null,
        @McpDescription("Environment variables as comma-separated key=value pairs, e.g. 'A=1,B=2' (runnable configs only)") env: String? = null,
        @McpDescription("New name for rename (update only)") newName: String? = null
    ): String {
        return when (action.lowercase()) {
            "create" -> createConfig(name, typeId, programArgs, env)
            "update" -> updateConfig(name, programArgs, env, newName)
            "delete" -> deleteConfig(name)
            else -> mcpFail("Unknown action '$action'. Use: create, update, delete")
        }
    }

    private suspend fun createConfig(name: String, typeId: String?, programArgs: String?, env: String?): String {
        if (typeId.isNullOrBlank()) mcpFail("typeId is required for action='create'")
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)

        if (runManager.allSettings.any { it.name == name }) {
            mcpFail("Configuration '$name' already exists. Use action='update' to modify.")
        }

        val configType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            .firstOrNull { it.id == typeId }
            ?: mcpFail("Unknown typeId '$typeId'. Use the built-in get_run_configurations to list available types (or IDE Run → Edit Configurations).")

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

    private suspend fun updateConfig(name: String, programArgs: String?, env: String?, newName: String?): String {
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

        if (changes.isEmpty()) mcpFail("Nothing to update. Pass programArgs, env, or newName.")

        return buildJsonObject {
            put("updated", newName ?: name)
            putJsonArray("changes") { changes.forEach { add(it) } }
        }.toString()
    }

    private suspend fun deleteConfig(name: String): String {
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == name }
            ?: mcpFail("Configuration '$name' not found")

        runManager.removeConfiguration(settings)

        return buildJsonObject { put("deleted", name) }.toString()
    }

    private fun parseEnvString(env: String): Map<String, String> = parseRunConfigEnv(env)
}

// Comma-separated key=value pairs; a value containing commas must be
// double-quoted ("KEY=a,b"). Quotes are stripped, whitespace trimmed.
// Top-level internal for unit tests — behavior contract, do not weaken.
internal fun parseRunConfigEnv(env: String): Map<String, String> {
    if (env.isBlank()) return emptyMap()
    val pairs = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    for (ch in env) {
        when {
            ch == '"' -> { inQuotes = !inQuotes; cur.append(ch) }
            ch == ',' && !inQuotes -> { pairs.add(cur.toString()); cur.clear() }
            else -> cur.append(ch)
        }
    }
    pairs.add(cur.toString())
    return pairs.associate { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0) mcpFail("Invalid env entry '${pair.trim()}', expected key=value (quote values with commas: KEY=\"a,b\")")
        val key = pair.substring(0, idx).trim()
        var value = pair.substring(idx + 1).trim()
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value = value.substring(1, value.length - 1)
        }
        key to value
    }
}
