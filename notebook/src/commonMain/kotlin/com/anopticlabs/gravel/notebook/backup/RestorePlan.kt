package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.notes.FolderNames
import com.anopticlabs.gravel.notebook.notes.NotePaths
import com.anopticlabs.gravel.notebook.notes.NoteSanitizers
import com.anopticlabs.gravel.notebook.notes.RESERVED_FOLDERS
import com.anopticlabs.gravel.notebook.notes.stripControlChars
import com.anopticlabs.gravel.notebook.storage.isStoreIdentity
import com.anopticlabs.gravel.notebook.storage.isStoredInstant
import com.anopticlabs.gravel.notebook.storage.isValidNoteId
import com.anopticlabs.gravel.notebook.storage.isValidStoreId
import kotlin.time.Instant

/** The decisions a restore takes per entry, shared with the inspection so both count the same skips. */
object RestorePlan {
    const val MAX_NOTE_CHARS = 1 shl 20
    const val MAX_TITLE_CHARS = 200
    const val MAX_DATE_CHARS = 32

    /** Above what a Notebook index and capture queue can hold; an archive with more is refused before a restore writes anything. */
    const val MAX_ARCHIVE_NOTES = 500_000
    const val MAX_ARCHIVE_CAPTURES = 100_000

    /** A restore adds folders only while the list stays within this many. */
    const val MAX_FOLDERS = 1_000

    /** A note the store will take: its id, folder and name valid, its content bounded, its time in the stored window; [title] and [date] cleaned. */
    class NoteDecision(
        val id: String,
        val folder: String,
        val name: String,
        val content: String,
        val title: String?,
        val date: String?,
        val modifiedAtMillis: Long,
    )

    fun planNote(note: BackupNote): NoteDecision? {
        if (!isValidNoteId(note.id) || !FolderNames.isValid(note.folder) || !NotePaths.isValidName(note.name)) return null
        if (note.content.length > MAX_NOTE_CHARS || !isStoredInstant(note.modifiedAtMillis)) return null
        return NoteDecision(
            note.id, note.folder, note.name, note.content,
            note.title?.let { field(it, MAX_TITLE_CHARS) }, note.date?.let { field(it, MAX_DATE_CHARS) }, note.modifiedAtMillis,
        )
    }

    private fun field(raw: String, max: Int): String? = raw.stripControlChars().trim().take(max).takeIf { it.isNotBlank() }

    /** Whether the queue will take [entry] with a recording of [audioBytes]; [maxBlobBytes] is the store's cap on one capture. */
    fun isRestorableCapture(entry: BackupCapture, audioBytes: Int, maxBlobBytes: Int, originalNameOk: (String) -> Boolean): Boolean =
        audioBytes in 1..maxBlobBytes &&
            isValidStoreId(entry.id) &&
            originalNameOk(entry.originalFileName) &&
            isStoredInstant(entry.queuedAtMillis)

    /**
     * Folder tags as the store accepts them: keys normalized like a folder name and dropped when
     * invalid or reserved, values cleaned like typed tags and capped, empty entries dropped.
     */
    fun sanitizeFolderTags(raw: Map<String, List<String>>?): Map<String, List<String>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for ((rawKey, rawValue) in raw) {
            val key = FolderNames.normalize(rawKey)
            if (!FolderNames.isValid(key) || key in RESERVED_FOLDERS || key in out) continue
            val seen = LinkedHashSet<String>()
            for (v in rawValue) {
                val cleaned = v.stripControlChars().trim().lowercase().take(NoteSanitizers.MAX_TAG_CHARS)
                if (cleaned.isEmpty()) continue
                seen.add(cleaned)
                if (seen.size >= NoteSanitizers.MAX_TAGS_PER_FOLDER) break
            }
            if (seen.isEmpty()) continue
            out[key] = seen.toList()
            if (out.size >= NoteSanitizers.MAX_FOLDER_TAGS_ENTRIES) break
        }
        return out
    }
}

/** How many items of each kind an archive carries, restorable or not; the writer's and a reader's must agree. */
data class BackupCounts(
    val notes: Int = 0,
    val folders: Int = 0,
    val folderTags: Int = 0,
    val captures: Int = 0,
    val reminders: Int = 0,
    val events: Int = 0,
)

/** What an archive holds and what a restore of it would skip; [healthy] means nothing would be. */
data class BackupSummary(
    val formatVersion: Int,
    val createdAt: String,
    val fileBytes: Long,
    val notes: Int,
    /** Folder to note count, restorable notes only, sorted by folder. */
    val notesByFolder: Map<String, Int>,
    val skippedNotes: Int,
    val folders: Int,
    val skippedFolders: Int,
    val folderTagRules: Int,
    val captures: Int,
    val skippedCaptures: Int,
    val reminders: Int,
    val skippedReminders: Int,
    val events: Int,
    val skippedEvents: Int,
) {
    val healthy: Boolean
        get() = skippedNotes == 0 && skippedFolders == 0 && skippedCaptures == 0 && skippedReminders == 0 && skippedEvents == 0
}

/**
 * Tallies an archive's records in the order a reader returns them, refusing one whose structure
 * no writer produces: META first and once, FOLDERS, FOLDER_TAGS, REMINDERS and EVENTS at most once
 * each, NOTE and CAPTURE records within [RestorePlan.MAX_ARCHIVE_NOTES] and
 * [RestorePlan.MAX_ARCHIVE_CAPTURES]. It counts the per-entry decisions of [RestorePlan]; what the
 * stores themselves refuse at a restore (a full index or queue file, a taken id, no free name, an
 * id in the restoring store's own space past [MAX_RESTORED_REMINDER_ID]) is counted only by the
 * restore.
 */
class ArchiveContents(private val maxBlobBytes: Int, private val originalNameOk: (String) -> Boolean) {
    private var createdAtMillis: Long? = null
    private var storeId = ""
    private val singletons = HashSet<RecordType>()
    private val byFolder = sortedMapOf<String, Int>()
    private var notes = 0
    private var skippedNotes = 0
    private var folders = 0
    private var skippedFolders = 0
    private var folderTags = 0
    private var folderTagRules = 0
    private var captures = 0
    private var skippedCaptures = 0
    private var reminders = 0
    private var skippedReminders = 0
    private var events = 0
    private var skippedEvents = 0

    fun meta(meta: BackupMeta) {
        if (createdAtMillis != null) damaged("more than one META record")
        if (!isStoreIdentity(meta.storeId)) damaged("META names no reminder store")
        createdAtMillis = meta.createdAtMillis
        storeId = meta.storeId
    }

    fun folders(names: List<String>) = once(RecordType.FOLDERS) {
        folders = names.size
        skippedFolders = names.count { !FolderNames.isValid(it) }
    }

    fun folderTags(raw: Map<String, List<String>>) = once(RecordType.FOLDER_TAGS) {
        folderTags = raw.size
        folderTagRules = RestorePlan.sanitizeFolderTags(raw).size
    }

    fun note(note: BackupNote) {
        requireMeta()
        if (++notes > RestorePlan.MAX_ARCHIVE_NOTES) damaged("more notes than a Notebook holds")
        val decision = RestorePlan.planNote(note)
        if (decision == null) skippedNotes++ else byFolder[decision.folder] = (byFolder[decision.folder] ?: 0) + 1
    }

    fun capture(entry: BackupCapture, audioBytes: Int) {
        requireMeta()
        if (++captures > RestorePlan.MAX_ARCHIVE_CAPTURES) damaged("more captures than a Notebook holds")
        if (!RestorePlan.isRestorableCapture(entry, audioBytes, maxBlobBytes, originalNameOk)) skippedCaptures++
    }

    fun reminders(items: List<BackupReminder>) = once(RecordType.REMINDERS) {
        reminders = items.size
        skippedReminders = items.count { it.toItem() == null || it.originIn(storeId) == null }
    }

    fun events(items: List<BackupReminderEvent>) = once(RecordType.EVENTS) {
        events = items.size
        skippedEvents = items.count { it.toEvent() == null || it.originIn(storeId) == null }
    }

    val counts: BackupCounts get() = BackupCounts(notes, folders, folderTags, captures, reminders, events)

    /** Only once the reader has verified the stream: an archive without META is damage. */
    fun summary(fileBytes: Long): BackupSummary {
        val created = createdAtMillis ?: damaged("no META record")
        return BackupSummary(
            formatVersion = ArchiveFormat.VERSION,
            createdAt = if (isStoredInstant(created)) Instant.fromEpochMilliseconds(created).toString() else "",
            fileBytes = fileBytes,
            notes = notes,
            notesByFolder = byFolder.toMap(),
            skippedNotes = skippedNotes,
            folders = folders,
            skippedFolders = skippedFolders,
            folderTagRules = folderTagRules,
            captures = captures,
            skippedCaptures = skippedCaptures,
            reminders = reminders,
            skippedReminders = skippedReminders,
            events = events,
            skippedEvents = skippedEvents,
        )
    }

    private inline fun once(type: RecordType, block: () -> Unit) {
        requireMeta()
        if (!singletons.add(type)) damaged("more than one $type record")
        block()
    }

    private fun requireMeta() {
        if (createdAtMillis == null) damaged("META is not the first record")
    }

    private fun damaged(detail: String): Nothing = throw InvalidBackupException(BackupProblem.DAMAGED, "backup file is damaged: $detail")
}
