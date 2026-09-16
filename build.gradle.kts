plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    intellijPlatform {
        rider("2025.3") {
            useInstaller = false
        }
        bundledPlugin("com.intellij.mcpServer")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
        changeNotes = """
            v0.2.0: Canonical Gradle setup (9.5.0, IntelliJ Platform Gradle Plugin 2.16.0), verified compatible with Rider 2026.2. Replaced internal PluginManagerCore APIs with public PluginManager/PluginEnabler. Added V2 module dependency for smRunner.
        """.trimIndent()
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
