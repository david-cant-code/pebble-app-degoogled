package com.anopticlabs.gravel.notebook.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreFoldersForNoteTest {

    private fun routedDefault(ranked: List<Pair<String, Int>>): String =
        pickRoutedFolder(ranked, excluded = emptySet())

    private fun names(ranked: List<Pair<String, Int>>): List<String> = ranked.map { it.first }

    @Test
    fun exactTagMatchRoutesToFolder() {
        val out = scoreFoldersForNote(
            noteTags = listOf("budget"), noteTitle = "", noteContent = "",
            folders = listOf("work", "personal"),
            folderTags = mapOf("work" to listOf("budget", "meeting"), "personal" to listOf("family")),
        )
        assertEquals(listOf("work" to FOLDER_ROUTE_TAG_WEIGHT, "personal" to 0, "unclassified" to 0), out)
        assertEquals("work", routedDefault(out))
    }

    @Test
    fun tagMatchOutranksProseMatch() {
        val out = scoreFoldersForNote(
            noteTags = listOf("budget"), noteTitle = "Weekly meeting notes", noteContent = "",
            folders = listOf("finance", "calendar"),
            folderTags = mapOf("finance" to listOf("budget"), "calendar" to listOf("meeting")),
        )
        assertEquals(listOf("finance" to FOLDER_ROUTE_TAG_WEIGHT, "calendar" to 1, "unclassified" to 0), out)
        assertEquals("finance", routedDefault(out))
    }

    @Test
    fun oneTagMatchBeatsTwoProseMatches() {
        val out = scoreFoldersForNote(
            noteTags = listOf("budget"), noteTitle = "meeting project", noteContent = "",
            folders = listOf("finance", "planning"),
            folderTags = mapOf("finance" to listOf("budget"), "planning" to listOf("meeting", "project")),
        )
        assertEquals(listOf("finance" to FOLDER_ROUTE_TAG_WEIGHT, "planning" to 2, "unclassified" to 0), out)
    }

    @Test
    fun stemsMatchAcrossWordForms() {
        val out = scoreFoldersForNote(
            noteTags = emptyList(), noteTitle = "shopping list for the weekend", noteContent = "",
            folders = listOf("errands"), folderTags = mapOf("errands" to listOf("shop")),
        )
        assertEquals(listOf("errands" to 1, "unclassified" to 0), out)
    }

    @Test
    fun contentProseMatches() {
        val out = scoreFoldersForNote(
            noteTags = emptyList(), noteTitle = "Untitled",
            noteContent = "we spent an hour discussing the budget figures",
            folders = listOf("finance"), folderTags = mapOf("finance" to listOf("budget")),
        )
        assertEquals(listOf("finance" to 1, "unclassified" to 0), out)
    }

    @Test
    fun tagMatchesAddUpWithinAFolder() {
        val out = scoreFoldersForNote(
            noteTags = listOf("budget", "meeting"), noteTitle = "", noteContent = "",
            folders = listOf("rich", "poor"),
            folderTags = mapOf("rich" to listOf("budget", "meeting"), "poor" to listOf("budget")),
        )
        assertEquals(listOf("rich" to 2 * FOLDER_ROUTE_TAG_WEIGHT, "poor" to FOLDER_ROUTE_TAG_WEIGHT, "unclassified" to 0), out)
    }

    @Test
    fun noSignalRoutesToUnclassified() {
        val out = scoreFoldersForNote(
            noteTags = listOf("vacation"), noteTitle = "Trip to Italy", noteContent = "flights and hotels",
            folders = listOf("work", "finance"),
            folderTags = mapOf("work" to listOf("budget"), "finance" to listOf("invoice")),
        )
        assertTrue(out.all { it.second == 0 })
        assertEquals("unclassified", routedDefault(out))
    }

    @Test
    fun tiesKeepInputOrder() {
        val out = scoreFoldersForNote(
            noteTags = listOf("budget"), noteTitle = "", noteContent = "",
            folders = listOf("first", "second", "third"),
            folderTags = mapOf("first" to listOf("budget"), "second" to listOf("budget"), "third" to listOf("budget")),
        )
        assertEquals(listOf("first", "second", "third", "unclassified"), names(out))
        assertEquals("first", routedDefault(out))
    }

    @Test
    fun unclassifiedIsAppendedWhenAbsent() {
        val out = scoreFoldersForNote(emptyList(), "", "", folders = listOf("work"), folderTags = emptyMap())
        assertEquals(listOf("work" to 0, "unclassified" to 0), out)
    }

    @Test
    fun unclassifiedIsNotDuplicatedWhenPresent() {
        val out = scoreFoldersForNote(
            noteTags = listOf("budget"), noteTitle = "", noteContent = "",
            folders = listOf("work", "unclassified"), folderTags = mapOf("work" to listOf("budget")),
        )
        assertEquals(listOf("work" to FOLDER_ROUTE_TAG_WEIGHT, "unclassified" to 0), out)
    }

    @Test
    fun emptyFolderListYieldsOnlyUnclassified() {
        val out = scoreFoldersForNote(listOf("budget"), "x", "y", folders = emptyList(), folderTags = emptyMap())
        assertEquals(listOf("unclassified" to 0), out)
        assertEquals("unclassified", routedDefault(out))
    }

    @Test
    fun folderWithoutTagsScoresZero() {
        val out = scoreFoldersForNote(
            noteTags = listOf("budget"), noteTitle = "budget review", noteContent = "",
            folders = listOf("whisper"), folderTags = emptyMap(),
        )
        assertEquals(listOf("whisper" to 0, "unclassified" to 0), out)
    }

    @Test
    fun folderTagMatchingBothAxesCountsOnceAtTagTier() {
        val out = scoreFoldersForNote(
            noteTags = listOf("budget"), noteTitle = "budget budget budget", noteContent = "budget",
            folders = listOf("finance"), folderTags = mapOf("finance" to listOf("budget")),
        )
        assertEquals(listOf("finance" to FOLDER_ROUTE_TAG_WEIGHT, "unclassified" to 0), out)
    }

    @Test
    fun excludedTopFolderFallsThroughToNextPositiveFolder() {
        val ranked = listOf("archive" to 6, "work" to 3, "unclassified" to 0)
        assertEquals("work", pickRoutedFolder(ranked, excluded = setOf("archive")))
    }

    @Test
    fun excludedSolePositiveFolderYieldsUnclassified() {
        val ranked = listOf("archive" to 6, "work" to 0, "unclassified" to 0)
        assertEquals("unclassified", pickRoutedFolder(ranked, excluded = setOf("archive")))
    }

    @Test
    fun exclusionIsTheCallersSet() {
        val ranked = listOf("todo" to 6, "work" to 3, "unclassified" to 0)
        assertEquals("todo", pickRoutedFolder(ranked, excluded = setOf("archive")))
        assertEquals("work", pickRoutedFolder(ranked, excluded = setOf("todo")))
    }
}
