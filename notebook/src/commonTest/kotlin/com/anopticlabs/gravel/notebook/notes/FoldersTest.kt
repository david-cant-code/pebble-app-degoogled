package com.anopticlabs.gravel.notebook.notes

import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldersTest {

    @Test
    fun normalizeTrimsLowercasesAndHyphenatesSpaces() {
        assertEquals("my-work", FolderNames.normalize("  My Work "))
        assertEquals("a--b", FolderNames.normalize("A  B"))
    }

    @Test
    fun validNamesStartWithALetterOrDigitAndFitTheCap() {
        for (ok in listOf("a", "work", "q3-plans", "x_y", "0day", "a".repeat(FolderNames.MAX_CHARS))) {
            assertTrue(FolderNames.isValid(ok), ok)
        }
        for (bad in listOf("", "-lead", "_lead", "Cap", "has space", "sla/sh", "dot.", "a".repeat(FolderNames.MAX_CHARS + 1), "café")) {
            assertFalse(FolderNames.isValid(bad), bad)
        }
    }

    @Test
    fun theReservedAndBlocklistedSetsAreFixed() {
        assertEquals(setOf(UNCLASSIFIED_FOLDER, ARCHIVE_FOLDER), RESERVED_FOLDERS)
        assertEquals(setOf(ARCHIVE_FOLDER), INDEXED_FOLDER_BLOCKLIST)
        assertEquals(listOf(UNCLASSIFIED_FOLDER), DEFAULT_FOLDERS)
        assertTrue(RESERVED_FOLDERS.all { FolderNames.isValid(it) })
    }

    /** The archive can be in the folder list: a restore lists every valid name an archive carries. */
    @Test
    fun aListedArchiveIsNeverOfferedOrWrittenTo() {
        assertEquals(listOf("unclassified", "work"), captureRouteFolders(listOf("unclassified", "archive", "work")))
        assertEquals(UNCLASSIFIED_FOLDER, noteWriteFolder("archive", listOf("unclassified", "archive")))
        assertEquals("work", noteWriteFolder("work", listOf("unclassified", "work")))
    }

    @Test
    fun routingHasNoTagsOnlyWhileUserFoldersExistAndNoneIsTagged() {
        assertFalse(routingHasNoTags(listOf("unclassified"), emptyMap()))
        assertFalse(routingHasNoTags(listOf("unclassified", "archive"), emptyMap()))
        assertTrue(routingHasNoTags(listOf("unclassified", "work", "home"), emptyMap()))
        assertTrue(routingHasNoTags(listOf("work"), mapOf("work" to emptyList())))
        assertTrue(routingHasNoTags(listOf("work"), mapOf("archive" to listOf("x"), "unclassified" to listOf("y"))))
        assertFalse(routingHasNoTags(listOf("work", "home"), mapOf("home" to listOf("house"))))
    }
}
