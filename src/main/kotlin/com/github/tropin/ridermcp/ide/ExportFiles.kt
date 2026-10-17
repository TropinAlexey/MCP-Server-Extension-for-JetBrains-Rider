package com.github.tropin.ridermcp.ide

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Target files for outputFile exports (rider_execute_console, rider_console_results): format by
 * extension, writes confined to the project, the system temp dirs (agent scratchpads) and
 * directories listed in `-Drider.mcp.exportDirs` / `RIDER_MCP_EXPORT_DIRS`, no silent overwrite.
 */
internal object ExportFiles {

    enum class Format { CSV, JSON, TXT }

    const val EXPORT_DIRS_PROPERTY = "rider.mcp.exportDirs"
    const val EXPORT_DIRS_ENV = "RIDER_MCP_EXPORT_DIRS"

    fun formatOf(path: Path): Format? = when (path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()) {
        "csv" -> Format.CSV
        "json" -> Format.JSON
        "txt" -> Format.TXT
        else -> null
    }

    /** Project dir, temp dirs and configured extra dirs; blank/invalid entries are skipped. */
    fun allowedRoots(basePath: String?): List<Path> {
        val extra = listOfNotNull(System.getProperty(EXPORT_DIRS_PROPERTY), System.getenv(EXPORT_DIRS_ENV))
            .flatMap { it.split(File.pathSeparatorChar) }
        return (listOfNotNull(basePath, System.getProperty("java.io.tmpdir"), "/tmp") + extra)
            .mapNotNull { runCatching { Paths.get(it.trim()).takeIf { p -> it.isNotBlank() && p.isAbsolute } }.getOrNull() }
            .map(::canonical)
            .distinct()
    }

    /**
     * Resolves [value] (relative = against [basePath]) and validates extension, location and
     * existence. Throws [IllegalArgumentException] with an actionable message.
     */
    fun resolve(value: String, basePath: String?, overwrite: Boolean, roots: List<Path> = allowedRoots(basePath)): Path {
        val raw = Paths.get(value.trim())
        val path = (if (raw.isAbsolute || basePath == null) raw else Paths.get(basePath).resolve(raw)).normalize()
        if (formatOf(path) == null) throw IllegalArgumentException("outputFile must end with .csv, .json or .txt, got '$value'")
        val real = canonical(path)
        if (roots.none { real.startsWith(it) }) {
            throw IllegalArgumentException(
                "outputFile '$path' is outside the allowed export directories $roots. Write into the project, " +
                    "a temp/scratchpad dir, or ask the user to add the directory via -D$EXPORT_DIRS_PROPERTY=<dirs> (Help → Edit Custom VM Options)."
            )
        }
        checkWritable(path, overwrite)
        return path
    }

    fun checkWritable(path: Path, overwrite: Boolean) {
        if (Files.isDirectory(path)) throw IllegalArgumentException("outputFile '$path' is a directory")
        if (!overwrite && Files.exists(path)) {
            throw IllegalArgumentException("outputFile '$path' already exists — pass overwrite=true to replace it, or choose another name.")
        }
    }

    /** `<stem><suffix>.<ext>` next to [base]. */
    fun sibling(base: Path, suffix: String): Path {
        val name = base.fileName.toString()
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ".csv"
        return base.resolveSibling("$stem$suffix$ext")
    }

    /** One TXT line per row: tab-separated cells, line breaks inside a value folded to spaces. */
    fun txtLine(cells: List<String?>): String =
        cells.joinToString("\t") { v -> v?.replace(lineBreak, " ").orEmpty() }

    private val lineBreak = Regex("\r\n|\r|\n")

    /** Real path of the nearest existing ancestor plus the not-yet-created tail (resolves /tmp → /private/tmp). */
    private fun canonical(p: Path): Path {
        val abs = p.toAbsolutePath().normalize()
        var existing: Path? = abs
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        if (existing == null) return abs
        val real = runCatching { existing.toRealPath() }.getOrDefault(existing)
        return real.resolve(existing.relativize(abs)).normalize()
    }
}
