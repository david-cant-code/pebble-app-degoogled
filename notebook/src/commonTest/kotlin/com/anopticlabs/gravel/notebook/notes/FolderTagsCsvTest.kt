package com.anopticlabs.gravel.notebook.notes

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderTagsCsvTest {

    @Test
    fun blankInputIsEmpty() {
        assertEquals(emptyList(), parseTagsCsv(""))
        assertEquals(emptyList(), parseTagsCsv("   "))
        assertEquals(emptyList(), parseTagsCsv(",,, ,, ,,"))
    }

    @Test
    fun tagsAreTrimmedLowercasedAndDedupedInFirstSeenOrder() {
        assertEquals(listOf("meeting"), parseTagsCsv("meeting"))
        assertEquals(listOf("meeting", "project"), parseTagsCsv("  meeting  ,   project   "))
        assertEquals(listOf("meeting"), parseTagsCsv("Meeting, MEETING, meeting"))
        assertEquals(listOf("zebra", "alpha", "beta"), parseTagsCsv("zebra, alpha, beta, alpha"))
        assertEquals(listOf("real", "also-real"), parseTagsCsv("real,  , also-real"))
    }

    @Test
    fun controlCharactersInsideATagAreRemoved() {
        assertEquals(listOf("work", "project"), parseTagsCsv("wo\u0000rk, project"))
        assertEquals(listOf("meeting", "project", "deadline"), parseTagsCsv("meet\ting, pro\nject, dead\rline"))
        assertEquals(listOf("test"), parseTagsCsv("te\u007fst"))
    }

    @Test
    fun perTagAndPerFolderCapsApply() {
        val long = "a".repeat(NoteSanitizers.MAX_TAG_CHARS + 20)
        assertEquals(listOf("a".repeat(NoteSanitizers.MAX_TAG_CHARS)), parseTagsCsv(long))

        val twenty = (1..20).joinToString(", ") { "tag$it" }
        val parsed = parseTagsCsv(twenty)
        assertEquals(NoteSanitizers.MAX_TAGS_PER_FOLDER, parsed.size)
        assertEquals("tag1", parsed.first())
        assertEquals("tag${NoteSanitizers.MAX_TAGS_PER_FOLDER}", parsed.last())

        val combined = "  WORK  ,project\t,$long,Project," + (1..8).joinToString(",") { "tag$it" }
        val out = parseTagsCsv(combined)
        assertEquals(listOf("work", "project", "a".repeat(NoteSanitizers.MAX_TAG_CHARS), "tag1", "tag2", "tag3", "tag4", "tag5"), out)
    }

    @Test
    fun seedingPutsTheFolderNameFirstAndDedupesIt() {
        assertEquals(listOf("groceries"), seedFolderTags("groceries", ""))
        assertEquals(listOf("personal"), seedFolderTags("personal", "   "))
        assertEquals(listOf("work", "budget", "q3"), seedFolderTags("work", "budget, q3"))
        assertEquals(listOf("work", "budget"), seedFolderTags("work", "work, budget"))
        assertEquals(listOf("work-stuff"), seedFolderTags("work-stuff", ""))
        val capped = seedFolderTags("alpha", "b, c, d, e, f, g, h, i, j")
        assertEquals(NoteSanitizers.MAX_TAGS_PER_FOLDER, capped.size)
        assertEquals("alpha", capped.first())
    }
}
