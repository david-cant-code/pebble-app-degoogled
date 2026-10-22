package com.anopticlabs.gravel.notebook.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrontmatterTagsTest {

    private fun note(frontmatterLines: String) = "---\n$frontmatterLines\n---\n\nbody"

    @Test
    fun parsesDoubleQuotedInlineArray() {
        val content = note("title: \"Test\"\ndate: 2026-04-01\ntags: [\"shopping\", \"errands\"]\ntype: note")
        assertEquals(listOf("shopping", "errands"), FrontmatterTags.parseFrontmatterTags(content))
    }

    @Test
    fun parsesUnquotedInlineArray() {
        assertEquals(listOf("shopping", "errands"), FrontmatterTags.parseFrontmatterTags(note("tags: [shopping, errands]")))
    }

    @Test
    fun parsesSingleQuotedInlineArray() {
        assertEquals(listOf("health", "doctor"), FrontmatterTags.parseFrontmatterTags(note("tags: ['health', 'doctor']")))
    }

    @Test
    fun parsesBlockListForm() {
        val content = note("title: \"Test\"\ntags:\n  - shopping\n  - \"errands\"\ntype: note")
        assertEquals(listOf("shopping", "errands"), FrontmatterTags.parseFrontmatterTags(content))
    }

    @Test
    fun toleratesExtraWhitespace() {
        assertEquals(listOf("a", "b", "c"), FrontmatterTags.parseFrontmatterTags(note("tags: [  \"a\"  ,  \"b\"  ,  \"c\"  ]")))
    }

    @Test
    fun emptyArrayIsAnEmptyList() {
        assertEquals(emptyList(), FrontmatterTags.parseFrontmatterTags(note("tags: []")))
    }

    @Test
    fun missingTagsLineIsNull() {
        assertNull(FrontmatterTags.parseFrontmatterTags(note("title: \"Test\"\ndate: 2026-04-01\ntype: note")))
    }

    @Test
    fun noFrontmatterIsNull() {
        assertNull(FrontmatterTags.parseFrontmatterTags("just a body, no frontmatter"))
    }

    @Test
    fun unterminatedFrontmatterIsNull() {
        assertNull(FrontmatterTags.parseFrontmatterTags("---\ntitle: \"Test\"\ntags: [\"a\", \"b\"]"))
    }

    @Test
    fun unclosedArrayIsAnEmptyList() {
        assertEquals(emptyList(), FrontmatterTags.parseFrontmatterTags(note("tags: [\"a\", \"b\"\ntype: note")))
    }

    @Test
    fun parsesARenderedNote() {
        val content = NoteMarkdown.buildMarkdown("Buy groceries this week", listOf("shopping", "errands"), "- [ ] Milk\n- [ ] Eggs", "2026-04-01")
        assertEquals(listOf("shopping", "errands"), FrontmatterTags.parseFrontmatterTags(content))
    }

    @Test
    fun emptyFrontmatterIsNull() {
        assertNull(FrontmatterTags.parseFrontmatterTags("---\n---\nbody"))
    }

    @Test
    fun shortUnterminatedInputsAreNull() {
        assertNull(FrontmatterTags.parseFrontmatterTags("---"))
        assertNull(FrontmatterTags.parseFrontmatterTags("---\n"))
        assertNull(FrontmatterTags.parseFrontmatterTags("---\nbody"))
    }

    @Test
    fun commaInsideQuotesDoesNotSplit() {
        assertEquals(listOf("foo, bar", "baz"), FrontmatterTags.parseFrontmatterTags(note("tags: [\"foo, bar\", \"baz\"]")))
    }

    @Test
    fun bracketInsideQuotesDoesNotClose() {
        assertEquals(listOf("foo]bar", "baz"), FrontmatterTags.parseFrontmatterTags(note("tags: [\"foo]bar\", \"baz\"]")))
    }

    @Test
    fun mixedSpecialCharactersParse() {
        assertEquals(listOf("a, b", "c]d", "plain"), FrontmatterTags.parseFrontmatterTags(note("tags: [\"a, b\", \"c]d\", plain]")))
    }

    @Test
    fun escapedQuoteStaysInsideTheValue() {
        // The value is returned still escaped; only the surrounding quotes are removed.
        val tags = FrontmatterTags.parseFrontmatterTags("---\ntags: [\"he said \\\"hi\\\"\", \"next\"]\n---\n\nbody")
        assertEquals(listOf("he said \\\"hi\\\"", "next"), tags)
    }

    @Test
    fun bareScalarIsAOneElementList() {
        assertEquals(listOf("solo"), FrontmatterTags.parseFrontmatterTags(note("tags: solo")))
    }

    @Test
    fun aTypeLineIsIgnored() {
        val content = "---\ntitle: \"Do the thing\"\ndate: 2026-04-01\ntags: [\"task\"]\ntype: note\n---\n\nbody\n"
        assertEquals(listOf("task"), FrontmatterTags.parseFrontmatterTags(content))
    }
}
