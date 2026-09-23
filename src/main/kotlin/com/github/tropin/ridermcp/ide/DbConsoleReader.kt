package com.github.tropin.ridermcp.ide

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

internal object DbConsoleReader {

    data class ResolvedConsole(val fileName: String, val dataSource: LocalDataSource)

    fun resolveConsole(project: Project, consoleName: String?): ResolvedConsole? {
        val files = FileEditorManager.getInstance(project).openFiles
        val candidates = if (consoleName.isNullOrBlank()) files.toList() else files.filter {
            it.name.equals(consoleName, ignoreCase = true)
        }
        for (file in candidates) {
            try {
                val console = JdbcConsoleProvider.getConsole(project, file) ?: continue
                val ds = try { console.dataSource } catch (_: Exception) { null } ?: continue
                return ResolvedConsole(file.name, ds)
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    fun openConsoleNames(project: Project): List<String> {
        return FileEditorManager.getInstance(project).openFiles.mapNotNull { file ->
            try {
                JdbcConsoleProvider.getConsole(project, file)?.let { file.name }
            } catch (_: Exception) {
                null
            }
        }
    }

    data class SqlResult(
        val resultSetId: String,
        val text: String,
        val rowCount: Int,
        val hasMore: Boolean
    )

    data class SqlStatementResult(
        val statementIndex: Int,
        val result: SqlResult? = null,
        val error: String? = null
    )

    private fun resolveDatabaseTarget(project: Project, ds: LocalDataSource, database: String): DasObject {
        val app = ApplicationManager.getApplication()
        val dbSource = app.runReadAction<DbDataSource?> {
            DbPsiFacade.getInstance(project).dataSources.firstOrNull { it.uniqueId == ds.uniqueId }
        } ?: throw IllegalStateException(
            "Data source '${ds.name}' is not visible in the Database tool window"
        )
        return app.runReadAction<DasObject?> {
            try {
                dbSource.findObjects(ObjectPath.create(database, ObjectKind.DATABASE)).firstOrNull()
            } catch (_: Exception) {
                null
            }
        } ?: throw IllegalStateException(
            "Database '$database' not found in the introspected model of '${ds.name}'. " +
                "Introspect it in Rider (Database tool window → Schemas → check '$database'), then retry."
        )
    }

    private fun countCsvRows(text: String, pageSize: Int): Pair<Int, Boolean> {
        if (text.isBlank() || text == "(empty result)") return 0 to false
        val lines = text.lines().filter { it.isNotBlank() }
        val dataRows = (lines.size - 1).coerceAtLeast(0)
        return dataRows to (dataRows >= pageSize)
    }

    internal fun splitSqlStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        val len = sql.length

        while (i < len) {
            val c = sql[i]
            when {
                c == '\'' -> {
                    current.append(c); i++
                    while (i < len) {
                        current.append(sql[i])
                        if (sql[i] == '\'') {
                            i++
                            if (i < len && sql[i] == '\'') { current.append('\''); i++ } else break
                        } else i++
                    }
                }
                c == '"' -> {
                    current.append(c); i++
                    while (i < len) { current.append(sql[i]); if (sql[i] == '"') { i++; break } else i++ }
                }
                c == '[' -> {
                    current.append(c); i++
                    while (i < len) { current.append(sql[i]); if (sql[i] == ']') { i++; break } else i++ }
                }
                c == '-' && i + 1 < len && sql[i + 1] == '-' -> {
                    while (i < len && sql[i] != '\n') { current.append(sql[i]); i++ }
                }
                c == '/' && i + 1 < len && sql[i + 1] == '*' -> {
                    current.append("/*"); i += 2
                    while (i < len) {
                        if (sql[i] == '*' && i + 1 < len && sql[i + 1] == '/') { current.append("*/"); i += 2; break }
                        current.append(sql[i]); i++
                    }
                }
                c == ';' -> {
                    val stmt = current.toString().trim()
                    if (stmt.isNotEmpty()) statements.add(stmt)
                    current.clear(); i++
                }
                else -> { current.append(c); i++ }
            }
        }
        val last = current.toString().trim()
        if (last.isNotEmpty()) statements.add(last)
        return statements
    }

    suspend fun executeSql(
        project: Project,
        ds: LocalDataSource,
        sql: String,
        pageSize: Int,
        database: String? = null
    ): List<SqlStatementResult> {
        val statements = splitSqlStatements(sql)
        if (statements.isEmpty()) throw IllegalStateException("No SQL statements to execute")

        val pkg = "com.intellij.database.ai.queryProcessing"
        try {
            val ctxCls = Class.forName("$pkg.QueryContext")
            val ctxCtor = ctxCls.constructors.firstOrNull()
                ?: throw ClassNotFoundException("QueryContext ctor")

            val dbName = database?.takeIf { it.isNotBlank() }
            val objectPath = if (dbName != null) ObjectPath.create(dbName, ObjectKind.DATABASE) else ObjectPath.CURRENT
            val target = dbName?.let { resolveDatabaseTarget(project, ds, it) }

            val procCls = Class.forName("$pkg.QueryProcessor")
            val processor = procCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val execMethod = procCls.methods.firstOrNull {
                it.name == "executeQuery" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Continuation::class.java
            } ?: throw ClassNotFoundException("executeQuery")

            val mapperKt = Class.forName("$pkg.QueryResultTextMapperKt")
            val csvMapper = mapperKt.methods.firstOrNull {
                it.name == "csvMapper" && it.parameterTypes.size == 1
            } ?: throw ClassNotFoundException("csvMapper")
            val mapped = csvMapper.invoke(null, project)
            val mapMethod = mapped::class.java.methods.firstOrNull { it.name == "map" }
                ?: throw ClassNotFoundException("map")

            val results = mutableListOf<SqlStatementResult>()

            for ((index, stmt) in statements.withIndex()) {
                try {
                    val ctx = try {
                        ctxCtor.newInstance(ds, objectPath, target, stmt, pageSize)
                    } catch (e: Exception) {
                        throw IllegalStateException(
                            "No database selected for this console. " +
                                "Select a database in the console toolbar dropdown, or pass database='<name>' explicitly. " +
                                "Use rider_list_db_consoles to check currentDatabase."
                        )
                    }
                    val raw = suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                        execMethod.invoke(processor, ctx, cont)
                    }
                    val rawCls = raw!!::class.java
                    val simple = rawCls.name

                    if (simple.endsWith("QueryResult\$Error")) {
                        val msg = rawCls.getMethod("getMessage").invoke(raw) as? String
                        val errorText = if (msg.isNullOrBlank() || msg == "Unknown error") {
                            "SQL error (no details from IDE). Common causes: insufficient permissions " +
                                "(e.g. VIEW SERVER STATE), unsupported syntax, or missing object. " +
                                "Try running the query directly in the Rider console for the full error. " +
                                "Statement: ${stmt.take(200)}"
                        } else msg
                        results.add(SqlStatementResult(index + 1, error = errorText))
                        break
                    }

                    if (!simple.endsWith("QueryResult\$Success")) {
                        results.add(SqlStatementResult(index + 1, error = "Unexpected result type: $simple"))
                        break
                    }

                    val resultSetId = rawCls.getMethod("getResultSetId").invoke(raw) as? String ?: ""
                    val textObj = mapMethod.invoke(mapped, raw)
                    val text = try {
                        textObj::class.java.getMethod("getText").invoke(textObj) as? String
                    } catch (_: Exception) { null } ?: "(empty result)"
                    val (rowCount, hasMore) = countCsvRows(text, pageSize)
                    results.add(SqlStatementResult(index + 1, SqlResult(resultSetId, text, rowCount, hasMore)))
                } catch (e: IllegalStateException) {
                    results.add(SqlStatementResult(index + 1, error = e.message ?: "Query failed"))
                    break
                } catch (e: Exception) {
                    val detail = e.message?.takeIf { it.isNotBlank() }
                        ?: e.cause?.message?.takeIf { it.isNotBlank() }
                        ?: e.javaClass.simpleName
                    results.add(SqlStatementResult(index + 1, error = "Statement ${index + 1} failed: $detail"))
                    break
                }
            }

            return results
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "SQL execution engine is not available in this IDE (needs the database AI module). " +
                    "Run the query from the open console in Rider instead."
            )
        }
    }

    fun listOpenConsoles(project: Project): String {
        val consoles = FileEditorManager.getInstance(project).openFiles.mapNotNull { file ->
            try {
                val console = JdbcConsoleProvider.getConsole(project, file) ?: return@mapNotNull null
                val ds = try { console.dataSource } catch (_: Exception) { null }
                val currentDb = try {
                    val sp = console.searchPath
                    val current = sp?.current
                    if (current != null && current != ObjectPath.CURRENT && current != ObjectPath.ROOT)
                        current.name?.takeIf { it.isNotBlank() }
                    else null
                } catch (_: Exception) { null }
                buildJsonObject {
                    put("console", file.name)
                    if (ds == null) {
                        put("dataSource", "(not attached)")
                    } else {
                        put("dataSource", ds.name)
                        put("uniqueId", ds.uniqueId)
                        if (currentDb != null) put("currentDatabase", currentDb)
                        try {
                            ds.dbms?.name?.takeIf { it.isNotBlank() }?.let { put("dbms", it) }
                        } catch (_: Exception) {}
                        try {
                            ds.url?.takeIf { it.isNotBlank() }?.let { put("url", com.github.tropin.ridermcp.maskJdbcSecrets(it)) }
                        } catch (_: Exception) {}
                        try {
                            ds.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
        if (consoles.isEmpty()) return "[]"
        return buildJsonObject {
            put("count", consoles.size)
            putJsonArray("consoles") { consoles.forEach { add(it) } }
        }.toString()
    }
}
