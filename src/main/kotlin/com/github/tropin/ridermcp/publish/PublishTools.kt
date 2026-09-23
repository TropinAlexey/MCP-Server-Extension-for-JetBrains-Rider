package com.github.tropin.ridermcp.publish

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.github.tropin.ridermcp.OutputSession
import com.github.tropin.ridermcp.SessionManager
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import com.jetbrains.rider.run.configurations.publishing.base.PublishRunConfiguration
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class PublishToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Publishes a .NET project using an existing Publish run configuration (the ones created by Rider under Run → Edit Configurations). " +
            "Returns a sessionId — poll with rider_get_output until status != 'running'. " +
            "Use when you need to trigger a Publish (e.g. to reproduce phantom errors after Publish) or to capture its Configuration/Platform/PublishProfile metadata. " +
            "Do NOT use for regular builds (rider_build) or tests (rider_tests)."
    )
    suspend fun rider_publish(
        @McpDescription("Exact name of an existing Publish run configuration; omit if there is only one") configName: String? = null
    ): String {
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)

        val publishConfigs = runManager.allSettings.filter { isPublishConfig(it) }

        val settings = when {
            configName != null -> publishConfigs.find { it.name == configName }
                ?: mcpFail("Publish configuration '$configName' not found. Available: ${publishConfigs.map { it.name }}")
            publishConfigs.isEmpty() -> mcpFail("No Publish configurations found. Create one in Rider via Run → Edit Configurations → Publish (or right-click a project → Publish).")
            publishConfigs.size == 1 -> publishConfigs.first()
            else -> mcpFail("Multiple Publish configurations found; pass configName. Available: ${publishConfigs.map { it.name }}")
        }

        val runConfig = settings.configuration as? PublishRunConfiguration<*>
            ?: mcpFail("Selected configuration is not a Publish configuration")
        val publishSettings = runConfig.settings

        val session = SessionManager.create("publish")
        session.appendLine("Publishing: ${settings.name}")
        capturePublishMetadata(session, project, settings.name, publishSettings)

        ApplicationManager.getApplication().invokeLater {
            val connection = project.messageBus.connect()
            fun failPublishStart(error: Throwable?) {
                try { connection.disconnect() } catch (_: Exception) {}
                session.exitCode = 1
                session.status = "failed"
                session.appendLine("Error starting publish '${settings.name}': ${error?.message ?: error?.javaClass?.simpleName ?: "unknown error"}")
            }
            try {
                connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                        if (env.runProfile.name != settings.name) return
                        connection.disconnect()
                        session.tag = handler
                        handler.addProcessListener(object : ProcessListener {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                val text = event.text.trimEnd('\n', '\r')
                                if (text.isNotEmpty()) session.appendLine(text)
                            }
                            override fun processTerminated(event: ProcessEvent) {
                                session.exitCode = event.exitCode
                                session.status = if (event.exitCode == 0) "succeeded" else "failed"
                            }
                        })
                    }
                    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                        if (env.runProfile.name != settings.name) return
                        failPublishStart(null)
                    }
                    override fun processNotStarted(executorId: String, env: ExecutionEnvironment, error: Throwable) {
                        if (env.runProfile.name != settings.name) return
                        failPublishStart(error)
                    }
                })

                val runner = ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, settings.configuration)
                    ?: mcpFail("No runner available for publish configuration '${settings.name}'")
                ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
            } catch (e: Exception) {
                try { connection.disconnect() } catch (_: Exception) {}
                session.status = "failed"
                session.appendLine("Error starting publish: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    private fun isPublishConfig(settings: RunnerAndConfigurationSettings): Boolean {
        return settings.configuration is PublishRunConfiguration<*> ||
            settings.type?.displayName?.contains("Publish", ignoreCase = true) == true ||
            settings.type?.id?.contains("Publish", ignoreCase = true) == true
    }

    internal fun capturePublishMetadata(
        session: OutputSession,
        project: com.intellij.openapi.project.Project?,
        configName: String,
        settings: com.jetbrains.rider.run.configurations.publishing.base.PublishRunConfigurationSettingsBase
    ) {
        session.metadata["configName"] = configName
        if (settings is com.jetbrains.rider.run.configurations.publishing.PubXmlRunConfigurationType.ConfigurationSettings) {
            settings.publishProfile?.takeIf { it.isNotBlank() }?.let { session.metadata["publishProfile"] = it }
            settings.pubxmlPath?.takeIf { it.isNotBlank() }?.let { session.metadata["pubxmlPath"] = it }
            settings.configuration?.takeIf { it.isNotBlank() }?.let { session.metadata["configuration"] = it }
            settings.platform?.takeIf { it.isNotBlank() }?.let { session.metadata["platform"] = it }
        }
        if (project == null) return
        try {
            SolutionConfigurationManager.getInstance(project).activeConfigurationAndPlatform?.let { active ->
                session.metadata.putIfAbsent("configuration", active.configuration)
                session.metadata.putIfAbsent("platform", active.platform)
            }
        } catch (_: Throwable) {
            // configuration manager may not be ready yet
        }
    }
}
