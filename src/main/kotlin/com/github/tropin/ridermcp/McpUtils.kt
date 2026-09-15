package com.github.tropin.ridermcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import java.nio.file.Path

fun Project.projectDir(): Path? = guessProjectDir()?.toNioPathOrNull()

fun Path.relTo(projectDir: Path?): String =
    projectDir?.relativize(this)?.toString() ?: this.toString()
