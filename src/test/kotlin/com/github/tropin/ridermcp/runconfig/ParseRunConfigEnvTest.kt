package com.github.tropin.ridermcp.runconfig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParseRunConfigEnvTest {

    @Test
    fun `simple pairs split on commas`() {
        assertEquals(mapOf("A" to "1", "B" to "2"), parseRunConfigEnv("A=1,B=2"))
    }

    @Test
    fun `blank input is empty`() {
        assertTrue(parseRunConfigEnv("").isEmpty())
        assertTrue(parseRunConfigEnv("   ").isEmpty())
    }

    @Test
    fun `quoted values may contain commas`() {
        assertEquals(
            mapOf("CONN" to "a,b", "X" to "1"),
            parseRunConfigEnv("CONN=\"a,b\",X=1")
        )
    }

    @Test
    fun `values may contain equals signs`() {
        assertEquals(mapOf("K" to "a=b"), parseRunConfigEnv("K=a=b"))
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals(mapOf("A" to "1"), parseRunConfigEnv("  A  =  1  "))
    }

    @Test
    fun `entry without equals fails`() {
        var failed = false
        try {
            parseRunConfigEnv("FOO")
        } catch (_: Throwable) {
            failed = true
        }
        assertTrue(failed)
    }
}
