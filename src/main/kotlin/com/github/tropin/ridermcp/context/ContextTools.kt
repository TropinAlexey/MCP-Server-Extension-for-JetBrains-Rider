package com.github.tropin.ridermcp.context

import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.projectDir
import com.github.tropin.ridermcp.relTo
import kotlin.coroutines.coroutineContext

class ContextToolset : McpToolset {

    @McpTool
    @McpDescription("Returns what the programmer is looking at right now: active file (project-relative), cursor line+column (1-indexed), selected text with line range, surrounding code (±5 lines, current line marked '>>> '), other open editors, unsaved files, and bookmarks. Use for 'what is the user looking at / selected'. Contents may include secrets when open files hold them — do not paste into logs or chats. For IDE busyness use rider_get_ide_state; for any tool window text use rider_tool_window; for open DB consoles use rider_list_db_consoles.")
    suspend fun rider_get_context(): String {
        val project = coroutineContext.project
        val projectDir = project.projectDir()
        val fem = FileEditorManager.getInstance(project)
        val fdm = FileDocumentManager.getInstance()

        val result = buildJsonObject {
            val b = this

            ApplicationManager.getApplication().runReadAction {
                val editor = fem.selectedTextEditor ?: return@runReadAction
                val vf = editor.virtualFile ?: return@runReadAction
                b.put("file", vf.toNioPathOrNull()?.relTo(projectDir) ?: vf.path)

                val caret = editor.caretModel.primaryCaret
                val line = caret.logicalPosition.line
                b.put("line", line + 1)
                b.put("column", caret.logicalPosition.column + 1)

                val doc = editor.document
                val ctxStart = maxOf(0, line - 5)
                val ctxEnd = minOf(doc.lineCount - 1, line + 5)
                b.put("context", (ctxStart..ctxEnd).joinToString("\n") { ln ->
                    val s = doc.getLineStartOffset(ln)
                    val e = doc.getLineEndOffset(ln)
                    "${if (ln == line) ">>> " else "    "}${ln + 1}: ${doc.getText(TextRange(s, e))}"
                })

                editor.selectionModel.selectedText?.takeIf { it.isNotEmpty() }?.let { text ->
                    b.putJsonObject("selection") {
                        put("text", text)
                        put("startLine", doc.getLineNumber(editor.selectionModel.selectionStart) + 1)
                        put("endLine", doc.getLineNumber(editor.selectionModel.selectionEnd) + 1)
                    }
                }

                val activeVf = fem.selectedTextEditor?.virtualFile
                val others = fem.openFiles.filter { it != activeVf }
                if (others.isNotEmpty()) {
                    b.putJsonArray("openEditors") {
                        others.forEach { add(it.toNioPathOrNull()?.relTo(projectDir) ?: it.path) }
                    }
                    val mod = others.filter { f ->
                        fdm.getDocument(f)?.let { fdm.isDocumentUnsaved(it) } == true
                    }
                    if (mod.isNotEmpty()) {
                        b.putJsonArray("modified") {
                            mod.forEach { add(it.toNioPathOrNull()?.relTo(projectDir) ?: it.path) }
                        }
                    }
                }

                try {
                    val bm = BookmarksManager.getInstance(project) ?: return@runReadAction
                    val allBookmarks = bm.bookmarks
                    if (allBookmarks.isNotEmpty()) {
                        b.putJsonArray("bookmarks") {
                            allBookmarks.forEach { bookmark ->
                                val desc = bm.getGroups(bookmark).firstOrNull()?.name
                                addJsonObject {
                                    put("bookmark", bookmark.toString())
                                    desc?.takeIf { it.isNotEmpty() }?.let { put("group", it) }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return result.toString()
    }

}
