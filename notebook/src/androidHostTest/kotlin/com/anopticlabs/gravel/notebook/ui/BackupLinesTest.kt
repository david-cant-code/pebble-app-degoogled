package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.backup.ArchiveTally
import com.anopticlabs.gravel.notebook.backup.BackupCounts
import com.anopticlabs.gravel.notebook.backup.BackupPublishOutcome
import com.anopticlabs.gravel.notebook.backup.BackupSummary
import com.anopticlabs.gravel.notebook.backup.CreatedBackup
import com.anopticlabs.gravel.notebook.backup.RestoreReport
import com.anopticlabs.gravel.notebook.storage.ReconcileResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupLinesTest {

    private fun summary(
        createdAt: String = "2026-10-02T21:30:00Z",
        notes: Int = 3,
        byFolder: Map<String, Int> = mapOf("work" to 2, "home" to 1),
        reminders: Int = 2,
        events: Int = 0,
        captures: Int = 0,
        folderTagRules: Int = 0,
        skippedNotes: Int = 0,
        skippedReminders: Int = 0,
    ) = BackupSummary(
        formatVersion = 1, createdAt = createdAt, fileBytes = 4096, notes = notes, notesByFolder = byFolder, skippedNotes = skippedNotes,
        folders = 3, skippedFolders = 0, folderTagRules = folderTagRules, captures = captures, skippedCaptures = 0,
        reminders = reminders, skippedReminders = skippedReminders, events = events, skippedEvents = 0,
    )

    private fun created(missing: Int = 0, tooLarge: Int = 0, unlisted: Int = 0, capturesMissing: Int = 0) =
        CreatedBackup(ArchiveTally(emptyMap(), ""), BackupCounts(), ReconcileResult(), missing, tooLarge, unlisted, capturesMissing)

    private fun texts(lines: List<BackupLine>) = lines.map { it.text }

    @Test
    fun aCheckListsWhenTheBackupWasMadeWhatItHoldsAndOnlyTheCountsThatAreNotZero() {
        val lines = BackupLines.contents(summary())
        assertEquals(
            listOf(BackupText.MADE_ON, BackupText.NOTES, BackupText.FOLDERS, BackupText.REMINDERS, BackupText.SIZE),
            texts(lines),
        )
        assertEquals(BackupLine(BackupText.FOLDERS, 0, listOf("work", "home"), listOf(2, 1)), lines[2])
        assertEquals(BackupLine(BackupText.MADE_ON, names = listOf("2026-10-02T21:30:00Z")), lines[0])

        val full = BackupLines.contents(summary(createdAt = "", events = 4, captures = 1, folderTagRules = 2, skippedNotes = 1, skippedReminders = 2))
        assertEquals(
            listOf(
                BackupText.NOTES, BackupText.FOLDERS, BackupText.REMINDERS, BackupText.HISTORY, BackupText.CAPTURES,
                BackupText.FOLDER_TAGS, BackupText.SIZE, BackupText.SKIP_NOTES, BackupText.SKIP_REMINDERS,
            ),
            texts(full),
        )
        assertEquals(2L, full.single { it.text == BackupText.SKIP_REMINDERS }.count)
    }

    @Test
    fun theFolderLineShowsTheEightLargestFoldersAndCountsTheRest() {
        val byFolder = (1..10).associate { "f$it" to it } + ("a" to 10)
        val folders = BackupLines.contents(summary(byFolder = byFolder)).single { it.text == BackupText.FOLDERS }
        assertEquals(listOf("a", "f10", "f9", "f8", "f7", "f6", "f5", "f4"), folders.names)
        assertEquals(listOf(10, 10, 9, 8, 7, 6, 5, 4), folders.counts)
        assertEquals(3L, folders.count)
        assertTrue(BackupLines.contents(summary(notes = 0, byFolder = emptyMap())).none { it.text == BackupText.FOLDERS })
    }

    @Test
    fun aSavedBackupSaysWhereItWentAndWhatItLacks() {
        val published = BackupPublishOutcome("b.gnbk", 10)
        assertEquals(BackupLine(BackupText.SAVED_AS, names = listOf("b.gnbk")), BackupLines.saved(published, created(), summary()).first())
        assertEquals(BackupLine(BackupText.SAVED_AS), BackupLines.saved(BackupPublishOutcome(null, 10), created(), summary()).first())

        val gaps = created(missing = 1, tooLarge = 2, unlisted = 3, capturesMissing = 4)
        val lines = BackupLines.saved(published, gaps, summary())
        assertEquals(
            listOf(BackupText.MISSING_NOTES, BackupText.TOO_LARGE_NOTES, BackupText.UNLISTED_NOTES, BackupText.MISSING_CAPTURES).zip(listOf(1L, 2L, 3L, 4L)),
            lines.filter { it.text.name.startsWith("MISSING") || it.text.name.endsWith("_NOTES") && it.text != BackupText.SKIP_NOTES }.map { it.text to it.count },
        )
        assertTrue(lines.none { it.text == BackupText.MADE_ON })
        assertTrue(BackupLines.hasGaps(gaps, summary()))
        assertTrue(BackupLines.hasGaps(created(), summary(skippedNotes = 1)))
        assertFalse(BackupLines.hasGaps(created(), summary()))
    }

    @Test
    fun aRestoreListsWhatItAddedThenWhatWasAlreadyThereThenWhatItSkipped() {
        val report = RestoreReport(notesWritten = 2, notesKept = 5, capturesAdded = 1, remindersKept = 3, eventsAdded = 4, remindersSkipped = 1)
        assertEquals(
            listOf(
                BackupText.RESTORED_NOTES_WRITTEN, BackupText.RESTORED_CAPTURES, BackupText.RESTORED_HISTORY,
                BackupText.RESTORED_NOTES_KEPT, BackupText.RESTORED_REMINDERS_KEPT, BackupText.RESTORED_SKIP_REMINDERS,
            ),
            texts(BackupLines.restored(report)),
        )
        assertEquals(listOf(BackupLine(BackupText.RESTORED_NOTHING)), BackupLines.restored(RestoreReport(notesKept = 4, remindersKept = 1)))
        assertEquals(
            listOf(BackupText.RESTORED_NOTES_KEPT, BackupText.RESTORED_SKIP_NOTES),
            texts(BackupLines.restored(RestoreReport(notesKept = 4, notesSkipped = 2))),
        )
    }
}
