package com.github.tropin.ridermcp.ide

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

// All database-plugin references are isolated here. This class is loaded only
// when rider_list_db_consoles actually runs, keeping MCP tool registration
// (which happens on the EDT at startup) free of DatabaseTools class loading.
internal object DbConsoleReader {

    data class ResolvedConsole(val fileName: String, val dataSource: LocalDataSource)

    // Finds an open console by file name (or the first open console when null)
    // and returns it with its attached data source. Null when not found.
    fun resolveConsole(project: Project, consoleName: String?): ResolvedConsole? {
        val files = FileEditorManager.getInstance(project).openFiles
        val candidates = if (consoleName.isNullOrBlank()) files.toList() else files.filter {
            it.name.equals(consoleName, ignoreCase = true)
        }
        for (file in candidates) {
            try {
                val console = JdbcConsoleProvider.getConsole(project, file) ?: continue
                val ds = try {
                    console.dataSource
                } catch (_: Exception) {
                    null
                } ?: continue
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

    data class SqlResult(val resultSetId: String, val text: String)

    // Resolves the DasObject of a database by name inside the introspected
    // model of the given data source. Runs on the caller's thread (off EDT)
    // with read actions. Throws an honest IllegalStateException when the
    // database is not introspected — auto-picking is deliberately NOT done.
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

    // Executes SQL against the given data source via the IDE's own query
    // pipeline (the same one behind the stock execute_sql_query, but without
    // its introspection requirement). The `ai` query-processing module is not
    // on this plugin's compile classpath (and may be absent on older IDEs),
    // so it is accessed reflectively; absence degrades into an honest error.
    // Must be called OFF the EDT (network I/O, suspending).
    suspend fun executeSql(
        project: Project,
        ds: LocalDataSource,
        sql: String,
        pageSize: Int,
        database: String? = null
    ): SqlResult {
        val pkg = "com.intellij.database.ai.queryProcessing"
        try {
            val ctxCls = Class.forName("$pkg.QueryContext")
            val ctxCtor = ctxCls.constructors.firstOrNull()
                ?: throw ClassNotFoundException("QueryContext ctor")
            // objectPath is the execution database. Explicit name wins (no
            // wrong-DB guessing); otherwise CURRENT = the console's own
            // context (its toolbar dropdown).
            // QueryContext's Kotlin ctor rejects nulls for BOTH objectPath and
            // target (DasObject of the execution database), so an explicit
            // database is resolved to its DasObject via the introspected model.
            val dbName = database?.takeIf { it.isNotBlank() }
            val objectPath = if (dbName != null) ObjectPath.create(dbName, ObjectKind.DATABASE) else ObjectPath.CURRENT
            val target = dbName?.let { resolveDatabaseTarget(project, ds, it) }
            val ctx = try {
                ctxCtor.newInstance(ds, objectPath, target, sql, pageSize)
            } catch (e: Exception) {
                // QueryContext requires a non-null objectPath = the execution
                // database. Auto-picking a database is deliberately NOT done:
                // running SQL against an unintended DB is worse than failing.
                // Human selects it once in the console toolbar, or the caller
                // passes it explicitly via the database parameter.
                throw IllegalStateException(
                    "No execution database resolved" +
                        (if (!database.isNullOrBlank()) " for '$database'" else " (console toolbar has none selected)") +
                        ". Select a database in the console toolbar dropdown, or pass database='<name>' " +
                        "explicitly, then retry — the tool never guesses the database. Details: " +
                        (e.cause?.message ?: e.message ?: e.javaClass.simpleName)
                )
            }
            val procCls = Class.forName("$pkg.QueryProcessor")
            val processor = procCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val execMethod = procCls.methods.firstOrNull {
                it.name == "executeQuery" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Continuation::class.java
            } ?: throw ClassNotFoundException("executeQuery")
            val raw = suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                execMethod.invoke(processor, ctx, cont)
            }
            val rawCls = raw!!::class.java
            val simple = rawCls.name
            if (simple.endsWith("QueryResult\$Error")) {
                val msg = rawCls.getMethod("getMessage").invoke(raw) as? String
                throw IllegalStateException(msg ?: "Query failed")
            }
            if (!simple.endsWith("QueryResult\$Success")) {
                throw IllegalStateException("Unexpected query result: $simple")
            }
            val resultSetId = rawCls.getMethod("getResultSetId").invoke(raw) as? String ?: ""
            val mapperKt = Class.forName("$pkg.QueryResultTextMapperKt")
            val csvMapper = mapperKt.methods.firstOrNull {
                it.name == "csvMapper" && it.parameterTypes.size == 1
            } ?: throw ClassNotFoundException("csvMapper")
            val mapped = csvMapper.invoke(null, project)
            val mapMethod = mapped::class.java.methods.firstOrNull { it.name == "map" }
                ?: throw ClassNotFoundException("map")
            val textObj = mapMethod.invoke(mapped, raw)
            val text = try {
                textObj::class.java.getMethod("getText").invoke(textObj) as? String
            } catch (_: Exception) {
                null
            } ?: "(empty result)"
            return SqlResult(resultSetId, text)
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
                val ds = try {
                    console.dataSource
                } catch (_: Exception) {
                    null
                }
                buildJsonObject {
                    put("console", file.name)
                    if (ds == null) {
                        put("dataSource", "(not attached)")
                    } else {
                        put("dataSource", ds.name)
                        put("uniqueId", ds.uniqueId)
                        try {
                            ds.dbms?.name?.takeIf { it.isNotBlank() }?.let { put("dbms", it) }
                        } catch (_: Exception) {
                        }
                        try {
                            ds.url?.takeIf { it.isNotBlank() }?.let { put("url", it) }
                        } catch (_: Exception) {
                        }
                        try {
                            ds.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                        } catch (_: Exception) {
                        }
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
