package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.runOnEdt
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.settings.DatabaseSettings
import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * Runs a script in an open console the way the user does (Execute on the whole script): results land in
 * the console's own result tabs and Output log, the console's session (#temp tables, SET options) is reused.
 * Read the outcome with rider_console_results — this runner only starts the run and waits for it to go idle.
 */
internal object IdeConsoleRunner {

    suspend fun run(project: Project, consoleName: String?, sql: String, waitSeconds: Int): String {
        val started = System.currentTimeMillis()
        val (ref, console, replaced) = runOnEdt { start(project, consoleName, sql) }

        // doRunQueryInConsole only schedules the run: give the session a moment to leave idle before
        // treating idle as "finished".
        val deadline = started + waitSeconds.coerceIn(0, 40) * 1000L
        var sawRunning = false
        while (System.currentTimeMillis() < deadline) {
            val running = runOnEdt { isRunning(console) }
            if (running) sawRunning = true
            if (!running && (sawRunning || System.currentTimeMillis() - started > 3000)) break
            delay(250)
        }
        val running = runOnEdt { isRunning(console) }
        return buildJsonObject {
            put("console", ref)
            put("status", if (running) "running" else "finished")
            put("inIde", true)
            if (replaced) put("editorTextReplaced", true)
            put("elapsedMs", System.currentTimeMillis() - started)
            put(
                "hint",
                if (running) "Still running in the IDE console. Poll rider_console_results(console='$ref') until state=idle; the user can stop it with the console's Stop button."
                else "Read the result tabs and Output with rider_console_results(console='$ref')."
            )
        }.toString()
    }

    private data class Started(val ref: String, val console: JdbcConsole, val replaced: Boolean)

    private fun start(project: Project, consoleName: String?, sql: String): Started {
        val c = try {
            DbConsoleReader.findConsole(project, consoleName)
        } catch (e: IllegalArgumentException) {
            mcpFail(e.message ?: "Console not found")
        }
        // Re-checked at run time: the flag may have been set after the caller's check.
        if (runCatching { c.dataSource?.isReadOnly == true }.getOrDefault(true)) {
            mcpFail("Console '${c.ref}' belongs to a Read-only data source: inIde=true is not available there.")
        }
        if (isRunning(c.console)) {
            mcpFail("Console '${c.ref}' is still running a script in the IDE. Poll rider_console_results(console='${c.ref}') until state=idle, or ask the user to stop it.")
        }
        val editor = editorOf(project, c)
        val document = editor.document
        // The run reads its statements from the console editor, so the script must be its text.
        // Replacing goes through a command: the user can undo it in the console (⌘Z).
        val replaced = sql.isNotBlank() && document.text != sql
        if (replaced) {
            WriteCommandAction.runWriteCommandAction(project, "Run Script from MCP", null, { document.setText(sql) })
        }
        val psiDocs = PsiDocumentManager.getInstance(project)
        psiDocs.commitDocument(document)
        val psiFile = psiDocs.getPsiFile(document) ?: mcpFail("Console '${c.ref}' has no SQL file behind its editor.")
        if (document.text.isBlank()) mcpFail("Console '${c.ref}' is empty: pass sql=…")

        val option = DatabaseSettings.ExecOption().apply {
            copyFrom(DatabaseSettings.getDefaultExecOption())
            execInside = DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT
            execOutside = DatabaseSettings.EXECUTE_OUTSIDE_WHOLE_SCRIPT
            execSelection = DatabaseSettings.EXECUTE_SELECTION_EXACTLY_SCRIPT
            newTab = false
        }
        val info = JdbcConsoleProvider.findScriptModelNoInject(project, psiFile, editor, TextRange(0, document.textLength), option)
            ?: mcpFail("The IDE found no statements to run in console '${c.ref}'.")
        JdbcConsoleProvider.doRunQueryInConsole(c.console, info)
        return Started(c.ref, c.console, replaced)
    }

    private fun editorOf(project: Project, c: DbConsoleReader.OpenConsole): EditorEx =
        FileEditorManager.getInstance(project).getEditors(c.file)
            .filterIsInstance<TextEditor>().firstNotNullOfOrNull { it.editor as? EditorEx }
            ?: mcpFail("Console '${c.ref}' has no text editor open.")

    // Via reflection: DatabaseSession drags grid-module types into the verifier's view.
    fun isRunning(console: JdbcConsole): Boolean =
        runCatching { DbConsoleReader.call(DbConsoleReader.call(console, "getSession")!!, "isIdle") == false }.getOrDefault(false)
}
