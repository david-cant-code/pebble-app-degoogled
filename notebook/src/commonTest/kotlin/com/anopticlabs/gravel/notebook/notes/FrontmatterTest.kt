package com.anopticlabs.gravel.notebook.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FrontmatterTest {

    @Test
    fun standardBlockExtractsAndStrips() {
        val c = "---\ntitle: Hello\n---\nbody text"
        assertEquals("---\ntitle: Hello\n---", Frontmatter.extract(c))
        assertEquals("body text", Frontmatter.strip(c))
    }

    @Test
    fun dashesInsideAQuotedValueDoNotClose() {
        val c = "---\ntitle: \"a --- b\"\n---\nbody"
        assertEquals("---\ntitle: \"a --- b\"\n---", Frontmatter.extract(c))
        assertEquals("body", Frontmatter.strip(c))
    }

    @Test
    fun editSaveRoundTripKeepsAnInlineDashTitle() {
        val raw = "---\ntitle: \"a --- b\"\n---\nbody"
        val saved = Frontmatter.recombine(raw, Frontmatter.strip(raw))
        assertEquals("---\ntitle: \"a --- b\"\n---\n\nbody", saved)
        assertEquals("---\ntitle: \"a --- b\"\n---", Frontmatter.extract(saved))
        assertEquals("body", Frontmatter.strip(saved))
    }

    @Test
    fun noBlockReturnsEmptyAndUnchanged() {
        val c = "just body\nno frontmatter"
        assertEquals("", Frontmatter.extract(c))
        assertEquals(c, Frontmatter.strip(c))
    }

    @Test
    fun unterminatedBlockIsNoBlock() {
        val c = "---\ntitle: Hello\nbody with no close"
        assertEquals("", Frontmatter.extract(c))
        assertEquals(c, Frontmatter.strip(c))
    }

    @Test
    fun crlfIsHandledLikeLf() {
        val c = "---\r\ntitle: Hello\r\n---\r\nbody"
        assertEquals("---\r\ntitle: Hello\r\n---", Frontmatter.extract(c))
        assertEquals("body", Frontmatter.strip(c))
    }

    @Test
    fun emptyBlockClosesAtOnce() {
        val c = "---\n---\nbody"
        assertEquals("---\n---", Frontmatter.extract(c))
        assertEquals("body", Frontmatter.strip(c))
    }

    @Test
    fun closeAtEndOfContent() {
        val c = "---\ntitle: Hello\n---"
        assertEquals("---\ntitle: Hello\n---", Frontmatter.extract(c))
        assertEquals("", Frontmatter.strip(c))
    }

    @Test
    fun longerDashRunIsNotAClose() {
        val c = "---\ntitle: Hello\n----\nstill fm? no\n---\nreal body"
        assertEquals("---\ntitle: Hello\n----\nstill fm? no\n---", Frontmatter.extract(c))
        assertEquals("real body", Frontmatter.strip(c))
    }

    @Test
    fun openingMustBeAnExactDashLine() {
        val c = "---x\ntitle: Hello\n---\nbody"
        assertEquals("", Frontmatter.extract(c))
        assertEquals(c, Frontmatter.strip(c))
    }

    @Test
    fun recombineRoundTripsARenderedNote() {
        val note = NoteMarkdown.buildMarkdown("My Photo", listOf("t"), "line one\nline two\n\npara two", "2026-07-09")
        assertEquals(note, Frontmatter.recombine(note, Frontmatter.strip(note)))
    }

    @Test
    fun recombineDoesNotRestoreLeadingWhitespace() {
        val note = NoteMarkdown.buildMarkdown("T", emptyList(), "\n\n  indented start", "2026-07-09")
        assertNotEquals(note, Frontmatter.recombine(note, Frontmatter.strip(note)))
    }

    @Test
    fun recombineSwapsTheBodyAndKeepsTheBlock() {
        val note = NoteMarkdown.buildMarkdown("T", emptyList(), "old body", "2026-07-09")
        val edited = Frontmatter.recombine(note, "new\nmultiline\nbody")
        assertEquals(Frontmatter.extract(note) + "\n\nnew\nmultiline\nbody", edited)
        assertEquals("new\nmultiline\nbody", Frontmatter.strip(edited))
        assertEquals(Frontmatter.extract(note), Frontmatter.extract(edited))
    }

    @Test
    fun recombineWithoutABlockReturnsTheBody() {
        val plain = "no frontmatter here\njust text"
        assertEquals("brand new", Frontmatter.recombine(plain, "brand new"))
        assertEquals(plain, Frontmatter.recombine(plain, Frontmatter.strip(plain)))
    }

    @Test
    fun withTitleReplacesTheTitleEntryAndKeepsTheRest() {
        val note = NoteMarkdown.buildMarkdown("Old", listOf("a"), "body", "2026-07-09")
        val retitled = Frontmatter.withTitle(note, "New \"one\"")
        assertEquals(note.replace("title: \"Old\"", "title: \"New \\\"one\\\"\""), retitled)
        assertEquals(Frontmatter.strip(note), Frontmatter.strip(retitled))
    }

    @Test
    fun withTitleDropsTheIndentedLinesOfAMultiLineTitle() {
        val note = "---\ntitle: >\n  first\n  second\ndate: 2026-07-09\n---\n\nbody"
        assertEquals("---\ntitle: \"T\"\ndate: 2026-07-09\n---\n\nbody", Frontmatter.withTitle(note, "T"))
    }

    @Test
    fun withTitleAddsATitleToABlockWithoutOne() {
        assertEquals("---\ntitle: \"T\"\ndate: 2026-07-09\n---\nbody", Frontmatter.withTitle("---\ndate: 2026-07-09\n---\nbody", "T"))
        assertEquals("---\ntitle: \"T\"\n---\nbody", Frontmatter.withTitle("---\n---\nbody", "T"))
    }

    @Test
    fun withTitleKeepsCrlfLineEndings() {
        val note = "---\r\ntitle: Old\r\ndate: x\r\n---\r\nbody"
        assertEquals("---\r\ntitle: \"T\"\r\ndate: x\r\n---\r\nbody", Frontmatter.withTitle(note, "T"))
    }

    @Test
    fun withTitleIgnoresAnIndentedOrBodyTitleAndLeavesANoteWithoutABlockAlone() {
        val note = "---\nmeta:\n  title: inner\n---\ntitle: in the body"
        assertEquals("---\ntitle: \"T\"\nmeta:\n  title: inner\n---\ntitle: in the body", Frontmatter.withTitle(note, "T"))
        assertEquals("plain\ntitle: x", Frontmatter.withTitle("plain\ntitle: x", "T"))
    }

    @Test
    fun editableBodyGivesBackANoteTheNotebookWroteUnchanged() {
        for (body in listOf("    indented code\nline", "\n\nafter blank lines", "plain")) {
            val note = NoteMarkdown.buildMarkdown("T", listOf("a"), body, "2026-09-27")
            assertEquals("$body\n", Frontmatter.editableBody(note))
            assertEquals(note, Frontmatter.recombine(note, Frontmatter.editableBody(note)))
        }
    }

    @Test
    fun editableBodyTakesOffOneSeparatorAndLeavesANoteWithoutABlockAlone() {
        assertEquals("  body", Frontmatter.editableBody("---\ntitle: x\n---\n  body"))
        assertEquals("body", Frontmatter.editableBody("---\r\ntitle: x\r\n---\r\n\r\nbody"))
        assertEquals("", Frontmatter.editableBody("---\ntitle: x\n---"))
        assertEquals("  no block", Frontmatter.editableBody("  no block"))
    }

    @Test
    fun withTitleDropsABlockScalarsLinesAcrossABlankLineButNotTheNextEntry() {
        val note = "---\ntitle: |\n  a\n\n  b\n\ndate: x\n---\nbody"
        assertEquals("---\ntitle: \"T\"\n\ndate: x\n---\nbody", Frontmatter.withTitle(note, "T"))
    }
}
