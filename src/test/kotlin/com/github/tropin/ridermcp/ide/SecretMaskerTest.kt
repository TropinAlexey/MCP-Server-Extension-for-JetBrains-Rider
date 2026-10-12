package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.ide.SecretMasker.ColumnKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretMaskerTest {

    @Test
    fun `columns are classified by name`() {
        assertEquals(ColumnKind.SECRET, SecretMasker.classifyColumn("Password"))
        assertEquals(ColumnKind.SECRET, SecretMasker.classifyColumn("api_key"))
        assertEquals(ColumnKind.SECRET, SecretMasker.classifyColumn("refresh_token"))
        assertEquals(ColumnKind.CONNECTION, SecretMasker.classifyColumn("connection_string"))
        assertEquals(ColumnKind.CONNECTION, SecretMasker.classifyColumn("ConnectionString"))
        assertEquals(ColumnKind.PLAIN, SecretMasker.classifyColumn("name"))
    }

    @Test
    fun `secret columns are fully masked`() {
        assertEquals("***" to true, SecretMasker.maskCell("hunter2", ColumnKind.SECRET))
        assertEquals("" to false, SecretMasker.maskCell("", ColumnKind.SECRET))
    }

    @Test
    fun `connection strings keep everything but the password`() {
        val (masked, hit) = SecretMasker.maskCell(
            "Server=10.0.0.1;Database=lakmus;User Id=app;Password=S3cr;et!;Encrypt=true", ColumnKind.CONNECTION
        )
        assertTrue(hit)
        assertEquals("Server=10.0.0.1;Database=lakmus;User Id=app;Password=***;et!;Encrypt=true", masked)
        assertEquals("Host=h;Pwd=***;", SecretMasker.maskText("Host=h;Pwd={a;b};"))
        assertEquals("Password=***;x=1", SecretMasker.maskText("Password=\"p;w\";x=1"))
    }

    @Test
    fun `free text secrets are masked`() {
        assertEquals("jdbc:sqlserver://h;user=sa;password=***", SecretMasker.maskText("jdbc:sqlserver://h;user=sa;password=pw"))
        assertEquals("""{"user":"a","password":"***"}""", SecretMasker.maskText("""{"user":"a","password":"p\"w"}"""))
        assertEquals("postgres://u:***@host/db", SecretMasker.maskText("postgres://u:pw@host/db"))
        assertEquals("api_key=***&x=1", SecretMasker.maskText("api_key=abc&x=1"))
        val (plain, hit) = SecretMasker.maskCell("no secrets here", ColumnKind.PLAIN)
        assertFalse(hit)
        assertEquals("no secrets here", plain)
    }
}

class DbEditorPathsTest {

    @Test
    fun `table tab path is parsed`() {
        val tab = DbEditorPaths.parse("99f01a52-1111-2222/database/lakmus_master_temp/schema/dbo/table/instances")!!
        assertEquals("99f01a52-1111-2222", tab.dataSourceId)
        assertEquals("lakmus_master_temp", tab.database)
        assertEquals("dbo", tab.schema)
        assertEquals("instances", tab.objectName)
        assertEquals("table", tab.kind)
    }

    @Test
    fun `schema-only and view paths`() {
        val view = DbEditorPaths.parse("/id/schema/app/view/v%20x")!!
        assertNull(view.database)
        assertEquals("app", view.schema)
        assertEquals("v x", view.objectName)
        assertEquals("view", view.kind)
        assertNull(DbEditorPaths.parse("/Users/mac/console.sql"))
        assertNull(DbEditorPaths.parse("id/database"))
    }

    @Test
    fun `database is read from JDBC urls`() {
        assertEquals("lakmus", DbEditorPaths.databaseFromUrl("jdbc:sqlserver://h:1433;databaseName=lakmus;encrypt=false"))
        assertNull(DbEditorPaths.databaseFromUrl("jdbc:sqlserver://h:1433"))
        assertEquals("app", DbEditorPaths.databaseFromUrl("jdbc:postgresql://h:5432/app?ssl=true"))
        assertEquals("db", DbEditorPaths.databaseFromUrl("jdbc:jtds:sqlserver://h:1433/db;user=x"))
        assertNull(DbEditorPaths.databaseFromUrl("jdbc:postgresql://h:5432/"))
    }
}
