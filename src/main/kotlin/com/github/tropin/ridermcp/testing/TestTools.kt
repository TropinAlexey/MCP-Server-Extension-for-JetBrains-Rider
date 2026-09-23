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
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.mcpserver.McpToolset
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.github.tropin.ridermcp.OutputSession
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.SessionManager
import kotlin.coroutines.coroutineContext

class TestToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs .NET tests. action='run' (default) starts tests and returns sessionId — poll with rider_get_output, then call rider_tests(action='results', sessionId=...) for the structured tree. " +
            "action='run_and_wait' runs and blocks up to timeoutMs (default 300000) in one call. " +
            "action='results' returns the pass/fail tree with durations, errors and stack traces (requires sessionId; IDE config runs only — filter runs have no tree, read failures from session output text instead). " +
            "action='rerun_failed' reruns only previously failed tests. " +
            "Scope with filter (raw 'dotnet test --filter' expr) or className/methodName — a filter run bypasses run configs and auto-resolves the .sln/.csproj. " +
            "WARNING: a bare configName — or no filters at all — ALWAYS runs the ENTIRE configuration (can be thousands of tests). " +
            "On a vague prompt like \"run tests\", NEVER pass a bare configName: scope with filter/className/methodName. " +
            "Do NOT use for building (rider_build) or debugging (rider_start_debug). " +
            "If the IDE is busy, check rider_get_ide_state first."
    )
    suspend fun rider_tests(
        @McpDescription("Action: run (default) starts async run, run_and_wait blocks until done, results returns structured tree, rerun_failed retries failures") action: String = "run",
        @McpDescription("IDE run configuration name (whole config runs — always combine with a filter to scope)") configName: String? = null,
        @McpDescription("Raw 'dotnet test --filter' expression, e.g. 'FullyQualifiedName~MyClass'. Bypasses run configs") filter: String? = null,
        @McpDescription("Test class name — converted to FullyQualifiedName~ filter, bypasses run configs") className: String? = null,
        @McpDescription("Test method name — combined with className when both given, otherwise FullyQualifiedName~ filter") methodName: String? = null,
        @McpDescription("Session ID from run/run_and_wait (required for action='results')") sessionId: String? = null,
        @McpDescription("Max wait in ms for run_and_wait only, 1000-1800000, default 300000") timeoutMs: Int = 300000
    ): String {
        return when (action.lowercase()) {
            "run" -> runTests(configName, filter, className, methodName)
            "run_and_wait" -> runTestsAndWait(configName, filter, className, methodName, timeoutMs)
            "results" -> getTestResults(sessionId)
            "rerun_failed" -> rerunFailedTests()
            else -> mcpFail("Unknown action '$action'. Use: run, run_and_wait, results, rerun_failed")
        }
    }

    private suspend fun runTests(configName: String?, filter: String?, className: String?, methodName: String?): String {
        val project = coroutineContext.project
        val launch = launchTests(project, configName, filter, className, methodName)
        if (launch.errorJson != null) return launch.errorJson
        return buildJsonObject { put("sessionId", launch.session!!.id) }.toString()
    }

    private suspend fun runTestsAndWait(configName: String?, filter: String?, className: String?, methodName: String?, timeoutMs: Int): String {
        val project = coroutineContext.project
        val launch = launchTests(project, configName, filter, className, methodName)
        if (launch.errorJson != null) return launch.errorJson
        val session = launch.session!!

        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1000, 1800000)
        while (session.status == "running" && System.currentTimeMillis() < deadline) {
            delay(1000)
        }

        return buildJsonObject {
            put("status", session.status)
            put("sessionId", session.id)
            session.exitCode?.let { put("exitCode", it) }
            putJsonArray("tail") { session.getAllLines().takeLast(30).forEach { add(it) } }
            if (session.status == "running") {
                put("note", "Timeout expired, tests still running. Continue with rider_get_output, then rider_tests(action='results').")
            } else {
                put("note", "Finished. Call rider_tests(action='results', sessionId='${session.id}') for the structured tree.")
            }
        }.toString()
    }

    private suspend fun getTestResults(sessionId: String?): String {
        if (sessionId.isNullOrBlank()) mcpFail("sessionId is required for action='results'")
        val project = coroutineContext.project
        val session = SessionManager.get(sessionId)
            ?: mcpFail("Session '$sessionId' not found")

        if (session.status == "running") mcpFail("Tests still running, wait for completion")

        val handler = session.tag as? ProcessHandler
            ?: mcpFail("No process handler captured for this session")

        val descriptors = RunContentManager.getInstance(project).allDescriptors
        val descriptor = descriptors.find { it.processHandler === handler }
            ?: mcpFail("Execution descriptor not found (tab may have been closed; filter/className runs have no IDE test tree — read failures with stack traces from the session output text instead)")

        val console = descriptor.executionConsole ?: mcpFail("No console available")

        val root = try {
            val resultsForm = findResultsForm(console) ?: mcpFail("No test results form found")
            resultsForm.testsRootNode
        } catch (e: Exception) {
            mcpFail("Cannot extract test tree: ${e.message}")
        }

        return extractTestNode(root).toString()
    }

    private suspend fun rerunFailedTests(): String {
        val project = coroutineContext.project
        val action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .getAction("RerunFailedTests")
            ?: mcpFail("Rerun Failed Tests action not available")

        val session = SessionManager.create("test")
        session.appendLine("Rerunning failed tests")

        ApplicationManager.getApplication().invokeLater {
            val connection = project.messageBus.connect()
            fun failRerunStart(error: Throwable?) {
                try { connection.disconnect() } catch (_: Exception) {}
                session.exitCode = 1
                session.status = "failed"
                session.appendLine("Error rerunning failed tests: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown error"}")
            }
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
                override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                    failRerunStart(null)
                }
                override fun processNotStarted(executorId: String, env: ExecutionEnvironment, error: Throwable) {
                    failRerunStart(error)
                }
            })

            val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
            com.intellij.openapi.actionSystem.ActionManager.getInstance().tryToExecute(action, null, frame, "", true)
        }

        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    private data class TestLaunch(val session: OutputSession?, val errorJson: String?)

    private fun launchTests(
        project: com.intellij.openapi.project.Project,
        configName: String?,
        filter: String?,
        className: String?,
        methodName: String?
    ): TestLaunch {
        val runManager = RunManager.getInstance(project)

        val testConfigs = runManager.allSettings.filter { config ->
            val id = config.type.id.lowercase()
            val displayName = config.type.displayName.lowercase()
            id.contains("test") || displayName.contains("test")
        }

        val filterExpr = when {
            filter != null -> filter
            className != null && methodName != null ->
                "FullyQualifiedName~${className}.${methodName}"
            className != null -> "FullyQualifiedName~${className}"
            methodName != null -> "FullyQualifiedName~${methodName}"
            else -> null
        }

        if (filterExpr != null) return TestLaunch(startFilteredTests(project, filterExpr), null)

        val settings = if (configName != null) {
            runManager.allSettings.find { it.name == configName }
                ?: mcpFail("Configuration '$configName' not found")
        } else when {
            testConfigs.isEmpty() -> mcpFail("No test configurations found. Create one in Rider via Run → Edit Configurations → + ('.NET Test' / xUnit / NUnit), or bypass configs by passing filter/className/methodName (runs 'dotnet test --filter <expr>' directly without a configuration). If the solution has no test project at all, shell 'dotnet test' is the fallback.")
            testConfigs.size == 1 -> testConfigs.first()
            else -> {
                return TestLaunch(
                    null,
                    buildJsonObject {
                        put("error", "Multiple test configs found, specify configName")
                        putJsonArray("configs") { testConfigs.forEach { add(it.name) } }
                    }.toString()
                )
            }
        }

        return TestLaunch(startIdeTests(project, settings), null)
    }

    private fun startIdeTests(
        project: com.intellij.openapi.project.Project,
        settings: com.intellij.execution.RunnerAndConfigurationSettings
    ): OutputSession {
        val runRunner = try {
            ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, settings.configuration)
        } catch (_: Exception) {
            null
        }
        if (runRunner == null) mcpFail("Configuration '${settings.name}' cannot be run (no run runner — Publish/MSBuild profiles aren't runnable). Pick a run/test configuration instead.")

        val session = SessionManager.create("test")
        session.appendLine("Running: ${settings.name}")
        captureSessionMetadata(session, project, settings.name)
        val cfgName = settings.name

        ApplicationManager.getApplication().invokeLater {
            val connection = project.messageBus.connect()
            fun failTestStart(error: Throwable?) {
                try { connection.disconnect() } catch (_: Exception) {}
                session.exitCode = 1
                session.status = "failed"
                session.appendLine("Error starting test run '${cfgName}': ${error?.message ?: error?.javaClass?.simpleName ?: "unknown error"}")
            }
            try {
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
                override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                    if (env.runProfile.name != cfgName) return
                    failTestStart(null)
                }
                override fun processNotStarted(executorId: String, env: ExecutionEnvironment, error: Throwable) {
                    if (env.runProfile.name != cfgName) return
                    failTestStart(error)
                }
            })

            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
            } catch (e: Exception) {
                try { connection.disconnect() } catch (_: Exception) {}
                session.status = "failed"
                session.appendLine("Error starting test run: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        return session
    }

    private fun startFilteredTests(project: com.intellij.openapi.project.Project, filter: String): OutputSession {
        val session = SessionManager.create("test")
        captureSessionMetadata(session, project)
        val target = resolveDotnetTestTarget(project)
        val cmd = if (target != null) {
            GeneralCommandLine("dotnet", "test", target.toString(), "--filter", filter)
                .withWorkDirectory(target.parent.toString())
        } else {
            GeneralCommandLine("dotnet", "test", "--filter", filter)
                .withWorkDirectory(project.basePath)
        }
        session.appendLine("Running: ${cmd.commandLineString}")
        try {
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
        return session
    }

    private fun captureSessionMetadata(session: OutputSession, project: com.intellij.openapi.project.Project, configName: String? = null) {
        configName?.let { session.metadata["configName"] = it }
        try {
            SolutionConfigurationManager.getInstance(project).activeConfigurationAndPlatform?.let { active ->
                session.metadata["configuration"] = active.configuration
                session.metadata["platform"] = active.platform
            }
        } catch (_: Throwable) {
            // configuration manager may not be ready yet
        }
    }

    private fun resolveDotnetTestTarget(project: com.intellij.openapi.project.Project): java.nio.file.Path? {
        val basePath = project.basePath ?: return null
        val base = try { java.nio.file.Paths.get(basePath) } catch (_: Exception) { return null }
        if (!java.nio.file.Files.isDirectory(base)) return null
        val skipped = setOf(".git", ".idea", ".vs", "bin", "obj", "TestResults")
        fun collect(match: (String) -> Boolean, maxDepth: Int, limit: Int): List<java.nio.file.Path> {
            val out = mutableListOf<java.nio.file.Path>()
            val stream = try {
                java.nio.file.Files.walk(base, maxDepth)
            } catch (_: Exception) {
                return out
            }
            try {
                stream.forEach { p ->
                    if (out.size >= limit) return@forEach
                    if (!java.nio.file.Files.isRegularFile(p)) return@forEach
                    if (p.any { it.fileName.toString() in skipped }) return@forEach
                    if (match(p.fileName.toString())) out.add(p)
                }
            } catch (_: Exception) {
            } finally {
                try { stream.close() } catch (_: Exception) {}
            }
            return out
        }
        val solutions = collect(
            { n -> n.endsWith(".sln", ignoreCase = true) || n.endsWith(".slnx", ignoreCase = true) },
            3, 2
        )
        if (solutions.size == 1) return solutions.first()
        val testProjects = collect(
            { n -> n.endsWith(".csproj", ignoreCase = true) && n.contains("test", ignoreCase = true) },
            4, 2
        )
        if (testProjects.size == 1) return testProjects.first()
        return null
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
}
