package com.github.tropin.ridermcp.runconfig

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.jetbrains.rider.run.configurations.RiderConfigurationParametersAware
import com.jetbrains.rider.run.configurations.exe.ExeConfigurationParameters
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class RunConfigToolset : McpToolset {

    @McpTool
    @McpDescription(
        "CRUD for IDE run/debug configurations (does NOT launch anything — launch with rider_start_debug, rider_tests, or the built-in execute tools). " +
            "action='create' (default) requires name + either copyFrom (an existing config to clone — the way to get a .NET project config with its project set, e.g. copyFrom='MyApp') or typeId (an unknown typeId lists the valid ones); optional programArgs/env for runnable and .NET project/exe configs (env values with commas must be double-quoted). " +
            "action='update' requires name; optional programArgs/env/newName (at least one required). " +
            "action='delete' removes the config by exact name (confirm with the user — re-creation is manual)."
    )
    suspend fun rider_run_config(
        @McpDescription("Action: create (default), update, delete") action: String = "create",
        @McpDescription("Configuration name, exact match (required for all actions)") name: String,
        @McpDescription("Configuration type ID, e.g. 'DotNetProject' (create without copyFrom; an unknown value lists valid IDs)") typeId: String? = null,
        @McpDescription("Existing configuration to clone for create (keeps its project, framework, working dir); programArgs/env then override") copyFrom: String? = null,
        @McpDescription("Program arguments (runnable configs only)") programArgs: String? = null,
        @McpDescription("Environment variables as comma-separated key=value pairs, e.g. 'A=1,B=2' (runnable configs only)") env: String? = null,
        @McpDescription("New name for rename (update only)") newName: String? = null
    ): String {
        return when (action.lowercase()) {
            "create" -> createConfig(name, typeId, copyFrom, programArgs, env)
            "update" -> updateConfig(name, programArgs, env, newName)
            "delete" -> deleteConfig(name)
            else -> mcpFail("Unknown action '$action'. Use: create, update, delete")
        }
    }

    private suspend fun createConfig(name: String, typeId: String?, copyFrom: String?, programArgs: String?, env: String?): String {
        if (typeId.isNullOrBlank() && copyFrom.isNullOrBlank()) mcpFail("action='create' needs copyFrom (existing config to clone) or typeId")
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)

        if (runManager.allSettings.any { it.name == name }) {
            mcpFail("Configuration '$name' already exists. Use action='update' to modify.")
        }

        val settings = if (!copyFrom.isNullOrBlank()) {
            val source = runManager.allSettings.find { it.name == copyFrom }
                ?: mcpFail("Configuration '$copyFrom' not found. Existing: ${runManager.allSettings.map { it.name }}")
            runManager.createConfiguration(source.configuration.clone() as RunConfiguration, source.factory!!)
                .also { it.name = name }
        } else {
            val types = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            val configType = types.firstOrNull { it.id == typeId }
                ?: mcpFail("Unknown typeId '$typeId'. Valid IDs: ${types.map { it.id }.sorted().take(MAX_LISTED_TYPES)}")
            val factory = configType.configurationFactories.firstOrNull()
                ?: mcpFail("No factory for type '$typeId'")
            runManager.createConfiguration(name, factory)
        }
        val config = settings.configuration

        if (!applyArgsEnv(config, programArgs, env).supported && (programArgs != null || env != null)) {
            mcpFail("Type '${config.type.id}' doesn't support programArgs/env")
        }

        runManager.addConfiguration(settings)

        return buildJsonObject {
            put("created", name)
            put("type", config.type.displayName)
            put("typeId", config.type.id)
            if (projectFilePathOf(config)?.isBlank() == true) {
                put("warning", "No project set: it will not run. Delete it and create with copyFrom=<an existing config of that project>.")
            }
        }.toString()
    }

    private suspend fun updateConfig(name: String, programArgs: String?, env: String?, newName: String?): String {
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == name }
            ?: mcpFail("Configuration '$name' not found")

        val config = settings.configuration
        val changes = mutableListOf<String>()

        val applied = applyArgsEnv(config, programArgs, env)
        if (!applied.supported && (programArgs != null || env != null)) {
            mcpFail("This config type doesn't support programArgs/env")
        }
        changes += applied.changes

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

    // .NET project configs keep the project in getProjectFilePath(); their class is not on the compile classpath.
    private fun projectFilePathOf(config: RunConfiguration): String? =
        runCatching { config.javaClass.getMethod("getProjectFilePath").invoke(config) as? String }.getOrNull()

    private class Applied(val supported: Boolean, val changes: List<String>)

    // Platform runnable configs and Rider's .NET project/exe configs (their args live in ExeConfigurationParameters).
    private fun applyArgsEnv(config: RunConfiguration, programArgs: String?, env: String?): Applied {
        val changes = mutableListOf<String>()
        when {
            config is CommonProgramRunConfigurationParameters -> {
                programArgs?.let { config.programParameters = it; changes.add("programArgs") }
                env?.let { config.envs = parseEnvString(it); changes.add("env") }
            }
            config is RiderConfigurationParametersAware<*> && config.parameters is ExeConfigurationParameters -> {
                val params = config.parameters as ExeConfigurationParameters
                programArgs?.let { params.programParameters = it; changes.add("programArgs") }
                env?.let { params.envs = parseEnvString(it); changes.add("env") }
            }
            else -> return Applied(false, changes)
        }
        return Applied(true, changes)
    }

    private fun parseEnvString(env: String): Map<String, String> = parseRunConfigEnv(env)
}

private const val MAX_LISTED_TYPES = 60

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
