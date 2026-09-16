package com.github.tropin.ridermcp.admin

import com.intellij.ide.InvalidateCacheService
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class AdminToolset : McpToolset {

    @McpTool
    @McpDescription("Invalidates IDE caches and restarts Rider. Use when IDE shows stale state: wrong syntax highlighting, missing references, broken code completion, indexing stuck, or phantom errors. This is the 'nuclear option' for IDE glitches.")
    suspend fun rider_invalidate_caches(): String {
        val project = coroutineContext.project
        ApplicationManager.getApplication().invokeLater {
            InvalidateCacheService.invalidateCachesAndRestart(project)
        }
        return buildJsonObject { put("status", "invalidating_and_restarting") }.toString()
    }
}
