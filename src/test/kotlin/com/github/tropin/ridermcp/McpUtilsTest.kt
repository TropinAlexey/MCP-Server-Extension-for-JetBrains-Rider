package com.github.tropin.ridermcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpUtilsTest {

    // maskJdbcSecrets — behavior contract: credentials never reach tool responses.

    @Test
    fun `masks password param but keeps the rest of the url`() {
        assertEquals(
            "jdbc:postgresql://db:5432/app?password=***&ssl=true",
            maskJdbcSecrets("jdbc:postgresql://db:5432/app?password=s3cret&ssl=true")
        )
    }

    @Test
    fun `masks userinfo embedded in authority`() {
        assertEquals(
            "jdbc:postgresql://***@db:5432/app",
            maskJdbcSecrets("jdbc:postgresql://bob:s3cr3t@db:5432/app")
        )
    }

    @Test
    fun `leaves clean urls untouched`() {
        assertEquals("jdbc:sqlite:/tmp/app.db", maskJdbcSecrets("jdbc:sqlite:/tmp/app.db"))
        assertEquals("hello", maskJdbcSecrets("hello"))
    }

    // paginateLines — paging, grep and coordinate contracts.

    @Test
    fun `head read returns first lines with local coordinates`() {
        val r = paginateLines(listOf("a", "b", "c"), maxLines = 2)
        assertEquals(listOf("a", "b"), r.lines)
        assertEquals(3, r.totalLines)
        assertEquals(0, r.returnedFrom)
        assertEquals(1, r.returnedTo)
        assertTrue(r.truncated)
    }

    @Test
    fun `fromEnd returns tail`() {
        val r = paginateLines(listOf("a", "b", "c"), maxLines = 2, fromEnd = true)
        assertEquals(listOf("b", "c"), r.lines)
        assertEquals(1, r.returnedFrom)
        assertEquals(2, r.returnedTo)
    }

    @Test
    fun `offset pages forward`() {
        val r = paginateLines(listOf("a", "b", "c"), maxLines = 1, offset = 1)
        assertEquals(listOf("b"), r.lines)
    }

    @Test
    fun `pattern filters with session-wide 1-based prefixes`() {
        val r = paginateLines(listOf("a", "b", "c"), maxLines = 10, pattern = "b|c")
        assertEquals(listOf("[2] b", "[3] c"), r.lines)
        assertEquals(2, r.matchedLines)
        assertEquals(3, r.totalLines)
    }

    @Test
    fun `invalid regex falls back to literal match`() {
        val r = paginateLines(listOf("a[0]", "b"), maxLines = 10, pattern = "[")
        assertEquals(listOf("[1] a[0]"), r.lines)
        assertEquals(1, r.matchedLines)
    }

    @Test
    fun `overlong pattern matches literally to stop ReDoS`() {
        val literal = "a|b".repeat(160) // 480 chars > 300 cap; as regex it would match "a" too
        val r = paginateLines(listOf("a", literal), maxLines = 10, pattern = literal)
        assertEquals(1, r.matchedLines)
        assertEquals(listOf("[2] $literal"), r.lines)
    }

    @Test
    fun `global coordinates reported for incremental reads`() {
        val r = paginateLines(listOf("x", "y"), maxLines = 0, baseLine = 10, globalTotal = 12)
        assertEquals(12, r.totalLines)
        assertEquals(10, r.returnedFrom)
        assertEquals(11, r.returnedTo)
        assertFalse(r.truncated)
    }

    @Test
    fun `empty input is not truncated`() {
        val r = paginateLines(emptyList(), maxLines = 5)
        assertTrue(r.lines.isEmpty())
        assertFalse(r.truncated)
    }

    @Test
    fun `offset and fromEnd together fail`() {
        var failed = false
        try {
            paginateLines(listOf("a"), maxLines = 1, offset = 0, fromEnd = true)
        } catch (_: Throwable) {
            failed = true
        }
        assertTrue(failed)
    }
}
