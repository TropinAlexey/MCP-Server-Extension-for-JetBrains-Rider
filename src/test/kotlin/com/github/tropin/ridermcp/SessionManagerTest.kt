package com.github.tropin.ridermcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionManagerTest {

    @Test
    fun `create session carries mutable metadata`() {
        val session = SessionManager.create("build")
        session.metadata["configuration"] = "Release"
        session.metadata["platform"] = "Any CPU"

        assertEquals("Release", session.metadata["configuration"])
        assertEquals("Any CPU", session.metadata["platform"])
        assertTrue(session.id.startsWith("build_"))
    }

    @Test
    fun `listByType returns sessions of given type`() {
        val build = SessionManager.create("build")
        val test = SessionManager.create("test")

        val builds = SessionManager.listByType("build")
        assertTrue(builds.any { it.id == build.id })
        assertTrue(builds.none { it.id == test.id })
    }
}
