package com.github.tropin.ridermcp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.jetbrains.rider.runtime.RiderDotNetActiveRuntimeHost

/**
 * `dotnet` command lines for the CLI configured in Rider (Settings → Build, Execution, Deployment →
 * Toolset and Build), so users without `dotnet` on PATH — or with several SDKs — get the same CLI
 * the IDE builds with. Falls back to `dotnet` on PATH when no runtime is detected.
 */
internal object DotnetCli {

    fun exePath(project: Project): String =
        runCatching { RiderDotNetActiveRuntimeHost.getInstance(project).dotNetCoreRuntime.value?.cliExePath?.toString() }
            .getOrNull() ?: "dotnet"

    /** GeneralCommandLine loads the user's shell environment, unlike a bare ProcessBuilder (macOS apps started from the Dock). */
    fun commandLine(project: Project, args: List<String>): GeneralCommandLine =
        GeneralCommandLine(listOf(exePath(project)) + args)
}
