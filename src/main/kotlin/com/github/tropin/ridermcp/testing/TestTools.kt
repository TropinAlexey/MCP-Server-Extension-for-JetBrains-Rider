package com.github.tropin.ridermcp.testing

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.SessionManager
import kotlin.coroutines.coroutineContext

class TestToolset : McpToolset {

    @McpTool
    @McpDescription("Runs unit/integration tests. Filter by className (\"MyTestClass\"), methodName (\"ShouldWork\"), or raw dotnet test filter expression (\"FullyQualifiedName~Namespace.Class\"). Omit all filters to run all tests. Poll progress with rider_get_output, then get structured pass/fail results with rider_get_test_results.")
    suspend fun rider_run_tests(
        @McpDescription("Run configuration name") configName: String? = null,
        @McpDescription("dotnet test --filter expression") filter: String? = null,
        @McpDescription("Test class name") className: String? = null,
        @McpDescription("Test method name") methodName: String? = null
    ): String {
        val project = coroutineContext.project
        val runManager = RunManager.getInstance(project)

        val testConfigs = runManager.allSettings.filter { config ->
            val id = config.type.id.lowercase()
            val displayName = config.type.displayName.lowercase()
            id.contains("test") || displayName.contains("test")
        }

        val settings = if (configName != null) {
            runManager.allSettings.find { it.name == configName }
                ?: mcpFail("Configuration '$configName' not found")
        } else when {
            testConfigs.isEmpty() -> mcpFail("No test configurations found. Create one in Rider first.")
            testConfigs.size == 1 -> testConfigs.first()
            else -> {
                val result = buildJsonObject {
                    put("error", "Multiple test configs found, specify configName")
                    putJsonArray("configs") { testConfigs.forEach { add(it.name) } }
                }
                return result.toString()
            }
        }

        val filterExpr = when {
            filter != null -> filter
            className != null && methodName != null ->
                "FullyQualifiedName~${className}.${methodName}"
            className != null -> "FullyQualifiedName~${className}"
            methodName != null -> "FullyQualifiedName~${methodName}"
            else -> null
        }

        if (filterExpr != null) return runFilteredTests(project, filterExpr)

        val session = SessionManager.create("test")
        session.appendLine("Running: ${settings.name}")
        val cfgName = settings.name

        ApplicationManager.getApplication().invokeLater {
            val connection = project.messageBus.connect()
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    if (env.runProfile.name != cfgName) return
                    connection.disconnect()
                    session.tag = handler
                    handler.addProcessListener(object : ProcessListener {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            val text = event.text.trimEnd('\n', '\r')
                            if (text.isNotEmpty()) session.appendLine(text)
                        }
                        override fun processTerminated(event: ProcessEvent) {
                            session.exitCode = event.exitCode
                            session.status = if (event.exitCode == 0) "passed" else "failed"
                        }
                    })
                }
            })

            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }

        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    private fun runFilteredTests(project: com.intellij.openapi.project.Project, filter: String): String {
        val session = SessionManager.create("test")
        session.appendLine("Running: dotnet test --filter $filter")
        try {
            val cmd = GeneralCommandLine("dotnet", "test", "--filter", filter)
                .withWorkDirectory(project.basePath)
            val handler = OSProcessHandler(cmd)
            session.tag = handler
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text.trimEnd('\n', '\r')
                    if (text.isNotEmpty()) session.appendLine(text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    session.exitCode = event.exitCode
                    session.status = if (event.exitCode == 0) "passed" else "failed"
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    @McpTool
    @McpDescription("Returns structured test results tree after tests finish: pass/fail status per test, duration, error messages, and stack traces for failures. Call after rider_get_output shows status is not 'running'. Use to analyze which tests passed or failed and why.")
    suspend fun rider_get_test_results(
        @McpDescription("Session ID from rider_run_tests") sessionId: String
    ): String {
        val project = coroutineContext.project
        val session = SessionManager.get(sessionId)
            ?: mcpFail("Session '$sessionId' not found")

        if (session.status == "running") mcpFail("Tests still running, wait for completion")

        val handler = session.tag as? ProcessHandler
            ?: mcpFail("No process handler captured for this session")

        val descriptors = RunContentManager.getInstance(project).allDescriptors
        val descriptor = descriptors.find { it.processHandler === handler }
            ?: mcpFail("Execution descriptor not found (tab may have been closed)")

        val console = descriptor.executionConsole ?: mcpFail("No console available")

        val root = try {
            val resultsForm = findResultsForm(console) ?: mcpFail("No test results form found")
            resultsForm.testsRootNode
        } catch (e: Exception) {
            mcpFail("Cannot extract test tree: ${e.message}")
        }

        return extractTestNode(root).toString()
    }

    private fun findResultsForm(console: Any): SMTestRunnerResultsForm? {
        if (console is SMTestRunnerResultsForm) return console
        try {
            val method = console.javaClass.getMethod("getResultsViewer")
            val viewer = method.invoke(console)
            if (viewer is SMTestRunnerResultsForm) return viewer
        } catch (_: Exception) {}
        return null
    }

    private fun extractTestNode(proxy: AbstractTestProxy): JsonObject {
        return buildJsonObject {
            put("name", proxy.name ?: "")
            put("leaf", proxy.isLeaf)
            if (proxy is SMTestProxy) {
                put("status", when {
                    proxy.isPassed -> "passed"
                    proxy.isDefect -> "failed"
                    proxy.isIgnored -> "ignored"
                    proxy.isInterrupted -> "interrupted"
                    else -> "unknown"
                })
                proxy.duration?.let { put("durationMs", it) }
                proxy.errorMessage?.let { put("error", it) }
                proxy.stacktrace?.let { put("stacktrace", it) }
            }
            val children = proxy.children
            if (children.isNotEmpty()) {
                putJsonArray("children") {
                    children.forEach { add(extractTestNode(it)) }
                }
            }
        }
    }

    @McpTool
    @McpDescription("Reruns only the previously failed tests (retry failures). Uses the IDE's Rerun Failed Tests action. Poll with rider_get_output, then rider_get_test_results for results.")
    suspend fun rider_rerun_failed_tests(): String {
        val project = coroutineContext.project
        val action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .getAction("RerunFailedTests")
            ?: mcpFail("Rerun Failed Tests action not available")

        val session = SessionManager.create("test")
        session.appendLine("Rerunning failed tests")

        ApplicationManager.getApplication().invokeLater {
            val connection = project.messageBus.connect()
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    connection.disconnect()
                    session.tag = handler
                    handler.addProcessListener(object : ProcessListener {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            val text = event.text.trimEnd('\n', '\r')
                            if (text.isNotEmpty()) session.appendLine(text)
                        }
                        override fun processTerminated(event: ProcessEvent) {
                            session.exitCode = event.exitCode
                            session.status = if (event.exitCode == 0) "passed" else "failed"
                        }
                    })
                }
            })

            val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
            com.intellij.openapi.actionSystem.ActionManager.getInstance().tryToExecute(action, null, frame, "", true)
        }

        return buildJsonObject { put("sessionId", session.id) }.toString()
    }
}
