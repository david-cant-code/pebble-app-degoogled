package com.anopticlabs.gravel.notebook.search

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BodyTokenizerTest {

    @Test
    fun queryStripsPunctuation() {
        val tokens = BodyTokenizer.tokenizeQuery("Hello, World!")
        assertContains(tokens, "hello")
        assertContains(tokens, "world")
    }

    @Test
    fun combiningMarksSeparateTokens() {
        // Lowercasing the dotted capital I yields "i" plus a combining dot, which is not a letter.
        for (t in BodyTokenizer.tokenizeQuery("İSTANBUL")) {
            assertFalse(t.contains("\u0307"), "combining dot in $t")
        }
    }

    @Test
    fun queryDropsSingleCharTokensAndStopwords() {
        assertEquals(emptySet(), BodyTokenizer.tokenizeQuery("a I to from x"))
    }

    @Test
    fun queryKeepsTwoCharAcronyms() {
        val tokens = BodyTokenizer.tokenizeQuery("AC Q3 IT")
        assertContains(tokens, "ac")
        assertContains(tokens, "q3")
        assertFalse("it" in tokens)
    }

    @Test
    fun queryStripsStopwords() {
        val tokens = BodyTokenizer.tokenizeQuery("the quick brown fox")
        assertEquals(setOf("quick", "brown", "fox"), tokens)
    }

    @Test
    fun stemmingCollapsesInflections() {
        assertEquals(BodyTokenizer.tokenizeQuery("running"), BodyTokenizer.tokenizeQuery("runs"))
        assertEquals(BodyTokenizer.tokenizeQuery("medicine"), BodyTokenizer.tokenizeQuery("medicines"))
        assertEquals(BodyTokenizer.tokenizeQuery("organization"), BodyTokenizer.tokenizeQuery("organize"))
        assertEquals(1, BodyTokenizer.tokenizeQuery("running").size)
    }

    @Test
    fun noteStripsFrontmatterBlock() {
        val tokens = BodyTokenizer.tokenizeNote("---\ntitle: my title\ntags: [foo, bar]\n---\n\nbody words here")
        assertFalse("title" in tokens)
        assertFalse("tag" in tokens)
        assertFalse("foo" in tokens)
        assertContains(tokens, "bodi")
        assertContains(tokens, "word")
    }

    @Test
    fun noteWithOnlyFrontmatterTokenizesToNothing() {
        assertEquals(emptySet(), BodyTokenizer.tokenizeNote("---\ntitle: x\ndate: 2026-04-28\n---"))
    }

    @Test
    fun noteWithUnterminatedFrontmatterIsAllBody() {
        val tokens = BodyTokenizer.tokenizeNote("---\ntitle: foo\ngotcha")
        assertContains(tokens, "titl")
        assertContains(tokens, "foo")
        assertContains(tokens, "gotcha")
    }

    @Test
    fun noteLenientStripClosesAtAnyLaterDashes() {
        // Stored tokens were computed with this lenient close; the strict parser would keep
        // everything here as body.
        assertEquals(setOf("bodi"), BodyTokenizer.tokenizeNote("---FOO---BODY"))
    }

    @Test
    fun noteWithoutFrontmatterPassesThrough() {
        assertEquals(setOf("plain", "bodi", "content"), BodyTokenizer.tokenizeNote("plain body content"))
    }

    @Test
    fun blankInputTokenizesToNothing() {
        assertEquals(emptySet(), BodyTokenizer.tokenizeQuery(""))
        assertEquals(emptySet(), BodyTokenizer.tokenizeQuery("   "))
    }

    @Test
    fun noteTokenCountIsCapped() {
        val text = (0 until 1500).joinToString(" ") { "word$it" }
        assertTrue(BodyTokenizer.tokenizeNote(text).size <= 500)
    }

    @Test
    fun queryTokenCountIsNotCapped() {
        val text = (0 until 1500).joinToString(" ") { "word$it" }
        assertTrue(BodyTokenizer.tokenizeQuery(text).size > 500)
    }

    @Test
    fun unicodeLettersArePreserved() {
        assertEquals(2, BodyTokenizer.tokenizeQuery("café résumé").size)
    }

    @Test
    fun alphanumericTokensKeepDigits() {
        val tokens = BodyTokenizer.tokenizeQuery("version 2 release v3 update q3")
        assertContains(tokens, "v3")
        assertContains(tokens, "q3")
    }

    @Test
    fun oversizedTokenInNoteIsDroppedWithoutCrashing() {
        val huge = "y".repeat(100_000)
        val tokens = BodyTokenizer.tokenizeNote("hello $huge world")
        assertContains(tokens, "hello")
        assertContains(tokens, "world")
        assertTrue(tokens.none { it.length > 64 })
    }

    @Test
    fun oversizedQueryTokenYieldsNothing() {
        assertEquals(emptySet(), BodyTokenizer.tokenizeQuery("y".repeat(100_000)))
    }

    @Test
    fun yBearingWordsStemAsPorterSpecifies() {
        assertEquals(setOf("happi"), BodyTokenizer.tokenizeQuery("happy"))
        assertEquals(setOf("fly"), BodyTokenizer.tokenizeQuery("fly"))
        assertEquals(setOf("sky"), BodyTokenizer.tokenizeQuery("sky"))
    }
}
