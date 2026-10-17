package com.github.tropin.ridermcp.ide

import java.rmi.RemoteException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlTextTest {

    // Splitting — T-SQL batches must keep variables alive across ';'.

    @Test
    fun `go mode keeps semicolon-separated T-SQL with variables in one batch`() {
        val sql = "declare @sql nvarchar(max); select @sql = 'select 1'; exec sp_executesql @sql"
        assertEquals(listOf(sql), SqlText.split(sql, SqlText.SplitMode.GO))
    }

    @Test
    fun `go mode splits on GO lines case-insensitively and honours repeat count`() {
        val sql = "select 1\ngo\nselect 2\n  GO 2  \nselect 3"
        assertEquals(listOf("select 1", "select 2", "select 2", "select 3"), SqlText.split(sql, SqlText.SplitMode.GO))
    }

    @Test
    fun `go inside literals comments and identifiers is not a separator`() {
        val sql = "select 'a\nGO\nb'\n/*\nGO\n*/\nselect [x\nGO] from t"
        assertEquals(listOf(sql), SqlText.split(sql, SqlText.SplitMode.GO))
    }

    @Test
    fun `go as part of an identifier on its own line is not a separator`() {
        val sql = "select 1\ngoto_label:\nselect 2"
        assertEquals(listOf(sql), SqlText.split(sql, SqlText.SplitMode.GO))
    }

    @Test
    fun `go line with trailing comment and CRLF still separates`() {
        assertEquals(listOf("select 1", "select 2"), SqlText.split("select 1\r\nGO -- next\r\nselect 2", SqlText.SplitMode.GO))
    }

    @Test
    fun `semicolon mode skips semicolons in literals and comments`() {
        val sql = "select 'a;b'; -- c;d\nselect 2; /* ; */ select 3;"
        assertEquals(
            listOf("select 'a;b'", "-- c;d\nselect 2", "/* ; */ select 3"),
            SqlText.split(sql, SqlText.SplitMode.SEMICOLON)
        )
    }

    @Test
    fun `none mode sends the whole script and drops blank input`() {
        assertEquals(listOf("select 1; select 2"), SqlText.split("  select 1; select 2  ", SqlText.SplitMode.NONE))
        assertEquals(emptyList(), SqlText.split("   ", SqlText.SplitMode.NONE))
    }

    @Test
    fun `split mode parsing accepts aliases and rejects junk`() {
        assertEquals(SqlText.SplitMode.AUTO, SqlText.parseSplitMode(null))
        assertEquals(SqlText.SplitMode.GO, SqlText.parseSplitMode("GO"))
        assertEquals(SqlText.SplitMode.NONE, SqlText.parseSplitMode("false"))
        assertNull(SqlText.parseSplitMode("lines"))
    }

    // Cells and CSV.

    @Test
    fun `long cells are cut with a marker telling how much was dropped`() {
        assertEquals("abc…[+3 chars]" to true, SqlText.truncateCell("abcdef", 3))
        assertEquals("abcdef" to false, SqlText.truncateCell("abcdef", 0))
        assertEquals("abc" to false, SqlText.truncateCell("abc", 3))
    }

    @Test
    fun `csv quotes only fields that need it`() {
        assertEquals("plain", SqlText.csvField("plain"))
        assertEquals("\"a,b\"", SqlText.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SqlText.csvField("say \"hi\""))
        assertEquals("\"line1\nline2\"", SqlText.csvField("line1\nline2"))
    }

    // Errors — the server text must survive remote wrappers.

    @Test
    fun `describe surfaces vendor code and SQLState through wrappers`() {
        val sql = SQLException("Invalid column name 'value'.", "S0001", 207)
        val wrapped = RemoteException("remote call failed", RuntimeException(sql))
        assertEquals("Invalid column name 'value'. [error 207, SQLState S0001]", SqlText.describe(wrapped))
    }

    @Test
    fun `describe masks JDBC credentials echoed by the driver`() {
        val e = SQLException("Login failed for jdbc:sqlserver://db:1433;user=sa;Password=s3cret;encrypt=false")
        assertEquals("Login failed for jdbc:sqlserver://db:1433;user=sa;Password=***;encrypt=false", SqlText.describe(e))
    }

    @Test
    fun `describe includes chained exceptions`() {
        val first = SQLException("first", "42000", 1)
        first.setNextException(SQLException("second"))
        assertEquals("first [error 1, SQLState 42000] | second", SqlText.describe(first))
    }

    /** Mimics com.microsoft.sqlserver.jdbc.SQLServerException, which is read reflectively. */
    class FakeServerError(private val severity: Int, private val line: Int) {
        fun getErrorSeverity(): Int = severity
        fun getLineNumber(): Int = line
    }

    class FakeSqlServerException(msg: String, private val err: FakeServerError) : SQLException(msg, "S0001", 207) {
        fun getSQLServerError(): FakeServerError = err
    }

    @Test
    fun `describe adds MSSQL severity and line when the driver exposes them`() {
        val e = FakeSqlServerException("Invalid column name 'identifier'.", FakeServerError(16, 3))
        assertEquals("Invalid column name 'identifier'. [error 207, SQLState S0001, severity 16, line 3]", SqlText.describe(RuntimeException(e)))
    }

    @Test
    fun `errorInfo returns structured fields of the server error`() {
        val info = SqlText.errorInfo(RemoteException("x", FakeSqlServerException("Invalid column name 'identifier'.", FakeServerError(16, 1))))
        assertEquals("Invalid column name 'identifier'.", info.message)
        assertEquals(207, info.errorCode)
        assertEquals("S0001", info.sqlState)
        assertEquals(16, info.severity)
        assertEquals(1, info.line)
    }

    @Test
    fun `errorInfo without a SQLException keeps the message and exception class`() {
        val info = SqlText.errorInfo(IllegalStateException())
        assertEquals("Unknown error", info.message)
        assertNull(info.errorCode)
        assertEquals("java.lang.IllegalStateException", info.exceptionClass)
    }

    @Test
    fun `describe names the exception class when no message exists`() {
        assertEquals("Unknown error (java.lang.IllegalStateException)", SqlText.describe(IllegalStateException()))
    }

    @Test
    fun `describe falls back to the first non-blank message in the cause chain`() {
        assertEquals("boom", SqlText.describe(RuntimeException("", IllegalStateException("boom"))))
    }

    @Test
    fun `timeouts are detected by type and by driver message`() {
        assertTrue(SqlText.isTimeout(RuntimeException(SQLTimeoutException("x"))))
        assertTrue(SqlText.isTimeout(SQLException("The query has timed out.")))
        assertFalse(SqlText.isTimeout(SQLException("Invalid object name 't'.")))
    }
}
