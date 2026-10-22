package com.anopticlabs.gravel.notebook.notes

import kotlin.test.Test
import kotlin.test.assertEquals

class NoteSlugTest {

    @Test
    fun wordCapAndJoin() {
        assertEquals("one-two-three-four", NoteSlug.slugify("one two three four five six"))
        assertEquals("one-two", NoteSlug.slugify("one two three", maxWords = 2))
    }

    @Test
    fun compatibilityDecompositionFoldsToAscii() {
        // NFKD, not NFD: ligatures and compatibility spaces decompose too.
        assertEquals("cafe-resume", NoteSlug.slugify("Café résumé"))
        assertEquals("office", NoteSlug.slugify("oﬀice"))
        assertEquals("budget-2026", NoteSlug.slugify("Budget\u00A02026"))
        assertEquals("budget-2026", NoteSlug.slugify("Budget\u30002026"))
    }

    @Test
    fun nonAsciiWithoutADecompositionIsDropped() {
        assertEquals("123-mixed", NoteSlug.slugify("数字 123 mixed"))
        assertEquals("budget2026", NoteSlug.slugify("Budget\u20282026"))
        assertEquals("", NoteSlug.slugify("数字"))
    }

    @Test
    fun separatorRuleIsWhitespaceAfterAsciiFold() {
        val probes = listOf(
            ' ', '\t', '\n', '\r', '\u000B', '\u000C', '\u001C', '\u001F',
            '!', '.', '\u00F0', '\u4E2D',
        )
        for (c in probes) {
            val expected = if (c.isWhitespace()) "a-b" else "ab"
            assertEquals(expected, NoteSlug.slugify("a" + c + "b"), "U+%04X".format(c.code))
        }
        assertEquals("a_b", NoteSlug.slugify("a_b"))
        assertEquals("a-b", NoteSlug.slugify("a-b"))
    }

    @Test
    fun edgeWhitespaceProducesNoStrayDash() {
        assertEquals("budget-2026", NoteSlug.slugify("\u00A0 Budget 2026 \u3000"))
        assertEquals("", NoteSlug.slugify("   "))
    }
}
