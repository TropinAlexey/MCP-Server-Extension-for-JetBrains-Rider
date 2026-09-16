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
            v0.2.1: Remove rider_manage_plugin (all plugin management APIs are internal). Zero internal/experimental API usages.
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
