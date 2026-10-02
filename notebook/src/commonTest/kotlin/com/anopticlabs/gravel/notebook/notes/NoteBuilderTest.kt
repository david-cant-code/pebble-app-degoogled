package com.anopticlabs.gravel.notebook.notes

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteBuilderTest {

    private val now = LocalDateTime(2026, 5, 31, 14, 7)
    private val date = "2026-05-31"

    private fun build(
        raw: String,
        transcript: String = "t",
        folders: List<String> = emptyList(),
        folderTags: Map<String, List<String>> = emptyMap(),
    ) = NoteBuilder.buildNote(raw, transcript, folders, folderTags, now)

    @Test
    fun wellFormedReplyRoutesByTagsAndRendersTheBody() {
        val raw = """{"category":"idea","folder":"ignored","title":"My Idea","tags":["work","ideas"],"content":"body text"}"""
        val r = build(raw, transcript = "spoken words", folders = listOf("projects", "todo"), folderTags = mapOf("projects" to listOf("ideas")))
        assertEquals("projects", r.folder)
        assertEquals(listOf("work", "ideas"), r.tags)
        assertFalse(r.classificationFailed)
        assertEquals("spoken words", r.transcript)
        assertEquals(NoteMarkdown.buildMarkdown("My Idea", listOf("work", "ideas"), "spoken words", date), r.markdown)
        assertEquals("my-idea_05.31.26_1407.md", r.filename)
    }

    @Test
    fun theModelsFolderIsNeverUsed() {
        val r = build("""{"folder":"work","title":"T","content":"c"}""", folders = listOf("work", "todo"))
        assertEquals("unclassified", r.folder)
    }

    @Test
    fun aStrayCategoryIsIgnored() {
        val raw = """{"category":"task","folder":"whatever","title":"Do thing","tags":["work"],"content":"stuff"}"""
        val r = build(raw, folders = listOf("todo", "work"), folderTags = mapOf("work" to listOf("work")))
        assertEquals("work", r.folder)
        assertEquals(NoteMarkdown.buildMarkdown("Do thing", listOf("work"), "t", date), r.markdown)
    }

    @Test
    fun malformedReplyFallsBackToTheTranscript() {
        val r = build("not json at all", transcript = "the spoken transcript", folders = listOf("work"))
        assertTrue(r.classificationFailed)
        assertEquals(listOf("voice"), r.tags)
        assertEquals(NoteMarkdown.buildMarkdown("Voice Note", listOf("voice"), "the spoken transcript", date), r.markdown)
    }

    @Test
    fun truncatedReplyKeepsTheTranscript() {
        val transcript = "um, call the plumber about the, uh, leak under the kitchen sink before Friday"
        val r = build("""{"title": "Call the plumber", "tags": ["home",""", transcript = transcript)
        assertTrue(r.classificationFailed)
        assertEquals(NoteMarkdown.buildMarkdown("Voice Note", listOf("voice"), transcript, date), r.markdown)
        assertFalse(r.markdown.contains("Call the plumber"))
    }

    @Test
    fun aVolunteeredContentFieldIsIgnored() {
        val transcript = "so, like, the tomatoes are, uh, looking rough"
        val raw = """{"title":"Tomatoes looking rough","tags":["garden"],"content":"The tomatoes are looking rough."}"""
        val r = build(raw, transcript = transcript)
        assertFalse(r.classificationFailed)
        assertEquals("Tomatoes looking rough", r.title)
        assertEquals(listOf("garden"), r.tags)
        assertEquals(NoteMarkdown.buildMarkdown("Tomatoes looking rough", listOf("garden"), transcript, date), r.markdown)
    }

    @Test
    fun routingReadsTheTranscriptNotAVolunteeredContentField() {
        val shopping = mapOf("shopping" to listOf("groceries"))
        val steered = build("""{"title":"T","tags":["misc"],"content":"groceries list"}""", transcript = "call the plumber", folders = listOf("shopping"), folderTags = shopping)
        assertEquals("unclassified", steered.folder)
        val spoken = build("""{"title":"T","tags":["misc"]}""", transcript = "buy groceries", folders = listOf("shopping"), folderTags = shopping)
        assertEquals("shopping", spoken.folder)
    }

    @Test
    fun aReplyWithTitleAndTagsIsAClassifiedNote() {
        val r = build("""{"title":"T","tags":["a"]}""", transcript = "spoken")
        assertFalse(r.classificationFailed)
        assertEquals(NoteMarkdown.buildMarkdown("T", listOf("a"), "spoken", date), r.markdown)
    }

    @Test
    fun nonObjectJsonIsAFailedClassification() {
        val r = build("[1, 2, 3]", transcript = "the spoken words", folders = listOf("work"))
        assertTrue(r.classificationFailed)
        assertEquals("unclassified", r.folder)
        assertEquals(NoteMarkdown.buildMarkdown("Voice Note", emptyList(), "the spoken words", date), r.markdown)
    }

    @Test
    fun nonStringTitleFallsBackToTheDefault() {
        val r = build("""{"title": 42, "tags": ["a"], "content": "c"}""")
        assertEquals("Voice Note", r.title)
        assertFalse(r.classificationFailed)
    }

    @Test
    fun pathTraversalInTheTitleCannotReachTheFilename() {
        val r = build("""{"folder":"../../etc","title":"../../../etc/passwd","content":"x"}""", folders = listOf("work"))
        assertEquals("unclassified", r.folder)
        assertEquals("etcpasswd_05.31.26_1407.md", r.filename)
    }

    @Test
    fun suffixedFilenameKeepsAStampInPlaceAndOtherwiseSuffixesTheStem() {
        assertEquals("my-idea-2_05.31.26_1407.md", NoteBuilder.suffixedFilename("my-idea_05.31.26_1407.md", 2))
        assertEquals("a_b-3_05.31.26_1407.md", NoteBuilder.suffixedFilename("a_b_05.31.26_1407.md", 3))
        assertEquals("-2_05.31.26_1407.md", NoteBuilder.suffixedFilename("_05.31.26_1407.md", 2))
        assertEquals("plain-2.md", NoteBuilder.suffixedFilename("plain.md", 2))
        assertEquals("noext-2", NoteBuilder.suffixedFilename("noext", 2))
    }

    @Test
    fun filenameStampPadsMonthDayHourAndMinute() {
        assertEquals("x_01.02.30_0304.md", NoteBuilder.noteFilename("x", LocalDateTime(2030, 1, 2, 3, 4)))
    }

    /** A model's title of long joined words still names a note the store takes, suffixes included. */
    @Test
    fun aLongTitleMakesANameTheStoreTakesWithEverySuffix() {
        val title = List(4) { "w".repeat(40) }.joinToString(" ")
        val name = NoteBuilder.noteFilename(title, now)
        assertTrue(NotePaths.isValidName(name), "$name (${name.length} chars)")
        assertTrue(NotePaths.isValidName(NoteBuilder.suffixedFilename(name, 999)))
        assertEquals("www_05.31.26_1407.md", NoteBuilder.noteFilename("www", now), "a short slug is kept whole")
    }

    @Test
    fun renamedFilenameTakesTheNewSlugAndKeepsTheStamp() {
        assertEquals("new-plan_09.27.26_1430.md", NoteBuilder.renamedFilename("old-title-2_09.27.26_1430.md", "New plan"))
        assertEquals("_09.27.26_1430.md", NoteBuilder.renamedFilename("old_09.27.26_1430.md", "\u2605\u2605"))
    }

    @Test
    fun renamedFilenameWithoutAStampUsesTheSlugOrKeepsTheName() {
        assertEquals("new-plan.md", NoteBuilder.renamedFilename("restored-name.md", "New plan"))
        assertNull(NoteBuilder.renamedFilename("restored-name.md", "\u2605"))
    }

    @Test
    fun renamedFilenameStaysWithinTheNameRuleForALongTitle() {
        val renamed = NoteBuilder.renamedFilename("a_09.27.26_1430.md", "word ".repeat(200))
        assertTrue(renamed != null && NotePaths.isValidName(renamed) && NotePaths.isValidName(NoteBuilder.suffixedFilename(renamed, 999)))
    }
}
