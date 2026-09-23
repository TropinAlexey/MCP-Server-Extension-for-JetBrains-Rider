package com.github.tropin.ridermcp.analysis

import com.jetbrains.rider.model.SolutionAnalysisErrorWithIgnore
import kotlin.test.Test
import kotlin.test.assertEquals

class SweaToolsTest {

    private val toolset = SweaToolset()

    @Test
    fun `parseError extracts C# error code and trims it from text`() {
        val err = SolutionAnalysisErrorWithIgnore("CS0246 The type or namespace name 'Foo' could not be found", 0, false, false, false)
        val parsed = toolset.parseError(err)
        assertEquals("CS0246", parsed.code)
        assertEquals("The type or namespace name 'Foo' could not be found", parsed.text)
        assertEquals(1, parsed.line)
    }

    @Test
    fun `parseError extracts VB error code`() {
        val err = SolutionAnalysisErrorWithIgnore("BC30002 Type 'Foo' is not defined", 0, false, false, false)
        val parsed = toolset.parseError(err)
        assertEquals("BC30002", parsed.code)
        assertEquals("Type 'Foo' is not defined", parsed.text)
    }

    @Test
    fun `parseError uses unknown code when no diagnostic prefix`() {
        val err = SolutionAnalysisErrorWithIgnore("Some generic message", 0, false, false, false)
        val parsed = toolset.parseError(err)
        assertEquals("<unknown>", parsed.code)
        assertEquals("Some generic message", parsed.text)
    }

    @Test
    fun `parseError keeps warning flag separate`() {
        val err = SolutionAnalysisErrorWithIgnore("CS0168 The variable 'x' is declared but never used", 0, true, false, false)
        val parsed = toolset.parseError(err)
        assertEquals("CS0168", parsed.code)
        assertEquals("The variable 'x' is declared but never used", parsed.text)
    }
}
