package com.anopticlabs.gravel.notebook.routing

import com.anopticlabs.gravel.notebook.notes.Frontmatter
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutedFolderTest {

    @Test
    fun routesByTagOverlap() {
        val folder = routedFolder(
            noteTags = listOf("budget"), noteTitle = "", noteContent = "",
            folders = listOf("work", "personal"),
            folderTags = mapOf("work" to listOf("budget"), "personal" to listOf("family")),
        )
        assertEquals("work", folder)
    }

    @Test
    fun noSignalFallsBackToUnclassified() {
        val folder = routedFolder(
            noteTags = listOf("vacation"), noteTitle = "Trip to Italy", noteContent = "flights and hotels",
            folders = listOf("work", "finance"),
            folderTags = mapOf("work" to listOf("budget"), "finance" to listOf("invoice")),
        )
        assertEquals("unclassified", folder)
    }

    @Test
    fun archiveIsNeverTheDefault() {
        val folder = routedFolder(
            noteTags = listOf("archived", "work"), noteTitle = "", noteContent = "",
            folders = listOf("archive", "work"),
            folderTags = mapOf("archive" to listOf("archived", "work"), "work" to listOf("work")),
        )
        assertEquals("work", folder)
    }

    @Test
    fun archiveAsSolePositiveYieldsUnclassified() {
        val folder = routedFolder(
            noteTags = listOf("archived"), noteTitle = "", noteContent = "",
            folders = listOf("archive"), folderTags = mapOf("archive" to listOf("archived")),
        )
        assertEquals("unclassified", folder)
    }

    @Test
    fun excludedSetIsExactlyArchive() {
        assertEquals(setOf("archive"), NOTE_WRITE_EXCLUDED_FOLDERS)
    }

    // Routing over a note's rendered markdown must strip the front matter first: its keys and
    // values are prose to the scorer and can flip the pick.

    private val renderedNote =
        "---\ntitle: Q3 numbers\ndate: 2026-06-14\ntags: []\ntype: journal\n---\n\nwe reviewed the budget"
    private val folders = listOf("diary", "work")
    private val folderTags = mapOf("diary" to listOf("journal"), "work" to listOf("budget"))

    @Test
    fun strippedContentRoutesLikeTheBodyAlone() {
        val stripped = routedFolder(emptyList(), "", Frontmatter.strip(renderedNote), folders, folderTags)
        val body = routedFolder(emptyList(), "", "we reviewed the budget", folders, folderTags)
        assertEquals("work", stripped)
        assertEquals(body, stripped)
    }

    @Test
    fun unstrippedFrontmatterLeaksIntoTheProseTier() {
        assertEquals("diary", routedFolder(emptyList(), "", renderedNote, folders, folderTags))
    }
}
