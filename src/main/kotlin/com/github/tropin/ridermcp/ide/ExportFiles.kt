package com.github.tropin.ridermcp.ide

import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

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
            .mapNotNull { runCatching { canonical(it) }.getOrNull() }
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
        // A link could point outside the allowed roots (a dangling one passes the containment check).
        if (Files.isSymbolicLink(path)) throw IllegalArgumentException("outputFile '$path' is a symbolic link — choose a regular file path.")
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalArgumentException("outputFile '$path' is a directory")
        if (!overwrite && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("outputFile '$path' already exists — pass overwrite=true to replace it, or choose another name.")
        }
    }

    /**
     * Opens [path] for writing without following a link at the last component; without [overwrite]
     * the file must not exist (atomic CREATE_NEW).
     */
    fun newWriter(path: Path, overwrite: Boolean): Writer {
        checkWritable(path, overwrite)
        path.parent?.let { Files.createDirectories(it) }
        val create = if (overwrite) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING) else arrayOf(StandardOpenOption.CREATE_NEW)
        return Files.newBufferedWriter(path, Charsets.UTF_8, *create, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
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

    /**
     * Real path of the nearest existing ancestor plus the not-yet-created tail (resolves /tmp → /private/tmp).
     * Links count as existing, so a dangling link is resolved (and rejected) instead of skipped.
     */
    private fun canonical(p: Path): Path {
        val abs = p.toAbsolutePath().normalize()
        var existing: Path? = abs
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent
        if (existing == null) return abs
        val real = try {
            existing.toRealPath()
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("outputFile path '$abs' goes through an unresolvable link '$existing'")
        }
        return real.resolve(existing.relativize(abs)).normalize()
    }
}
