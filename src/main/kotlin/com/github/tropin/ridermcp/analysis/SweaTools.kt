package com.github.tropin.ridermcp.analysis

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.github.tropin.ridermcp.projectDir
import com.jetbrains.rider.model.SolutionAnalysisErrorWithIgnore
import com.jetbrains.rider.model.SolutionAnalysisErrors
import com.jetbrains.rider.solutionAnalysis.SolutionAnalysisHost
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class SweaToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Returns Solution Wide Analysis (SWEA) error and warning counts per project, plus top error codes and sample issues. " +
            "This is the source of the 'errors in solution' counter shown in the IDE status bar; the Problems panel only shows the current scope. " +
            "Does NOT trigger analysis: it reads the current SWEA model. If analysis is still running the response includes analysisInProgress=true; wait and call again. " +
            "Use when the user mentions a solution-wide error count that does not match the Problems panel."
    )
    suspend fun rider_swea_errors(
        @McpDescription("Maximum sample issues to return across all projects (default 50)") maxExamples: Int = 50
    ): String {
        val project = coroutineContext.project
        val host = SolutionAnalysisHost.getInstance(project)
        val model = host.model

        val issuesState = model.issuesState.valueOrNull
        val ready = host.ready && host.hostReady
        val inProgress = issuesState == null || !ready

        val fileErrors = model.fileErrors
        val result = ApplicationManager.getApplication().runReadAction<Triple<List<ProjectStat>, List<ExampleIssue>, Map<String, Int>>> {
            collectSweaData(project, fileErrors, maxExamples.coerceIn(0, 500))
        }
        val projectStats = result.first
        val examples = result.second
        val codeCounts = result.third

        val totalErrors = projectStats.sumOf { it.errorCount }
        val totalWarnings = projectStats.sumOf { it.warningCount }

        return buildJsonObject {
            put("analysisInProgress", inProgress)
            put("enabled", host.enabled)
            put("paused", host.paused)
            put("totalErrors", totalErrors)
            put("totalWarnings", totalWarnings)
            put("fileCount", issuesState?.fileCount ?: -1)
            putJsonArray("projects") {
                projectStats.forEach { stat ->
                    addJsonObject {
                        put("project", stat.project)
                        put("errorCount", stat.errorCount)
                        put("warningCount", stat.warningCount)
                    }
                }
            }
            putJsonArray("topCodes") {
                codeCounts.entries
                    .sortedByDescending { it.value }
                    .take(20)
                    .forEach { (code, count) ->
                        addJsonObject { put("code", code); put("count", count) }
                    }
            }
            putJsonArray("examples") {
                examples.forEach { ex ->
                    addJsonObject {
                        put("project", ex.project)
                        put("file", ex.file)
                        put("line", ex.line)
                        put("code", ex.code)
                        put("text", ex.text)
                        put("isWarning", ex.isWarning)
                    }
                }
            }
        }.toString()
    }

    private data class ProjectStat(val project: String, val errorCount: Int, val warningCount: Int)
    private data class ExampleIssue(
        val project: String,
        val file: String,
        val line: Int,
        val code: String,
        val text: String,
        val isWarning: Boolean
    )

    private fun collectSweaData(
        project: Project,
        fileErrors: Map<com.jetbrains.rider.model.SolutionAnalysisIdWithContext, SolutionAnalysisErrors>,
        maxExamples: Int
    ): Triple<List<ProjectStat>, List<ExampleIssue>, Map<String, Int>> {
        val byProject = mutableMapOf<String, ProjectStat>()
        val examples = mutableListOf<ExampleIssue>()
        val codeCounts = mutableMapOf<String, Int>()

        fileErrors.forEach { (key, entry) ->
            val projectName = inferProjectName(project, key.context) ?: "<unknown>"
            val errorsList = entry.errors.valueOrNull?.errors ?: emptyList()

            val visibleErrors = errorsList.filter { !it.ignored }
            val errorCount = visibleErrors.count { !it.isWarning }
            val warningCount = visibleErrors.count { it.isWarning }

            byProject.merge(projectName, ProjectStat(projectName, errorCount, warningCount)) { a, b ->
                ProjectStat(projectName, a.errorCount + b.errorCount, a.warningCount + b.warningCount)
            }

            visibleErrors.forEach { err ->
                val parsed = parseError(err)
                codeCounts.merge(parsed.code, 1) { a, b -> a + b }
                if (examples.size < maxExamples) {
                    val vfm = VirtualFileManager.getInstance()
                    val url = vfm.findFileByUrl("pdb://id/${key.fileId}")?.path
                        ?: key.context
                    examples.add(
                        ExampleIssue(
                            project = projectName,
                            file = url,
                            line = parsed.line,
                            code = parsed.code,
                            text = parsed.text,
                            isWarning = err.isWarning
                        )
                    )
                }
            }
        }

        return Triple(byProject.values.sortedByDescending { it.errorCount + it.warningCount }, examples, codeCounts)
    }

    private fun inferProjectName(project: Project, context: String): String? {
        if (context.isBlank()) return null
        val vfm = VirtualFileManager.getInstance()
        val vf = vfm.findFileByUrl(context) ?: return context
        return project.projectDir()?.let { root ->
            vf.toNioPathOrNull()?.parent?.toString()?.removePrefix(root.toString())
        } ?: vf.name
    }

    internal data class ParsedError(val code: String, val text: String, val line: Int)

    internal fun parseError(err: SolutionAnalysisErrorWithIgnore): ParsedError {
        val text = err.text
        val codeMatch = Regex("""^(CS\d+|BC\d+|FS\d+)""").find(text)
        val code = codeMatch?.groupValues?.get(1) ?: "<unknown>"
        val cleaned = codeMatch?.let { text.removePrefix(it.value).trimStart(':', ' ') } ?: text
        val line = 1
        return ParsedError(code, cleaned, line)
    }
}
