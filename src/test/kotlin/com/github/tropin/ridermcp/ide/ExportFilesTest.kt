package com.github.tropin.ridermcp.ide

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportFilesTest {

    private fun tempRoot(): Path = Files.createTempDirectory("export-files-test").toRealPath()

    @Test
    fun `format follows the extension`() {
        assertEquals(ExportFiles.Format.TXT, ExportFiles.formatOf(Path.of("/x/urls.TXT")))
        assertEquals(ExportFiles.Format.JSON, ExportFiles.formatOf(Path.of("a.json")))
        assertEquals(null, ExportFiles.formatOf(Path.of("a.xlsx")))
    }

    @Test
    fun `relative paths resolve against the project and stay inside it`() {
        val root = tempRoot()
        val p = ExportFiles.resolve("out/urls.txt", root.toString(), overwrite = false, roots = listOf(root))
        assertEquals(root.resolve("out/urls.txt"), p)
    }

    @Test
    fun `paths outside the allowed roots are rejected`() {
        val root = tempRoot()
        val e = assertFailsWith<IllegalArgumentException> {
            ExportFiles.resolve("../escape.csv", root.toString(), overwrite = false, roots = listOf(root))
        }
        assertTrue("outside the allowed export directories" in e.message!!)
    }

    @Test
    fun `existing files need overwrite`() {
        val root = tempRoot()
        val file = Files.writeString(root.resolve("r.csv"), "a\n")
        assertFailsWith<IllegalArgumentException> { ExportFiles.resolve(file.toString(), null, overwrite = false, roots = listOf(root)) }
        assertEquals(file, ExportFiles.resolve(file.toString(), null, overwrite = true, roots = listOf(root)))
    }

    @Test
    fun `unknown extensions are rejected`() {
        val root = tempRoot()
        assertFailsWith<IllegalArgumentException> { ExportFiles.resolve("r.xlsx", root.toString(), overwrite = false, roots = listOf(root)) }
    }

    @Test
    fun `siblings keep the extension`() {
        assertEquals(Path.of("/t/urls-1-2.txt"), ExportFiles.sibling(Path.of("/t/urls.txt"), "-1-2"))
    }

    @Test
    fun `txt line is tab-separated with folded line breaks and empty nulls`() {
        assertEquals("a b c\t\tx", ExportFiles.txtLine(listOf("a\nb\r\nc", null, "x")))
    }
}
