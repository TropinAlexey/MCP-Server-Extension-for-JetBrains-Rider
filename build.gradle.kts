plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    intellijPlatform {
        rider("2026.1") {
            useInstaller = false
        }
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("com.intellij.database")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
        changeNotes = file("CHANGELOG.html").readText()
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
        ides {
            local("/Applications/Rider.app/Contents")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Production guard: README tool table and @McpDescription blocks must stay in
// sync with the real @McpTool set. Fails the build on drift.
tasks.register("checkToolDocs") {
    group = "verification"
    description = "Asserts every rider_* tool is documented in README.md and description blocks stay within budget."
    val srcDir = projectDir.resolve("src/main/kotlin")
    val readmeFile = projectDir.resolve("README.md")
    doLast {
        val sources = srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val blockRe = Regex("suspend fun (rider_\\w+)")
        val strRe = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
        val tools = mutableMapOf<String, Int>()
        sources.forEach { f ->
            f.readText().split("@McpTool").drop(1).forEach { block ->
                val name = blockRe.find(block)?.groupValues?.get(1) ?: return@forEach
                val head = block.substringBefore("suspend fun")
                tools[name] = strRe.findAll(head).sumOf { it.groupValues[1].length }
            }
        }
        if (tools.isEmpty()) throw GradleException("checkToolDocs: no rider_* tools found")
        val readme = readmeFile.readText()
        val missing = tools.keys.filter { !readme.contains("`$it`") }
        if (missing.isNotEmpty()) throw GradleException("Tools missing from README.md: $missing")
        val over = tools.filter { it.value > 1100 }
        if (over.isNotEmpty()) throw GradleException("Description budget exceeded (1100 chars): $over")
        println("checkToolDocs: ${tools.size} tools documented, max block ${tools.values.max()} chars — OK")
    }
}

tasks.findByName("check")?.dependsOn("checkToolDocs")
