package com.github.tropin.ridermcp.ide

import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.util.LoaderContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fire-and-forget introspection (⟳ in the Database tool window).
 *
 * Calls the suspend `tryPerformSync` with every argument spelled out: the `tryPerformAsync` family is
 * deprecated, and its synthetic `$default` bridge is gone in 263, so omitting a default argument breaks
 * binary compatibility there.
 */
@Service(Service.Level.APP)
internal class DataSourceRefresher(private val cs: CoroutineScope) {

    fun start(project: Project, ds: LocalDataSource) {
        cs.launch {
            try {
                DataSourceSyncManager.getInstance().tryPerformSync(LoaderContext.selectGeneralTask(project, ds), false, true, null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LOG.warn("Refresh of data source '${ds.name}' failed", e)
            }
        }
    }

    companion object {
        private val LOG = logger<DataSourceRefresher>()

        fun getInstance(): DataSourceRefresher = service()
    }
}
