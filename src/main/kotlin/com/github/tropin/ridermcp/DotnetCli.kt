package com.github.tropin.ridermcp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project

/**
 * `dotnet` command lines for the CLI configured in Rider (Settings → Build, Execution, Deployment →
 * Toolset and Build), so users without `dotnet` on PATH — or with several SDKs — get the same CLI
 * the IDE builds with. Falls back to `dotnet` on PATH when no runtime is detected.
 * 
 * RiderDotNetActiveRuntimeHost is accessed via reflection to avoid hard dependency on Rider classes.
 */
internal object DotnetCli {

    fun exePath(project: Project): String =
        runCatching {
            val hostClass = Class.forName("com.jetbrains.rider.runtime.RiderDotNetActiveRuntimeHost")
            val host = hostClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val runtime = hostClass.getMethod("getDotNetCoreRuntime").invoke(host)
            // runtime is a PersistentValue<RuntimeInfo>; .value returns RuntimeInfo
            val runtimeValue = runtime.javaClass.getMethod("getValue").invoke(runtime)
            if (runtimeValue != null) {
                runtimeValue.javaClass.getMethod("getCliExePath").invoke(runtimeValue) as? String
            } else null
        }.getOrNull() ?: "dotnet"

    /** GeneralCommandLine loads the user's shell environment, unlike a bare ProcessBuilder (macOS apps started from the Dock). */
    fun commandLine(project: Project, args: List<String>): GeneralCommandLine =
        GeneralCommandLine(listOf(exePath(project)) + args)
}
