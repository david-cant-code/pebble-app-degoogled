package com.anopticlabs.gravel.notebook.sources

import com.anopticlabs.gravel.notebook.backup.BackupNote
import com.anopticlabs.gravel.notebook.backup.RestorePlan
import com.anopticlabs.gravel.notebook.notes.NoteMarkdown
import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TypedNotesTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val notes = NoteStore(root, FakeCryptographer(softwareCrypto()))

    // 2026-09-27 21:30 in Los Angeles, already the 28th in UTC.
    private val typed = TypedNotes(notes, clock = { 1_790_569_800_000L }, zone = { TimeZone.of("America/Los_Angeles") })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aNoteIsWrittenAsTypedWithNoTagsInTheChosenFolder() = runBlocking<Unit> {
        notes.ensureDefaultFolders()
        notes.createFolder("work")

        val saved = assertIs<TypedNoteResult.Saved>(typed.save("Groceries", "milk\neggs", "work"))

        assertEquals("work", saved.folder)
        assertEquals(NoteMarkdown.buildMarkdown("Groceries", emptyList(), "milk\neggs", "2026-09-27"), notes.readNote(saved.noteId, strict = true))
        val meta = notes.readMetadata(strict = true).getValue(saved.noteId)
        assertEquals("Groceries", meta.title)
        assertEquals("groceries_09.27.26_2130.md", notes.listNoteItems("work").single().name)
    }

    @Test
    fun aFolderNotOfferedGoesToUnclassified() = runBlocking<Unit> {
        notes.ensureDefaultFolders()
        notes.updateFolders { it + "archive" }

        assertEquals(UNCLASSIFIED_FOLDER, assertIs<TypedNoteResult.Saved>(typed.save("C", "d", "archive")).folder)
        assertEquals(UNCLASSIFIED_FOLDER, assertIs<TypedNoteResult.Saved>(typed.save("E", "f", "nowhere")).folder)
        assertEquals(listOf(UNCLASSIFIED_FOLDER), typed.folders())
    }

    @Test
    fun aBlankTitleIsRefusedAndNothingIsWritten() = runBlocking<Unit> {
        assertEquals(TypedNoteResult.TitleMissing, typed.save(" \t\u0007 ", "body", UNCLASSIFIED_FOLDER))
        assertTrue(notes.readMetadata(strict = true).isEmpty())
    }

    @Test
    fun theTitleIsCleanedAndCutToWhatARestoreKeeps() {
        assertEquals("ab", TypedNotes.cleanTitle(" a\u0000b\n "))
        assertEquals(RestorePlan.MAX_TITLE_CHARS, TypedNotes.cleanTitle("x".repeat(250)).length)
        val emoji = "😀"
        val cut = TypedNotes.cleanTitle("x".repeat(RestorePlan.MAX_TITLE_CHARS - 1) + emoji)
        assertEquals("x".repeat(RestorePlan.MAX_TITLE_CHARS - 1), cut)
    }

    @Test
    fun aTitleEndingInHalfASurrogatePairLosesThatHalf() = runBlocking<Unit> {
        val cutEmoji = "x".repeat(RestorePlan.MAX_TITLE_CHARS - 1) + "\uD83D"
        assertEquals("x".repeat(RestorePlan.MAX_TITLE_CHARS - 1), TypedNotes.cutTitle(cutEmoji))
        val saved = assertIs<TypedNoteResult.Saved>(typed.save(cutEmoji, "body", UNCLASSIFIED_FOLDER))
        assertEquals("x".repeat(RestorePlan.MAX_TITLE_CHARS - 1), notes.readMetadata(strict = true).getValue(saved.noteId).title)
    }

    @Test
    fun aNoteTooLargeForARestoreIsRefusedAndOneAtTheLimitIsKeptByIt() = runBlocking<Unit> {
        val frame = NoteMarkdown.buildMarkdown("T", emptyList(), "", "2026-09-27").length
        assertEquals(TypedNoteResult.TooLarge, typed.save("T", "x".repeat(RestorePlan.MAX_NOTE_CHARS - frame + 1), UNCLASSIFIED_FOLDER))
        assertTrue(notes.readMetadata(strict = true).isEmpty())

        val saved = assertIs<TypedNoteResult.Saved>(typed.save("T", "x".repeat(RestorePlan.MAX_NOTE_CHARS - frame), UNCLASSIFIED_FOLDER))
        val meta = notes.readMetadata(strict = true).getValue(saved.noteId)
        val content = assertNotNull(notes.readNote(saved.noteId, strict = true))
        assertNotNull(RestorePlan.planNote(BackupNote(saved.noteId, meta.folder, meta.name, meta.title, meta.date, meta.modifiedAtMillis, content)))
    }
}
