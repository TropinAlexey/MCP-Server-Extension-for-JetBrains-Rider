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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.build.SessionIdArgs
import com.github.tropin.ridermcp.mcpJson

@Serializable
data class RunTestsArgs(
    val configName: String? = null,
    val filter: String? = null,
    val className: String? = null,
    val methodName: String? = null
)

class RunTestsTool : AbstractMcpTool<RunTestsArgs>(RunTestsArgs.serializer()) {
    override val name = "rider_run_tests"
    override val description = """Runs unit/integration tests. Filter by className ("MyTestClass"), methodName ("ShouldWork"), or raw dotnet test filter expression ("FullyQualifiedName~Namespace.Class"). Omit all filters to run all tests. Poll progress with rider_get_output, then get structured pass/fail results with rider_get_test_results."""

    override fun handle(project: Project, args: RunTestsArgs): Response {
        val runManager = RunManager.getInstance(project)

        val testConfigs = runManager.allSettings.filter { config ->
            val id = config.type.id.lowercase()
            val displayName = config.type.displayName.lowercase()
            id.contains("test") || displayName.contains("test")
        }

        val settings = if (args.configName != null) {
            runManager.allSettings.find { it.name == args.configName }
                ?: return Response(error = "Configuration '${args.configName}' not found")
        } else when {
            testConfigs.isEmpty() -> return Response(error = "No test configurations found. Create one in Rider first.")
            testConfigs.size == 1 -> testConfigs.first()
            else -> {
                val result = buildJsonObject {
                    put("error", "Multiple test configs found, specify configName")
                    putJsonArray("configs") { testConfigs.forEach { add(it.name) } }
                }
                return Response(result.toString())
            }
        }

        val filterExpr = when {
            args.filter != null -> args.filter
            args.className != null && args.methodName != null ->
                "FullyQualifiedName~${args.className}.${args.methodName}"
            args.className != null -> "FullyQualifiedName~${args.className}"
            args.methodName != null -> "FullyQualifiedName~${args.methodName}"
            else -> null
        }

        if (filterExpr != null) return runFilteredTests(project, filterExpr)

        val session = SessionManager.create("test")
        session.appendLine("Running: ${settings.name}")
        val configName = settings.name

        ApplicationManager.getApplication().invokeLater {
            val connection = project.messageBus.connect()
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    if (env.runProfile.name != configName) return
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

        return Response(mcpJson.encodeToString(mapOf("sessionId" to session.id)))
    }

    private fun runFilteredTests(project: Project, filter: String): Response {
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
        return Response(mcpJson.encodeToString(mapOf("sessionId" to session.id)))
    }
}

class GetTestResultsTool : AbstractMcpTool<SessionIdArgs>(SessionIdArgs.serializer()) {
    override val name = "rider_get_test_results"
    override val description = "Returns structured test results tree after tests finish: pass/fail status per test, duration, error messages, and stack traces for failures. Call after rider_get_output shows status is not 'running'. Use to analyze which tests passed or failed and why."

    override fun handle(project: Project, args: SessionIdArgs): Response {
        val session = SessionManager.get(args.sessionId)
            ?: return Response(error = "Session '${args.sessionId}' not found")

        if (session.status == "running") {
            return Response(error = "Tests still running, wait for completion")
        }

        val handler = session.tag as? ProcessHandler
            ?: return Response(error = "No process handler captured for this session")

        val descriptors = RunContentManager.getInstance(project).allDescriptors
        val descriptor = descriptors.find { it.processHandler === handler }
            ?: return Response(error = "Execution descriptor not found (tab may have been closed)")

        val console = descriptor.executionConsole ?: return Response(error = "No console available")

        val root = try {
            val resultsForm = findResultsForm(console) ?: return Response(error = "No test results form found")
            resultsForm.testsRootNode
        } catch (e: Exception) {
            return Response(error = "Cannot extract test tree: ${e.message}")
        }

        val tree = extractTestNode(root)
        return Response(tree.toString())
    }

    private fun findResultsForm(console: Any): SMTestRunnerResultsForm? {
        if (console is SMTestRunnerResultsForm) return console
        // SMTestRunnerConsoleView wraps results form
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
}

class RerunFailedTestsTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "rider_rerun_failed_tests"
    override val description = "Reruns only the previously failed tests (retry failures). Uses the IDE's Rerun Failed Tests action. Poll with rider_get_output, then rider_get_test_results for results."

    override fun handle(project: Project, args: NoArgs): Response {
        val action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .getAction("RerunFailedTests")
            ?: return Response(error = "Rerun Failed Tests action not available")

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

        return Response(mcpJson.encodeToString(mapOf("sessionId" to session.id)))
    }
}
