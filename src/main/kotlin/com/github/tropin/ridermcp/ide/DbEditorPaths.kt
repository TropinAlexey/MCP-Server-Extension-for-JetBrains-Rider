package com.github.tropin.ridermcp.ide

import java.net.URLDecoder

/** Pure helpers describing database editor tabs — no IDE dependencies, unit-tested. */
internal object DbEditorPaths {

    data class DbObjectTab(
        val dataSourceId: String,
        val database: String?,
        val schema: String?,
        val objectName: String?,
        val kind: String
    )

    /**
     * Parses the virtual path of a table/view editor tab, e.g.
     * `99f01a52-…/database/lakmus_master_temp/schema/dbo/table/instances` (kind/name pairs after the data source id).
     * Returns null when the path does not have that shape.
     */
    fun parse(path: String): DbObjectTab? {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.size < 3 || (segments.size - 1) % 2 != 0) return null
        val pairs = segments.drop(1).chunked(2).map { (kind, name) -> kind.lowercase() to decode(name) }
        if (pairs.none { it.first in containerKinds }) return null
        var database: String? = null
        var schema: String? = null
        for ((kind, name) in pairs) {
            when (kind) {
                "database", "catalog" -> database = name
                "schema" -> schema = name
            }
        }
        val (lastKind, lastName) = pairs.last()
        val isContainer = lastKind in containerKinds
        return DbObjectTab(
            dataSourceId = segments[0],
            database = database,
            schema = if (lastKind == "schema") lastName else schema,
            objectName = if (isContainer) null else lastName,
            kind = lastKind
        )
    }

    private val containerKinds = setOf("database", "catalog", "schema")

    private fun decode(name: String): String =
        if ('%' in name) runCatching { URLDecoder.decode(name, Charsets.UTF_8) }.getOrDefault(name) else name

    /** Database named in a JDBC URL (MSSQL `databaseName=`/`database=`, jTDS/PostgreSQL/MySQL path), or null. */
    fun databaseFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        Regex("(?i)[;?&](?:databaseName|database)=([^;&]+)").find(url)?.let { return it.groupValues[1].trim().takeIf { n -> n.isNotEmpty() } }
        if (url.startsWith("jdbc:sqlserver:", ignoreCase = true)) return null
        val afterHost = Regex("^jdbc:[a-z0-9:]+://[^/]+/([^?;/]+)", RegexOption.IGNORE_CASE).find(url) ?: return null
        return decode(afterHost.groupValues[1]).takeIf { it.isNotBlank() }
    }
}
