package com.github.tropin.ridermcp.ide

import com.github.tropin.ridermcp.maskJdbcSecrets
import com.github.tropin.ridermcp.runOnEdt
import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class DatabaseConnectionToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages IDE database connections (data sources). action='create' (default) requires dbms + JDBC url + name; optional user/password (password goes to the IDE credential store, never logged). " +
            "action='edit' requires connectionId; optional url/name/user/password. " +
            "DBMS is matched fuzzily (PostgreSQL, MySQL, MariaDB, Oracle, SQL Server/MSSQL, SQLite, H2, DB2, ClickHouse, MongoDB, ...) with URL-vs-driver validation on create; edit sanity-checks the URL against known drivers (to switch DBMS, create a new connection). " +
            "Credentials in URLs are masked in responses (password= and userinfo). " +
            "Returns uniqueId (connectionId for the built-in query tools). To run SQL the rider_* way, open this connection's console in the Database tool window, then use rider_list_db_consoles + rider_execute_console(console=...). " +
            "Prefer rider_* over the built-in create_database_connection/edit_database_connection (broken MSSQL matching, undocumented dbms enum). " +
            "This only manages connections — it never executes SQL."
    )
    suspend fun rider_database_connection(
        @McpDescription("Action: create (default), edit") action: String = "create",
        @McpDescription("DBMS display name, e.g. 'PostgreSQL', 'SQL Server', 'MySQL' (required for create; fuzzy-matched, aliases mssql/postgres/maria/mongo accepted)") dbms: String? = null,
        @McpDescription("JDBC URL, must match the driver (required for create; e.g. driver sample URL is shown on mismatch)") url: String? = null,
        @McpDescription("Connection display name, must be unique (required for create)") name: String? = null,
        @McpDescription("DB username (optional)") user: String? = null,
        @McpDescription("DB password — stored in IDE credential store, never logged or echoed (optional)") password: String? = null,
        @McpDescription("Connection uniqueId from create (required for edit)") connectionId: String? = null
    ): String {
        return when (action.lowercase()) {
            "create" -> createConnection(dbms, url, name, user, password)
            "edit" -> editConnection(connectionId, url, name, user, password)
            else -> mcpFail("Unknown action '$action'. Use: create, edit")
        }
    }

    private suspend fun createConnection(dbms: String?, url: String?, name: String?, user: String?, password: String?): String {
        if (dbms.isNullOrBlank()) mcpFail("dbms is required for action='create'")
        if (url.isNullOrBlank()) mcpFail("url is required for action='create'")
        if (name.isNullOrBlank()) mcpFail("name is required for action='create'")

        val project = coroutineContext.project

        val driver = runOnEdt {
            val driverManager = DatabaseDriverManager.getInstance()
            val allDrivers = driverManager.drivers.filter { it.isPredefined }

            val normalized = dbms.trim().lowercase()

            allDrivers.firstOrNull { it.name.lowercase() == normalized }
                ?: allDrivers.firstOrNull { it.matchesUrl(url) }
                ?: allDrivers.firstOrNull {
                    val dn = it.name.lowercase()
                    dn.contains(normalized) || normalized.contains(dn)
                }
                ?: run {
                    val aliasMap = mapOf(
                        "mssql" to "sql server",
                        "microsoft sql server" to "sql server",
                        "ms sql" to "sql server",
                        "sqlserver" to "sql server",
                        "postgres" to "postgresql",
                        "maria" to "mariadb",
                        "mongo" to "mongodb",
                        "cockroach" to "cockroachdb"
                    )
                    val alias = aliasMap[normalized]
                    if (alias != null) {
                        allDrivers.firstOrNull { it.name.lowercase().contains(alias) }
                    } else null
                }
        } ?: mcpFail(buildString {
            append("No driver found for DBMS '$dbms'. ")
            append("Available drivers: ")
            val names = runOnEdt {
                DatabaseDriverManager.getInstance().drivers
                    .filter { it.isPredefined }
                    .map { it.name }
                    .sorted()
            }
            append(names.joinToString(", "))
        })

        if (!driver.matchesUrl(url)) {
            mcpFail(
                "URL '$url' is not valid for driver '${driver.name}'. " +
                    "Expected format example: ${driver.sampleUrl ?: "(no sample available)"}"
            )
        }

        val existingNames = runOnEdt {
            LocalDataSourceManager.getInstance(project).dataSources.map { it.name }
        }
        if (name in existingNames) {
            mcpFail("A data source named '$name' already exists. Choose a different name.")
        }

        val ds = runOnEdt {
            val dataSource = LocalDataSource.fromDriver(driver, url, false)
            dataSource.name = name
            if (!user.isNullOrBlank()) {
                dataSource.username = user
            }
            dataSource.isConfiguredByUrl = true

            if (!password.isNullOrBlank()) {
                DatabaseCredentials.getInstance().storePassword(dataSource, OneTimeString(password))
            }

            LocalDataSourceManager.getInstance(project).addDataSource(dataSource)
            dataSource
        }

        return buildJsonObject {
            put("status", "created")
            put("name", ds.name)
            put("uniqueId", ds.uniqueId)
            put("driver", driver.name)
            put("url", maskJdbcSecrets(url))
            if (!user.isNullOrBlank()) put("username", user)
            put("hint", "Connection added to Database tool window. Open its console there, then use rider_list_db_consoles + rider_execute_console(console='<fileName>'); the built-in query tools take connectionId='${ds.uniqueId}'.")
        }.toString()
    }

    private suspend fun editConnection(connectionId: String?, url: String?, name: String?, user: String?, password: String?): String {
        if (connectionId.isNullOrBlank()) mcpFail("connectionId is required for action='edit'")
        val project = coroutineContext.project

        val ds = runOnEdt {
            LocalDataSourceManager.getInstance(project).dataSources
                .firstOrNull { it.uniqueId == connectionId }
        } ?: mcpFail("No connection found with id '$connectionId'. Use list_database_connections to see available connections.")

        if (!url.isNullOrBlank()) {
            val known = runOnEdt {
                DatabaseDriverManager.getInstance().drivers.any { it.isPredefined && it.matchesUrl(url) }
            }
            if (!known) {
                mcpFail("No known driver matches URL '$url'. Edit keeps the current driver — to switch DBMS, create a new connection instead (action='create' re-validates driver match).")
            }
        }

        runOnEdt {
            if (!url.isNullOrBlank()) {
                ds.setUrlSmart(url)
            }
            if (!name.isNullOrBlank()) {
                ds.name = name
            }
            if (!user.isNullOrBlank()) {
                ds.username = user
            }
            if (!password.isNullOrBlank()) {
                DatabaseCredentials.getInstance().storePassword(ds, OneTimeString(password))
            }
            LocalDataSourceManager.getInstance(project).fireDataSourceUpdated(ds)
        }

        return buildJsonObject {
            put("status", "updated")
            put("name", ds.name)
            put("uniqueId", ds.uniqueId)
            ds.url?.let { put("url", maskJdbcSecrets(it)) }
            ds.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
        }.toString()
    }
}
