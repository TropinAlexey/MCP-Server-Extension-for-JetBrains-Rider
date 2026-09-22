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
    @McpDescription("Runs unit/integration tests via the IDE (preferred over shell 'dotnet test' — you keep the structured result tree). Typical chain: rider_run_tests → poll rider_get_output(sessionId) until status is not 'running' → rider_get_test_results(sessionId) for the pass/fail tree. Shortcut: rider_run_tests_and_wait runs the same chain in one call. Filter by className (\"MyTestClass\"), methodName (\"ShouldWork\"), or raw dotnet test filter expression (\"FullyQualifiedName~Namespace.Class\"). Omit all filters to run all tests. WARNING: a bare configName — or no filters at all — ALWAYS runs the ENTIRE configuration (the whole solution, can be thousands of tests). On a vague prompt like \"run tests\", NEVER pass a bare configName: you MUST scope it with filter/className/methodName, or explicitly justify in your reply why a full run is intended. If the IDE is busy (indexing, build, active debug), check rider_get_ide_state first.")
    suspend fun rider_run_tests(
        @McpDescription("Run configuration name") configName: String? = null,
        @McpDescription("dotnet test --filter expression") filter: String? = null,
        @McpDescription("Test class name") className: String? = null,
        @McpDescription("Test method name") methodName: String? = null
    ): String {
        val project = coroutineContext.project
        val launch = launchTests(project, configName, filter, className, methodName)
        if (launch.errorJson != null) return launch.errorJson
        return buildJsonObject { put("sessionId", launch.session!!.id) }.toString()
    }

    @McpTool
    @McpDescription("Runs tests and waits for completion in one call (run + wait). Returns the final status, exit code, last output lines, and a sessionId for rider_get_test_results (structured pass/fail tree). Prefer this over shell 'dotnet test'. If the timeout expires first, returns status 'running' with the sessionId — continue with rider_get_output, then rider_get_test_results. WARNING: a bare configName — or no filters at all — ALWAYS runs the ENTIRE configuration. On a vague prompt like \"run tests\", NEVER pass a bare configName: you MUST scope it with filter/className/methodName, or explicitly justify in your reply why a full run is intended. If the IDE is busy (indexing, build, active debug), check rider_get_ide_state first.")
    suspend fun rider_run_tests_and_wait(
        @McpDescription("Run configuration name") configName: String? = null,
        @McpDescription("dotnet test --filter expression") filter: String? = null,
        @McpDescription("Test class name") className: String? = null,
        @McpDescription("Test method name") methodName: String? = null,
        @McpDescription("Max wait in milliseconds (1000-1800000, default 300000)") timeoutMs: Int = 300000
    ): String {
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
                put("note", "Timeout expired, tests still running. Continue with rider_get_output, then rider_get_test_results.")
            } else {
                put("note", "Finished. Call rider_get_test_results with this sessionId for the structured tree.")
            }
        }.toString()
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

        // Filtered runs go through 'dotnet test --filter' directly and do not
        // need a pre-created run configuration — don't block them on configs.
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
        // Same guard as rider_start_debug: non-runnable profiles (Publish/...)
        // have no run runner — fail fast instead of throwing on the EDT and
        // leaving a hung "running" session.
        val runRunner = try {
            ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, settings.configuration)
        } catch (_: Exception) {
            null
        }
        if (runRunner == null) mcpFail("Configuration '${settings.name}' cannot be run (no run runner — Publish/MSBuild profiles aren't runnable). Pick a run/test configuration instead.")

        val session = SessionManager.create("test")
        session.appendLine("Running: ${settings.name}")
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
                    // Without this the session hangs in "running" forever: a failed
                    // start (e.g. a unit-test config with no test scope → IDE shows
                    // "Unknown error") never fires processStarted. Fail fast instead.
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
        // Config-less runs must not depend on the working directory: when the
        // folder holds several projects dotnet answers MSB1011, so resolve an
        // explicit target (.sln/.slnx, else a test-like .csproj) and run from
        // its own directory. Null target = old behavior, dotnet reports the
        // real error into the session output.
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

    // Resolves an explicit `dotnet test` target under the project root so
    // config-less runs don't depend on the working directory: exactly one
    // .sln/.slnx (depth ≤ 3), else exactly one test-like .csproj (depth ≤ 4),
    // else null (caller falls back to basePath and dotnet reports MSB1011
    // itself). Skips build output and VCS dirs.
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

    @McpTool
    @McpDescription("Returns structured test results tree after tests finish: pass/fail status per test, duration, error messages, and stack traces for failures. Call after rider_get_output shows status is not 'running'. Use to analyze which tests passed or failed and why. Note: filter/className runs ('dotnet test --filter' as a plain process) have no IDE test tree — parse failures with stack traces from the session output text instead; the tree only exists for run-configuration launches.")
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
                    // Same guard as above: a failed start never fires
                    // processStarted, so without this the session hangs.
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
}
