package com.github.tropin.ridermcp.publish

import com.github.tropin.ridermcp.OutputSession
import com.github.tropin.ridermcp.SessionManager
import com.jetbrains.rider.run.configurations.publishing.PubXmlRunConfigurationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishToolsTest {

    private val toolset = PublishToolset()

    @Test
    fun `capturePublishMetadata stores pubxml settings`() {
        val session = OutputSession(id = "test", type = "publish")
        val settings = PubXmlRunConfigurationType.ConfigurationSettings().apply {
            publishProfile = "FolderProfile"
            pubxmlPath = "/tmp/FolderProfile.pubxml"
            configuration = "Release"
            platform = "Any CPU"
        }

        toolset.capturePublishMetadata(session, null, "MyProject: FolderProfile", settings)

        assertEquals("FolderProfile", session.metadata["publishProfile"])
        assertEquals("/tmp/FolderProfile.pubxml", session.metadata["pubxmlPath"])
        assertEquals("Release", session.metadata["configuration"])
        assertEquals("Any CPU", session.metadata["platform"])
        assertEquals("MyProject: FolderProfile", session.metadata["configName"])
    }

    @Test
    fun `SessionManager create returns publish session with metadata`() {
        val session = SessionManager.create("publish")
        session.metadata["publishProfile"] = "FolderProfile"
        assertTrue(session.id.startsWith("publish_"))
        assertEquals("FolderProfile", SessionManager.get(session.id)?.metadata?.get("publishProfile"))
    }
}
