package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.runOnEdt
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JTable
import kotlin.coroutines.coroutineContext

class ConsoleResultsToolset : McpToolset {

    @McpTool
    @McpDescription("Reads what the USER ran in an open SQL console (Services → Database result tabs): every result grid of the console's last run as CSV with columns, rows loaded by the IDE and paging info, plus the Output log tail (PRINT/RAISERROR … WITH NOWAIT lines, 'N rows retrieved in …'). state=running while the console still executes — poll it, the console is never blocked. Use it when the user ran a long script themselves and asks about its results; #temp tables of the user's session are not reachable from rider_execute_console, but their final SELECTs are here. index picks one result tab (1-based, default all). hasMoreInIde=true means the IDE grid fetched only its first page — the remaining rows were never loaded; ask the user to raise the grid page size or re-run via rider_execute_console(outputFile=…). outputFile writes every grid to .csv/.json/.txt and keeps the response small.")
    suspend fun rider_console_results(
        @McpDescription("consoleRef '<data source>/<file>' from rider_list_db_consoles (bare file name when unique; omit = selected console tab)") console: String? = null,
        @McpDescription("Result tab, 1-based (0 = all, default)") index: Int = 0,
        @McpDescription("Rows to skip in each grid") offset: Int = 0,
        @McpDescription("Rows per grid in the response, 1-5000, default 200") pageSize: Int = 200,
        @McpDescription("Max chars per cell, '…[+N chars]' marks a cut; 0 = unlimited, default 4000") maxCellLength: Int = 4000,
        @McpDescription("Output log lines to return from the end, 0-2000, default 40") outputLines: Int = 40,
        @McpDescription("Mask password/secret/token columns and password= values, default true") maskSecrets: Boolean = true,
        @McpDescription("Write each grid to this .csv/.json/.txt file (txt = one line per row, cells tab-separated, no header; later grids → <name>-<index>.<ext>); relative = project dir; allowed: project, system temp dir (agent scratchpads only when under it), -Drider.mcp.exportDirs / RIDER_MCP_EXPORT_DIRS. Response keeps path, rowCount and 5 preview rows") outputFile: String? = null,
        @McpDescription("Replace outputFile (and its <name>-<index> siblings) when it already exists; default false = fail instead") overwrite: Boolean = false
    ): String {
        val project = coroutineContext.project
        val target = outputFile?.takeIf { it.isNotBlank() }?.let { value ->
            try {
                ExportFiles.resolve(value, project.basePath, overwrite)
            } catch (e: IllegalArgumentException) {
                mcpFail(e.message ?: "Invalid outputFile '$value'")
            }
        }
        val snapshot = runOnEdt { snapshot(project, console, index, outputLines.coerceIn(0, 2000)) }

        val cellLimit = maxCellLength.coerceAtLeast(0)
        val rowsPerGrid = pageSize.coerceIn(1, 5000)
        val skip = offset.coerceAtLeast(0)
        val written = if (target != null) withContext(Dispatchers.IO) {
            try {
                writeFiles(snapshot.grids, target, maskSecrets, overwrite)
            } catch (e: IllegalArgumentException) {
                mcpFail(e.message ?: "Cannot write outputFile")
            }
        } else emptyMap()

        return buildJsonObject {
            put("console", snapshot.console)
            snapshot.dataSource?.let { put("dataSource", it) }
            put("state", snapshot.state)
            put("resultCount", snapshot.totalGrids)
            putJsonArray("results") {
                snapshot.grids.forEach { g ->
                    add(buildJsonObject {
                        put("index", g.index)
                        put("title", g.title)
                        putJsonArray("columns") { g.columns.forEach { add(it) } }
                        put("loadedRows", g.rows.size)
                        g.totalRows?.let { put("totalRows", it) }
                        put("hasMoreInIde", g.hasMoreInIde)
                        val file = written[g.index]
                        val from = if (file != null) 0 else skip
                        val limit = if (file != null) PREVIEW_ROWS else rowsPerGrid
                        val page = g.rows.drop(from).take(limit)
                        val masks = if (maskSecrets) g.columns.map(SecretMasker::classifyColumn) else null
                        var truncated = 0
                        var masked = 0
                        val text = StringBuilder(g.columns.joinToString(",") { SqlText.csvField(it) })
                        val previewCell = if (file != null && (cellLimit == 0 || cellLimit > PREVIEW_CELL)) PREVIEW_CELL else cellLimit
                        page.forEach { row ->
                            text.append('\n')
                            row.forEachIndexed { c, raw ->
                                if (c > 0) text.append(',')
                                var v = raw ?: return@forEachIndexed
                                if (masks != null) {
                                    val (m, hit) = SecretMasker.maskCell(v, masks[c])
                                    if (hit) masked++
                                    v = m
                                }
                                val (cell, cut) = SqlText.truncateCell(v, previewCell)
                                if (cut) truncated++
                                text.append(SqlText.csvField(cell))
                            }
                        }
                        file?.let { put("file", it.first); put("fileRows", it.second) }
                        if (from > 0) put("offset", from)
                        put("rowCount", page.size)
                        put("hasMore", from + page.size < g.rows.size)
                        put("text", text.toString())
                        if (truncated > 0) put("truncatedCells", truncated)
                        if (masked > 0) put("maskedCells", masked)
                    })
                }
            }
            if (snapshot.output.isNotEmpty()) {
                putJsonArray("output") {
                    snapshot.output.forEach { add(if (maskSecrets) SecretMasker.maskText(it) else it) }
                }
            }
            snapshot.output.lastOrNull { summaryLine.containsMatchIn(it) }?.let { put("summary", it) }
            if (snapshot.state == "running") put("hint", "Still executing — poll again; results and Output grow as the script runs.")
            if (snapshot.totalGrids == 0 && snapshot.state != "running") {
                put("hint", "No result tabs for this console. Results appear in Services → Database after the user runs a query; a closed result tab cannot be read.")
            }
        }.toString()
    }

    private class Grid(
        val index: Int,
        val title: String,
        val columns: List<String>,
        val rows: List<List<String?>>,
        val totalRows: Long?,
        val hasMoreInIde: Boolean
    )

    private class Snapshot(
        val console: String,
        val dataSource: String?,
        val state: String,
        val totalGrids: Int,
        val grids: List<Grid>,
        val output: List<String>
    )

    private fun snapshot(project: Project, consoleName: String?, index: Int, outputLines: Int): Snapshot {
        val (file, console) = findConsole(project, consoleName)
        val view = console.view
        val ui = view.ui
        val outputContent = runCatching { view.outputContent }.getOrNull()
        val contents = ui.contentManager.contents.filter { it !== outputContent }
        val grids = mutableListOf<Pair<String, GridRef>>()
        for (content in contents) {
            findGrids(content.component).forEach { grids.add((content.displayName ?: content.tabName ?: "result") to it) }
        }
        if (index > grids.size) mcpFail("Result #$index not found: console '${file.name}' has ${grids.size} result tab(s): ${grids.map { it.first }}")
        val selected = grids.withIndex().filter { index <= 0 || it.index + 1 == index }.map { (i, g) -> readGrid(i + 1, g.first, g.second) }

        val running = IdeConsoleRunner.isRunning(console)
        val output = outputContent?.component?.let { outputText(it) }.orEmpty()
            .let { lines -> if (outputLines == 0) emptyList() else lines.takeLast(outputLines) }
        val ds = runCatching { console.dataSource.name }.getOrNull()
        return Snapshot(file.name, ds, if (running) "running" else "idle", grids.size, selected, output)
    }

    private fun findConsole(project: Project, name: String?): Pair<VirtualFile, JdbcConsole> = try {
        DbConsoleReader.findConsole(project, name).let { it.file to it.console }
    } catch (e: IllegalArgumentException) {
        mcpFail(e.message ?: "Console not found")
    }

    /** A result grid: the IDE DataGrid when reachable, plus its Swing table as a fallback reader. */
    private class GridRef(val table: JTable, val grid: Any?)

    /**
     * Grids under [root]. A result grid's table resolves to its DataGrid through the data context.
     * The grid API lives in platform modules this plugin does not depend on, so it is used reflectively
     * (key by name, public interface methods); the Swing table is the fallback.
     */
    private fun findGrids(root: Component): List<GridRef> {
        val found = mutableListOf<GridRef>()
        val seen = HashSet<Any>()
        fun walk(c: Component) {
            if (c is JTable) {
                val grid = runCatching { DataManager.getInstance().getDataContext(c).getData(DATA_GRID_KEY) }.getOrNull()
                if (grid == null || seen.add(grid)) found.add(GridRef(c, grid))
                return
            }
            if (c is Container) for (child in c.components) walk(child)
        }
        walk(root)
        return found
    }

    private fun readGrid(index: Int, title: String, ref: GridRef): Grid {
        ref.grid?.let { grid -> runCatching { readDataGrid(index, title, grid) }.getOrNull()?.let { return it } }
        val t = ref.table
        val names = (0 until t.columnCount).map { t.getColumnName(it) }
        val rows = (0 until t.rowCount).map { r -> (0 until t.columnCount).map { c -> cellText(t.getValueAt(r, c)) } }
        return Grid(index, title, names, rows, null, false)
    }

    private fun readDataGrid(index: Int, title: String, grid: Any): Grid {
        val getModel = DbConsoleReader.publicMethod(grid, "getDataModel", 1)
        @Suppress("UNCHECKED_CAST")
        val access = java.lang.Enum.valueOf(getModel.parameterTypes[0] as Class<out Enum<*>>, "DATA_WITH_MUTATIONS")
        val model = getModel.invoke(grid, access)!!
        val columns = DbConsoleReader.call(model, "getColumns") as List<*>
        val names = columns.mapIndexed { i, c -> (runCatching { DbConsoleReader.call(c!!, "getName") }.getOrNull() as? String) ?: "column${i + 1}" }
        val numbers = columns.mapIndexed { i, c -> (runCatching { DbConsoleReader.call(c!!, "getColumnNumber") }.getOrNull() as? Int) ?: i }
        val getValue = { row: Any, n: Int -> DbConsoleReader.publicMethod(row, "getValue", 1).invoke(row, n) }
        val rows = (DbConsoleReader.call(model, "getRows") as List<*>).map { row -> numbers.map { n -> cellText(getValue(row!!, n)) } }
        val paging = runCatching { DbConsoleReader.call(DbConsoleReader.call(grid, "getDataHookup")!!, "getPageModel") }.getOrNull()
        val total = paging?.let { p ->
            runCatching { if (DbConsoleReader.call(p, "isTotalRowCountPrecise") == true) DbConsoleReader.call(p, "getTotalRowCount") as? Long else null }.getOrNull()
        }
        val hasMore = paging?.let { p -> runCatching { DbConsoleReader.call(p, "isLastPage") == false }.getOrDefault(false) } ?: false
        return Grid(index, title, names, rows, total, hasMore)
    }

    private fun cellText(value: Any?): String? = when (value) {
        null -> null
        is ByteArray -> "0x" + value.joinToString("") { "%02X".format(it) }
        else -> value.toString()
    }

    /** Text of the Output log. Only read-only (viewer) editors count: the content also hosts the editable script editor. */
    private fun outputText(root: Component): List<String> {
        val lines = mutableListOf<String>()
        fun walk(c: Component) {
            if (c is EditorComponentImpl) {
                if (!c.editor.isViewer) return
                val text = ApplicationManager.getApplication().runReadAction<String> { c.editor.document.text }
                lines.addAll(text.lines().filter { it.isNotBlank() })
                return
            }
            if (c is Container) for (child in c.components) walk(child)
        }
        walk(root)
        return lines
    }

    /** Writes every grid; the first goes to [target], later ones to `<stem>-<index>.<ext>`. Returns index → (path, rows). */
    private fun writeFiles(grids: List<Grid>, target: Path, mask: Boolean, overwrite: Boolean): Map<Int, Pair<String, Int>> {
        val format = ExportFiles.formatOf(target) ?: ExportFiles.Format.CSV
        val json = format == ExportFiles.Format.JSON
        val paths = grids.mapIndexed { i, g -> if (i == 0) target else ExportFiles.sibling(target, "-${g.index}") }
        paths.drop(1).forEach { ExportFiles.checkWritable(it, overwrite) }
        return grids.withIndex().associate { (i, g) ->
            val path = paths[i]
            val kinds = if (mask) g.columns.map(SecretMasker::classifyColumn) else null
            ExportFiles.newWriter(path, overwrite).use { w ->
                when (format) {
                    ExportFiles.Format.JSON -> w.write("[")
                    ExportFiles.Format.CSV -> w.write(g.columns.joinToString(",") { SqlText.csvField(it) } + "\n")
                    ExportFiles.Format.TXT -> {}
                }
                g.rows.forEachIndexed { r, row ->
                    val cells = row.mapIndexed { c, v -> if (v != null && kinds != null) SecretMasker.maskCell(v, kinds[c]).first else v }
                    if (format == ExportFiles.Format.TXT) {
                        w.write(ExportFiles.txtLine(cells) + "\n")
                    } else if (json) {
                        w.write(if (r == 0) "\n{" else ",\n{")
                        cells.forEachIndexed { c, v ->
                            if (c > 0) w.write(",")
                            w.write(JsonPrimitive(g.columns[c]).toString() + ":" + (v?.let { JsonPrimitive(it).toString() } ?: "null"))
                        }
                        w.write("}")
                    } else {
                        w.write(cells.joinToString(",") { v -> v?.let(SqlText::csvField).orEmpty() } + "\n")
                    }
                }
                if (json) w.write(if (g.rows.isEmpty()) "]\n" else "\n]\n")
            }
            g.index to (path.toString() to g.rows.size)
        }
    }

    private companion object {
        const val PREVIEW_ROWS = 5
        const val PREVIEW_CELL = 200
        val DATA_GRID_KEY: DataKey<Any> = DataKey.create("DATA_GRID_KEY")
        val summaryLine = Regex("(?i)rows? retrieved|rows? affected|completed in|\\bin \\d+ (ms|s)\\b")
    }
}
