package com.anopticlabs.gravel.notebook.sources

import com.anopticlabs.gravel.notebook.backup.RestorePlan
import com.anopticlabs.gravel.notebook.notes.NoteBuilder
import com.anopticlabs.gravel.notebook.notes.captureRouteFolders
import com.anopticlabs.gravel.notebook.notes.noteWriteFolder
import com.anopticlabs.gravel.notebook.notes.stripControlChars
import com.anopticlabs.gravel.notebook.storage.NoteStore
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

sealed interface TypedNoteResult {
    data class Saved(val noteId: String, val folder: String) : TypedNoteResult

    data object TitleMissing : TypedNoteResult

    /** Over what a restore takes, so a backup would leave it out. */
    data object TooLarge : TypedNoteResult
}

/** Notes typed in the app, written as they are with no model involved. */
class TypedNotes(
    private val notes: NoteStore,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
) {
    /** The folders a typed note may go to, as a capture's note may; read strictly. */
    suspend fun folders(): List<String> = withContext(Dispatchers.IO) { captureRouteFolders(notes.readFolders(strict = true)) }

    /**
     * Saves a note titled [title], cleaned of control characters and cut to what a restore keeps,
     * in [folder] when it is one of [folders], else in the unclassified folder.
     */
    suspend fun save(title: String, body: String, folder: String): TypedNoteResult {
        val cleanTitle = cleanTitle(title)
        if (cleanTitle.isEmpty()) return TypedNoteResult.TitleMissing
        val now = Instant.fromEpochMilliseconds(clock()).toLocalDateTime(zone())
        val target = noteWriteFolder(folder, folders())
        val note = NoteBuilder.typedNote(cleanTitle, body, target, now)
        if (note.markdown.length > RestorePlan.MAX_NOTE_CHARS) return TypedNoteResult.TooLarge
        val created = notes.createListedNote(target, note.filename, note.markdown, title = note.title, date = note.date)
        return TypedNoteResult.Saved(created.id, created.folder)
    }

    companion object {
        /** [raw] cut to what a restore keeps, never between the two halves of a surrogate pair. */
        fun cutTitle(raw: String): String {
            val end = minOf(raw.length, RestorePlan.MAX_TITLE_CHARS)
            return raw.substring(0, if (end > 0 && raw[end - 1].isHighSurrogate()) end - 1 else end)
        }

        fun cleanTitle(raw: String): String = cutTitle(raw.stripControlChars().trim()).trim()
    }
}
