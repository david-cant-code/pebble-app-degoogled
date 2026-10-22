package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.backup.BackupPublishOutcome
import com.anopticlabs.gravel.notebook.backup.BackupSummary
import com.anopticlabs.gravel.notebook.backup.CreatedBackup
import com.anopticlabs.gravel.notebook.backup.RestoreReport

internal enum class BackupText {
    MADE_ON, NOTES, FOLDERS, REMINDERS, HISTORY, CAPTURES, FOLDER_TAGS, SIZE,
    SKIP_NOTES, SKIP_FOLDERS, SKIP_CAPTURES, SKIP_REMINDERS, SKIP_HISTORY,
    SAVED_AS,
    MISSING_NOTES, TOO_LARGE_NOTES, UNLISTED_NOTES, MISSING_CAPTURES,
    RESTORED_NOTHING, RESTORED_NOTES_WRITTEN, RESTORED_NOTES_KEPT, RESTORED_FOLDERS, RESTORED_FOLDER_TAGS,
    RESTORED_CAPTURES, RESTORED_CAPTURES_KEPT, RESTORED_REMINDERS, RESTORED_REMINDERS_KEPT, RESTORED_HISTORY,
    RESTORED_SKIP_NOTES, RESTORED_SKIP_FOLDERS, RESTORED_SKIP_CAPTURES, RESTORED_SKIP_REMINDERS, RESTORED_SKIP_HISTORY,
}

/** One line of a backup dialog: [text] picks the wording, [count] is its number, [names] (with [counts]) what it names. */
internal data class BackupLine(
    val text: BackupText,
    val count: Long = 0,
    val names: List<String> = emptyList(),
    val counts: List<Int> = emptyList(),
)

internal object BackupLines {
    const val SHOWN_FOLDERS = 8

    /** What a check or a restore's confirmation shows: what the archive holds, then what a restore would skip. */
    fun contents(summary: BackupSummary): List<BackupLine> =
        listOfNotNull(summary.createdAt.takeIf { it.isNotEmpty() }?.let { BackupLine(BackupText.MADE_ON, names = listOf(it)) }) +
            holdings(summary) + skips(summary)

    /** What a saved backup shows: where it went, what it holds, what the stores listed that it lacks, what a restore would skip. */
    fun saved(outcome: BackupPublishOutcome, created: CreatedBackup, summary: BackupSummary): List<BackupLine> =
        listOf(BackupLine(BackupText.SAVED_AS, names = listOfNotNull(outcome.displayName))) + holdings(summary) + listOfNotNull(
            counted(BackupText.MISSING_NOTES, created.notesMissing),
            counted(BackupText.TOO_LARGE_NOTES, created.notesTooLarge),
            counted(BackupText.UNLISTED_NOTES, created.notesUnlisted),
            counted(BackupText.MISSING_CAPTURES, created.capturesMissing),
        ) + skips(summary)

    fun hasGaps(created: CreatedBackup, summary: BackupSummary): Boolean =
        created.notesMissing + created.notesTooLarge + created.notesUnlisted + created.capturesMissing > 0 || !summary.healthy

    fun restored(report: RestoreReport): List<BackupLine> {
        val added = listOfNotNull(
            counted(BackupText.RESTORED_NOTES_WRITTEN, report.notesWritten),
            counted(BackupText.RESTORED_FOLDERS, report.foldersAdded),
            counted(BackupText.RESTORED_FOLDER_TAGS, report.folderTagsAdded),
            counted(BackupText.RESTORED_CAPTURES, report.capturesAdded),
            counted(BackupText.RESTORED_REMINDERS, report.remindersAdded),
            counted(BackupText.RESTORED_HISTORY, report.eventsAdded),
        )
        val skipped = listOfNotNull(
            counted(BackupText.RESTORED_SKIP_NOTES, report.notesSkipped),
            counted(BackupText.RESTORED_SKIP_FOLDERS, report.foldersSkipped),
            counted(BackupText.RESTORED_SKIP_CAPTURES, report.capturesSkipped),
            counted(BackupText.RESTORED_SKIP_REMINDERS, report.remindersSkipped),
            counted(BackupText.RESTORED_SKIP_HISTORY, report.eventsSkipped),
        )
        if (added.isEmpty() && skipped.isEmpty()) return listOf(BackupLine(BackupText.RESTORED_NOTHING))
        val kept = listOfNotNull(
            counted(BackupText.RESTORED_NOTES_KEPT, report.notesKept),
            counted(BackupText.RESTORED_CAPTURES_KEPT, report.capturesKept),
            counted(BackupText.RESTORED_REMINDERS_KEPT, report.remindersKept),
        )
        return added + kept + skipped
    }

    private fun holdings(summary: BackupSummary): List<BackupLine> {
        val folders = summary.notesByFolder.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val shown = folders.take(SHOWN_FOLDERS)
        return listOfNotNull(
            BackupLine(BackupText.NOTES, summary.notes.toLong()),
            shown.takeIf { it.isNotEmpty() }?.let {
                BackupLine(BackupText.FOLDERS, (folders.size - shown.size).toLong(), it.map { e -> e.key }, it.map { e -> e.value })
            },
            BackupLine(BackupText.REMINDERS, summary.reminders.toLong()),
            counted(BackupText.HISTORY, summary.events),
            counted(BackupText.CAPTURES, summary.captures),
            counted(BackupText.FOLDER_TAGS, summary.folderTagRules),
            BackupLine(BackupText.SIZE, summary.fileBytes),
        )
    }

    private fun skips(summary: BackupSummary): List<BackupLine> = listOfNotNull(
        counted(BackupText.SKIP_NOTES, summary.skippedNotes),
        counted(BackupText.SKIP_FOLDERS, summary.skippedFolders),
        counted(BackupText.SKIP_CAPTURES, summary.skippedCaptures),
        counted(BackupText.SKIP_REMINDERS, summary.skippedReminders),
        counted(BackupText.SKIP_HISTORY, summary.skippedEvents),
    )

    private fun counted(text: BackupText, n: Int) = if (n > 0) BackupLine(text, n.toLong()) else null
}
