package com.anopticlabs.gravel.notebook.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptBudgetTest {

    @Test
    fun shorterThanCapIsReturnedVerbatim() {
        assertEquals("hello\nworld", capToBudget("hello\nworld", 1024))
        assertEquals("a".repeat(100), capToBudget("a".repeat(100), 100))
        assertEquals("", capToBudget("", 16))
    }

    @Test
    fun overCapNeverExceedsCap() {
        assertTrue(capToBudget("x".repeat(10_000), 4096).length <= 4096)
    }

    @Test
    fun overCapPrefersANewlineInTheBackHalf() {
        val out = capToBudget("a".repeat(80) + "\n" + "b".repeat(10_000), 100)
        assertEquals("a".repeat(80), out)
        assertFalse(out.contains("b"))
    }

    @Test
    fun overCapHardCutsWithoutANewlineInTheBackHalf() {
        assertEquals(100, capToBudget("aaaaa\n" + "b".repeat(10_000), 100).length)
    }

    @Test
    fun contentBudgetStaysWithinHalfTheContext() {
        assertTrue(PROMPT_CONTENT_BUDGET <= 16 * 1024)
    }
}
