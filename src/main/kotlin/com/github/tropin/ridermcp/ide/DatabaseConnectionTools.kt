package com.github.tropin.ridermcp.ide

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
        "Creates a new database connection in the IDE Database tool window. " +
            "After creation, the connection is available for SQL execution via rider_execute_console and other database tools. " +
            "Supported DBMS: PostgreSQL, MySQL, MariaDB, Oracle, SQL Server (Microsoft SQL Server / MSSQL), " +
            "SQLite, H2, DB2, Sybase, Cassandra, ClickHouse, CockroachDB, Derby, Exasol, Greenplum, " +
            "HyperSQL, Redshift, Snowflake, Vertica, MongoDB, and others — pass the common name and the tool " +
            "fuzzy-matches it to the IDE's built-in driver list. " +
            "The url must be a JDBC URL matching the driver (e.g. jdbc:sqlserver://host:1433;databaseName=mydb " +
            "for SQL Server, jdbc:postgresql://host:5432/mydb for PostgreSQL). " +
            "Password is stored in the IDE credential store (never logged). " +
            "Returns the connection's uniqueId — pass it as connectionId to execute_sql_query and other database tools."
    )
    suspend fun rider_create_database_connection(
        @McpDescription("DBMS type — common name like 'PostgreSQL', 'SQL Server', 'MySQL', 'Oracle', 'SQLite', etc. Case-insensitive, fuzzy-matched to built-in drivers") dbms: String,
        @McpDescription("JDBC URL for the connection (e.g. jdbc:sqlserver://host:1433;databaseName=mydb)") url: String,
        @McpDescription("Display name for the connection in the Database tool window (e.g. 'Production MSSQL')") name: String,
        @McpDescription("Database username (omit for auth methods that don't require it)") user: String? = null,
        @McpDescription("Database password (stored in IDE credential store, never logged)") password: String? = null
    ): String {
        val project = coroutineContext.project

        val driver = runOnEdt {
            val driverManager = DatabaseDriverManager.getInstance()
            val allDrivers = driverManager.drivers.filter { it.isPredefined }

            val normalized = dbms.trim().lowercase()

            // 1. Exact match by driver name (case-insensitive)
            allDrivers.firstOrNull { it.name.lowercase() == normalized }
                // 2. Match by URL pattern
                ?: allDrivers.firstOrNull { it.matchesUrl(url) }
                // 3. Fuzzy match: driver name contains the query or vice versa
                ?: allDrivers.firstOrNull {
                    val dn = it.name.lowercase()
                    dn.contains(normalized) || normalized.contains(dn)
                }
                // 4. Common aliases
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
            put("url", url.replace(Regex("password=[^;&]+"), "password=***"))
            if (!user.isNullOrBlank()) put("username", user)
            put("hint", "Connection added to Database tool window. Use rider_list_db_consoles or execute_sql_query with connectionId='${ds.uniqueId}' to run queries.")
        }.toString()
    }

    @McpTool
    @McpDescription(
        "Edits an existing database connection in the IDE. " +
            "Pass the connectionId (uniqueId from list_database_connections or rider_list_db_consoles) " +
            "and only the fields you want to change — omitted fields keep their current values."
    )
    suspend fun rider_edit_database_connection(
        @McpDescription("The uniqueId of the connection to edit (from list_database_connections or rider_list_db_consoles)") connectionId: String,
        @McpDescription("New JDBC URL (omit to keep current)") url: String? = null,
        @McpDescription("New display name (omit to keep current)") name: String? = null,
        @McpDescription("New username (omit to keep current)") user: String? = null,
        @McpDescription("New password (omit to keep current)") password: String? = null
    ): String {
        val project = coroutineContext.project

        val ds = runOnEdt {
            LocalDataSourceManager.getInstance(project).dataSources
                .firstOrNull { it.uniqueId == connectionId }
        } ?: mcpFail("No connection found with id '$connectionId'. Use list_database_connections to see available connections.")

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
            ds.url?.let { put("url", it.replace(Regex("password=[^;&]+"), "password=***")) }
            ds.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
        }.toString()
    }
}
