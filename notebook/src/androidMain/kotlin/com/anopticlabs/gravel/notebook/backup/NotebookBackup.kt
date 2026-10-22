package com.anopticlabs.gravel.notebook.backup

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.MAX_QUEUE_ATTEMPTS
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.captureZone
import com.anopticlabs.gravel.notebook.capture.wavDurationMillis
import com.anopticlabs.gravel.notebook.notes.FolderNames
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.BlobRead
import com.anopticlabs.gravel.notebook.storage.BulkAddResult
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.PersistedFormatException
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReconcileResult
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.RestoreNoteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.storage.NoteMetadata
import com.anopticlabs.gravel.notebook.storage.ReminderFile

enum class BackupStage { CAPTURES, NOTES, REMINDERS, CHECKING, REARMING }

/** Progress of a backup or restore: the stage and how far through it, 0 to 1. */
typealias BackupProgress = (stage: BackupStage, fraction: Float) -> Unit

/**
 * What a backup wrote ([tally], [counts]), what the repair it ran first did ([repair]), and what
 * the stores list that the archive lacks: indexed notes that would not open or parse and are still
 * listed once the archive is written ([notesMissing]); notes too large for a restore to take
 * ([notesTooLarge]); note files on disk that no index read during the run listed
 * ([notesUnlisted]); and queued captures without a readable recording that are still queued once
 * the archive is written ([capturesMissing]).
 */
class CreatedBackup(
    val tally: ArchiveTally,
    val counts: BackupCounts,
    val repair: ReconcileResult,
    val notesMissing: Int,
    val notesTooLarge: Int,
    val notesUnlisted: Int,
    val capturesMissing: Int,
    /** The ids of the reminders the archive holds. */
    val reminderIds: List<Int> = emptyList(),
)

/** A finished archive in a staging file; closing deletes the file. */
class StagedBackup(val staged: StagedFile, val created: CreatedBackup) : Closeable {
    val source: BackupSource get() = BackupSource(staged.size) { staged.openRead() }

    override fun close() = staged.delete()
}

/** A picked archive copied into a staging file, its key derivation [kdf] read from the header; closing deletes the file. */
class StagedArchive(val staged: StagedFile, val kdf: Int = ArchiveFormat.KDF_PBKDF2_SHA256) : Closeable {
    /** Sealed under a recovery key: an automatic backup. */
    val isAutomatic: Boolean get() = kdf == ArchiveFormat.KDF_RECOVERY_KEY

    override fun close() = staged.delete()
}

/** A whole archive read back and authenticated: its records and item counts. */
class CheckedArchive(val tally: ArchiveTally, val counts: BackupCounts)

/** A read-back that opened as an archive but not as the one that was written. */
class BackupVerificationException(message: String) : Exception(message)

class PublishedBackup(val outcome: BackupPublishOutcome, val summary: BackupSummary)

/**
 * Per kind: what a restore added, what it left as it was, and what it skipped; the added counts are what the stores
 * hold after the re-arm, or what the merge added when the stores could not be read again.
 */
data class RestoreReport(
    val repair: ReconcileResult = ReconcileResult(),
    val notesWritten: Int = 0,
    val notesKept: Int = 0,
    val notesSkipped: Int = 0,
    val foldersAdded: Int = 0,
    val foldersSkipped: Int = 0,
    val folderTagsAdded: Int = 0,
    val capturesAdded: Int = 0,
    val capturesKept: Int = 0,
    val capturesSkipped: Int = 0,
    val remindersAdded: Int = 0,
    val remindersKept: Int = 0,
    val remindersSkipped: Int = 0,
    val eventsAdded: Int = 0,
    val eventsKept: Int = 0,
    val eventsSkipped: Int = 0,
)

/** What [BackupFlow] runs; [NotebookBackup] is the implementation. */
interface BackupOperations {
    suspend fun create(password: CharArray, onProgress: BackupProgress = { _, _ -> }): StagedBackup

    fun <H> publish(
        ops: BackupDocumentOps<H>,
        target: H,
        backup: StagedBackup,
        password: CharArray,
        onWriting: () -> Unit = {},
        onVerified: () -> Unit = {},
        onProgress: BackupProgress = { _, _ -> },
    ): PublishedBackup

    suspend fun stage(input: InputStream): StagedArchive

    suspend fun unlock(archive: StagedArchive, password: CharArray): ArchiveKey

    suspend fun inspect(archive: StagedArchive, key: ArchiveKey, onProgress: BackupProgress = { _, _ -> }): BackupSummary

    /** [beforeMerge] runs once the archive has been read through and checked, before the stores are touched. */
    suspend fun restore(archive: StagedArchive, key: ArchiveKey, beforeMerge: suspend () -> Unit = {}, onProgress: BackupProgress = { _, _ -> }): RestoreReport
}

/**
 * Backup, restore and inspection of the Notebook's stores as one password-sealed [ArchiveWriter]
 * archive, built in and read from [BackupStaging] files. A backup first runs the store's repair
 * ([NoteStore.reconcile]) and writes the captures before it reads the notes and reminders, so a
 * capture the drain publishes meanwhile is in the archive as a capture, as what it became, or
 * both. Every store read that feeds a backup is strict, so a store that cannot be read this time
 * fails the run, while a note or capture the stores list but cannot supply is left out and
 * counted. A restore reads the archive twice: the first pass authenticates all of it and writes
 * nothing, the second merges record by record after the repair. The merge is not a replacement
 * and nothing is rolled back: notes land under the index lock in batches, captures under the queue
 * lock in batches, then the reminder ids' adoptions, the event log and the reminders in one write
 * each, so a storage failure part way leaves every store in a state its readers accept; once the
 * re-arm has pruned, adoptions no reminder or history uses any more are dropped.
 */
class NotebookBackup(
    private val notes: NoteStore,
    private val queue: QueueStore,
    private val reminders: ReminderStore,
    private val events: ReminderEventStore,
    private val alarms: ReminderAlarms,
    private val refresh: ReminderRefreshSignal,
    private val staging: BackupStaging,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackupOperations {

    /** Builds an archive of the stores into a staging file; keep [password] for [publish], which reads the archive back with it. */
    override suspend fun create(password: CharArray, onProgress: BackupProgress): StagedBackup =
        stageArchive { write(it, ArchiveWriter.open(it, password), captures = true, onProgress) }

    /**
     * An automatic backup: as [create], sealed under [recoveryKey] instead of a password and
     * without the waiting recordings. Keep [recoveryKey] for [publish].
     */
    suspend fun createAutomatic(recoveryKey: CharArray, onProgress: BackupProgress = { _, _ -> }): StagedBackup =
        stageArchive { write(it, ArchiveWriter.openWithRecoveryKey(it, recoveryKey), captures = false, onProgress) }

    private suspend fun stageArchive(write: suspend (OutputStream) -> CreatedBackup): StagedBackup {
        val staged = withContext(Dispatchers.IO) { staging.newFile() }
        try {
            val created = withContext(Dispatchers.IO) { staged.openWrite().use { write(it) } }
            return StagedBackup(staged, created)
        } catch (e: Throwable) {
            staged.delete()
            throw e
        }
    }

    internal suspend fun write(out: OutputStream, password: CharArray, onProgress: BackupProgress): CreatedBackup =
        write(out, ArchiveWriter.open(out, password), captures = true, onProgress)

    private suspend fun write(out: OutputStream, writer: ArchiveWriter, captures: Boolean, onProgress: BackupProgress): CreatedBackup {
        val storeId = reminders.identity()
        val repair = notes.reconcile()
        val folders = notes.readFolders(strict = true)
        val folderTags = notes.readFolderTags(strict = true)
        val queued = if (captures) queue.readQueue(strict = true) else emptyList()
        writer.write(RecordType.META, BackupRecords.encode(BackupMeta.serializer(), BackupMeta(clock(), storeId)))
        writer.write(RecordType.FOLDERS, BackupRecords.encode(BackupRecords.FOLDERS, folders))
        writer.write(RecordType.FOLDER_TAGS, BackupRecords.encode(BackupRecords.FOLDER_TAGS, folderTags))

        val unreadCaptures = ArrayList<String>()
        for ((i, entry) in queued.withIndex()) {
            val bytes = (queue.readBlob(entry.id, quarantineCorrupt = false) as? BlobRead.Present)?.bytes
            if (bytes == null) {
                unreadCaptures += entry.id
                logger.w { "capture ${entry.id} is queued but has no readable blob; left out of the backup" }
            } else {
                val capture = BackupCapture(entry.id, entry.originalFileName, entry.queuedAtMillis, entry.attempts, entry.flagged, entry.zoneId, entry.fromWatch, entry.failure)
                writer.write(RecordType.CAPTURE, BackupRecords.capture(capture, bytes))
            }
            onProgress(BackupStage.CAPTURES, (i + 1).toFloat() / queued.size)
        }

        val index = notes.readMetadata(strict = true)
        val onDisk = notes.noteFileIds()
        val unreadNotes = ArrayList<String>()
        var notesWritten = 0
        var notesTooLarge = 0
        for ((i, entry) in index.entries.withIndex()) {
            val (id, meta) = entry
            val record = try {
                notes.readNoteRecord(id, strict = true)
            } catch (e: PersistedFormatException) {
                null
            }
            val body = record?.takeIf { it.content.length <= RestorePlan.MAX_NOTE_CHARS }?.let {
                BackupRecords.encode(BackupNote.serializer(), BackupNote(id, meta.folder, meta.name, it.title, it.date, it.modifiedAtMillis, it.content))
            }
            when {
                record == null -> {
                    unreadNotes += id
                    logger.w { "note $id is indexed but has no readable file; left out of the backup" }
                }
                body == null || body.size > ArchiveFormat.MAX_RECORD_BYTES -> {
                    notesTooLarge++
                    logger.w { "note $id is too large for a restore; left out of the backup" }
                }
                else -> {
                    writer.write(RecordType.NOTE, body)
                    notesWritten++
                }
            }
            onProgress(BackupStage.NOTES, (i + 1).toFloat() / index.size)
        }

        // The log first: a restore adopts an id before it logs history under it, so the adoptions read next cover every event read here.
        val eventItems = events.readAll(strict = true)
        val reminderFile = reminders.readFile(strict = true)
        val reminderItems = reminderFile.reminders
        val adopted = reminderFile.adopted
        writer.write(RecordType.REMINDERS, BackupRecords.encode(BackupRecords.REMINDERS, reminderItems.map { it.toBackup(adopted[it.id]) }))
        writer.write(RecordType.EVENTS, BackupRecords.encode(BackupRecords.EVENTS, eventItems.map { it.toBackup(adopted[it.reminderId]) }))
        onProgress(BackupStage.REMINDERS, 1f)
        val tally = writer.finish()

        // Deleted while the archive was written, or published by the drain before its recording was read: not a gap.
        val listedNow = notes.readMetadata(strict = true).keys
        val queuedNow = if (captures) queue.readQueue(strict = true).mapTo(HashSet()) { it.id } else emptySet()
        return CreatedBackup(
            tally = tally,
            counts = BackupCounts(
                notes = notesWritten,
                folders = folders.size,
                folderTags = folderTags.size,
                captures = queued.size - unreadCaptures.size,
                reminders = reminderItems.size,
                events = eventItems.size,
            ),
            repair = repair,
            notesMissing = unreadNotes.count { it in listedNow },
            notesTooLarge = notesTooLarge,
            notesUnlisted = onDisk.count { it !in index && it !in listedNow },
            capturesMissing = unreadCaptures.count { it in queuedNow },
            reminderIds = reminderItems.map { it.id },
        )
    }

    /**
     * Reads [input], a copy of [backup] read back from where it was published, the way a restore's
     * first pass would, with the key derived again from [password] (or the recovery key it was
     * made with), and throws unless it holds the records, hash and item counts that were written.
     * Returns what a restore of it would take and skip. Blocks on I/O.
     */
    fun verify(input: InputStream, password: CharArray, backup: StagedBackup, onProgress: BackupProgress = { _, _ -> }): BackupSummary {
        val counted = CountingInputStream(input)
        val scanned = scan(counted, ArchiveReader.open(counted, password), backup.staged.size, onProgress)
        if (scanned.tally != backup.created.tally) throw BackupVerificationException("the file's records differ from those written")
        if (scanned.counts != backup.created.counts) {
            throw BackupVerificationException("the file holds ${scanned.counts} where ${backup.created.counts} were written")
        }
        return scanned.summary
    }

    /**
     * Reads and authenticates all of [input] with [secret], as [verify] does, against nothing but
     * the archive's own closing record. Blocks on I/O.
     */
    fun check(input: InputStream, secret: CharArray): CheckedArchive {
        val counted = CountingInputStream(input)
        val scanned = scan(counted, ArchiveReader.open(counted, secret), 0L) { _, _ -> }
        return CheckedArchive(scanned.tally, scanned.counts)
    }

    /** Which of [ids] the history log holds an occurrence of; a strict read. */
    suspend fun withHistory(ids: Set<Int>): Set<Int> = withContext(Dispatchers.IO) {
        events.readAll(strict = true).mapNotNullTo(HashSet()) { e -> e.reminderId.takeIf { it in ids } }
    }

    /**
     * A SHA-256 (hex) over what an automatic backup holds, from the index and the note files'
     * sizes and times rather than their content: two calls return the same value only while the
     * notes, folders, folder tags, reminders and reminder history read the same. Strict reads, so
     * a store that cannot be read this time throws.
     */
    suspend fun fingerprint(): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(label: String, json: String) {
            digest.update(label.encodeToByteArray())
            digest.update(0)
            digest.update(json.encodeToByteArray())
            digest.update(0)
        }
        add("notes", BackupJson.encodeToString(INDEX, notes.readMetadata(strict = true).toSortedMap()))
        // A note file written without its index entry, as a process ended between the two, still moves the fingerprint.
        add("noteFiles", BackupJson.encodeToString(ListSerializer(String.serializer()), notes.noteFileStamps()))
        add("folders", BackupJson.encodeToString(BackupRecords.FOLDERS, notes.readFolders(strict = true)))
        add("folderTags", BackupJson.encodeToString(BackupRecords.FOLDER_TAGS, notes.readFolderTags(strict = true).toSortedMap()))
        // Not the next id, which the archive does not carry, nor the store id, which a backup writes the first time it runs.
        val file = reminders.readFile(strict = true)
        add("reminders", BackupJson.encodeToString(ReminderFile.serializer(), file.copy(nextId = 0, storeId = "")))
        add("events", BackupJson.encodeToString(EVENTS, events.readAll(strict = true)))
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * [BackupPublisher.publish] of [backup] into [target], its read-back checked by [verify] with [password];
     * [onVerified], which must not throw, runs once the check passed. Blocks on I/O.
     */
    override fun <H> publish(
        ops: BackupDocumentOps<H>,
        target: H,
        backup: StagedBackup,
        password: CharArray,
        onWriting: () -> Unit,
        onVerified: () -> Unit,
        onProgress: BackupProgress,
    ): PublishedBackup {
        var summary: BackupSummary? = null
        val outcome = BackupPublisher.publish(ops, target, backup.source, onWriting) {
            summary = verify(it, password, backup, onProgress)
            onVerified()
        }
        return PublishedBackup(outcome, checkNotNull(summary) { "the publisher reported success without verifying" })
    }

    /**
     * Copies a picked archive into a staging file, refusing one whose header this app does not
     * read before copying the rest. The caller closes [input].
     */
    override suspend fun stage(input: InputStream): StagedArchive {
        val staged = withContext(Dispatchers.IO) { staging.newFile() }
        try {
            val kdf = withContext(Dispatchers.IO) {
                val header = ArchiveReader.readHeader(input)
                staged.openWrite().use { out ->
                    out.write(header)
                    val buffer = ByteArray(COPY_BYTES)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                ArchiveReader.kdfOf(header)
            }
            return StagedArchive(staged, kdf)
        } catch (e: Throwable) {
            staged.delete()
            throw e
        }
    }

    /**
     * Derives [archive]'s key from [password], or from its recovery key, refusing one that cannot be any archive's; a wrong
     * one shows when [inspect] or [restore] reads.
     */
    override suspend fun unlock(archive: StagedArchive, password: CharArray): ArchiveKey =
        withContext(Dispatchers.IO) {
            val header = archive.staged.openRead().use { ArchiveReader.readHeader(it) }
            ArchiveReader.deriveKey(header, password)
        }

    /** Reads [archive] the way a restore's first pass would, touching no store. */
    override suspend fun inspect(archive: StagedArchive, key: ArchiveKey, onProgress: BackupProgress): BackupSummary =
        withContext(Dispatchers.IO) { scanStaged(archive, key, onProgress).summary }

    /**
     * Merges [archive] into the stores; throws [InvalidBackupException] before anything is written
     * when the archive does not open, and a storage exception when a store could not be read or
     * written, with whatever was merged before it left in place. Once the archive has opened,
     * [beforeMerge] runs, then the store's repair ([NoteStore.reconcile]), then the merge; the
     * alarms are re-armed from the stores after any of them, finished or not.
     */
    override suspend fun restore(archive: StagedArchive, key: ArchiveKey, beforeMerge: suspend () -> Unit, onProgress: BackupProgress): RestoreReport =
        withContext(Dispatchers.IO) {
            val scanned = scanStaged(archive, key, onProgress)
            val merged = try {
                beforeMerge()
                val repair = notes.reconcile()
                archive.staged.openRead().use { merge(ArchiveReader.open(it, key), scanned, repair, onProgress) }
            } finally {
                onProgress(BackupStage.REARMING, 0f)
                runCatching { alarms.rearmAll() }.onFailure { logger.w(it) { "re-arm after restore failed" } }
                refresh.emit()
            }
            // The counts say what the stores hold now: the re-arm drops a restored one-shot already due and prunes old history.
            // The merge is done by here, so a store that cannot be read again leaves the merge's own counts.
            try {
                val storedReminderIds = reminders.readAll(strict = true).map { it.id }.toHashSet()
                val storedEvents = events.readAll(strict = true)
                val storedEventKeys = storedEvents.map { it.key }.toHashSet()
                reminders.forgetAdoptions(storedEvents.mapTo(HashSet()) { it.reminderId })
                merged.report.copy(
                    remindersAdded = merged.remindersAdded.count { it in storedReminderIds },
                    eventsAdded = merged.eventsAdded.count { it in storedEventKeys },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "stores not read after the restore" }
                merged.report.copy(remindersAdded = merged.remindersAdded.size, eventsAdded = merged.eventsAdded.size)
            }
        }

    private class Scanned(val header: ByteArray, val tally: ArchiveTally, val counts: BackupCounts, val summary: BackupSummary)

    private fun scanStaged(archive: StagedArchive, key: ArchiveKey, onProgress: BackupProgress): Scanned =
        archive.staged.openRead().use { input ->
            val counted = CountingInputStream(input)
            scan(counted, ArchiveReader.open(counted, key), archive.staged.size, onProgress)
        }

    /** A whole pass over an archive that writes nothing; [counted] is what [reader] reads from. */
    private fun scan(counted: CountingInputStream, reader: ArchiveReader, totalBytes: Long, onProgress: BackupProgress): Scanned {
        onProgress(BackupStage.CHECKING, 0f)
        val contents = ArchiveContents(QueueStore.MAX_BLOB_BYTES, QueueStore::isValidOriginalName)
        while (true) {
            val record = reader.next() ?: break
            when (record.type) {
                RecordType.META -> contents.meta(BackupRecords.decode(BackupMeta.serializer(), record.body))
                RecordType.FOLDERS -> contents.folders(BackupRecords.decode(BackupRecords.FOLDERS, record.body))
                RecordType.FOLDER_TAGS -> contents.folderTags(BackupRecords.decode(BackupRecords.FOLDER_TAGS, record.body))
                RecordType.NOTE -> contents.note(BackupRecords.decode(BackupNote.serializer(), record.body))
                RecordType.CAPTURE -> BackupRecords.parseCaptureEntry(record.body).let { (entry, audioBytes) -> contents.capture(entry, audioBytes) }
                RecordType.REMINDERS -> contents.reminders(BackupRecords.decode(BackupRecords.REMINDERS, record.body))
                RecordType.EVENTS -> contents.events(BackupRecords.decode(BackupRecords.EVENTS, record.body))
                RecordType.END -> error("the reader returns no END record")
            }
            if (totalBytes > 0) onProgress(BackupStage.CHECKING, (counted.count.toFloat() / totalBytes).coerceAtMost(1f))
        }
        val tally = reader.verifiedTally ?: error("the reader returned null before verifying the stream")
        return Scanned(reader.header, tally, contents.counts, contents.summary(counted.count))
    }

    private class Merged(val report: RestoreReport, val remindersAdded: List<Int>, val eventsAdded: List<ReminderEventKey>)

    private suspend fun merge(reader: ArchiveReader, scanned: Scanned, repair: ReconcileResult, onProgress: BackupProgress): Merged {
        if (!reader.header.contentEquals(scanned.header)) {
            throw InvalidBackupException(BackupProblem.DAMAGED, "backup file is damaged: it changed between reads")
        }
        var report = RestoreReport(repair = repair)
        val pendingNotes = ArrayList<RestoreNoteRequest>()
        var pendingChars = 0L
        suspend fun flushNotes() {
            if (pendingNotes.isEmpty()) return
            val result = notes.restoreNotes(pendingNotes)
            report = report.copy(
                notesWritten = report.notesWritten + result.written,
                notesKept = report.notesKept + result.kept,
                notesSkipped = report.notesSkipped + result.skipped,
            )
            pendingNotes.clear()
            pendingChars = 0
        }
        val pendingCaptures = LinkedHashMap<String, Pair<QueuedCapture, ByteArray>>()
        var pendingAudio = 0L
        suspend fun flushCaptures() {
            if (pendingCaptures.isEmpty()) return
            val result = queue.enqueueAll(pendingCaptures.values.map { it.first }) { pendingCaptures.getValue(it.id).second }
            report = report.copy(
                capturesAdded = report.capturesAdded + result.added,
                capturesKept = report.capturesKept + result.kept,
                capturesSkipped = report.capturesSkipped + result.skipped,
            )
            pendingCaptures.clear()
            pendingAudio = 0
        }
        var notesSeen = 0
        var capturesSeen = 0
        var archiveStore = ""
        var reminderEntries: List<BackupReminder> = emptyList()
        var eventEntries: List<BackupReminderEvent> = emptyList()
        while (true) {
            val record = reader.next() ?: break
            when (record.type) {
                RecordType.META -> archiveStore = BackupRecords.decode(BackupMeta.serializer(), record.body).storeId
                RecordType.FOLDERS -> {
                    val names = BackupRecords.decode(BackupRecords.FOLDERS, record.body)
                    val valid = names.filter { FolderNames.isValid(it) }
                    var added = 0
                    var overCap = 0
                    notes.updateFolders { current ->
                        val new = valid.filterNot { it in current }.distinct()
                        val room = (RestorePlan.MAX_FOLDERS - current.size).coerceAtLeast(0)
                        added = minOf(new.size, room)
                        overCap = new.size - added
                        current + new.take(room)
                    }
                    report = report.copy(foldersAdded = added, foldersSkipped = names.size - valid.size + overCap)
                }
                RecordType.FOLDER_TAGS -> {
                    val tags = RestorePlan.sanitizeFolderTags(BackupRecords.decode(BackupRecords.FOLDER_TAGS, record.body))
                    if (tags.isNotEmpty()) {
                        var added = 0
                        notes.updateFolderTags { current ->
                            val new = tags.filterKeys { it !in current }
                            added = new.size
                            current + new
                        }
                        report = report.copy(folderTagsAdded = added)
                    }
                }
                RecordType.NOTE -> {
                    val decision = RestorePlan.planNote(BackupRecords.decode(BackupNote.serializer(), record.body))
                    if (decision == null) {
                        report = report.copy(notesSkipped = report.notesSkipped + 1)
                    } else {
                        pendingNotes += with(decision) { RestoreNoteRequest(id, folder, name, content, title, date, modifiedAtMillis) }
                        pendingChars += decision.content.length
                        if (pendingNotes.size >= RESTORE_BATCH || pendingChars >= RESTORE_BATCH_CHARS) flushNotes()
                    }
                    onProgress(BackupStage.NOTES, (++notesSeen).toFloat() / scanned.counts.notes)
                }
                RecordType.CAPTURE -> {
                    onProgress(BackupStage.CAPTURES, (++capturesSeen).toFloat() / scanned.counts.captures)
                    val body = BackupRecords.parseCapture(record.body)
                    val e = body.entry
                    if (!RestorePlan.isRestorableCapture(e, body.audio.size, QueueStore.MAX_BLOB_BYTES, QueueStore::isValidOriginalName)) {
                        report = report.copy(capturesSkipped = report.capturesSkipped + 1)
                    } else {
                        // A repeated id lands in its own batch, so the queue sees it as already queued.
                        if (e.id in pendingCaptures) flushCaptures()
                        val zoneId = e.zoneId?.takeIf { captureZone(it) != null }
                        pendingCaptures[e.id] = QueuedCapture(
                            e.id, e.originalFileName, e.queuedAtMillis, e.attempts.coerceIn(0, MAX_QUEUE_ATTEMPTS), e.flagged, zoneId,
                            durationMillis = wavDurationMillis(body.audio), fromWatch = e.fromWatch,
                            failure = e.failure?.let { CaptureFailureReason.of(it).code },
                        ) to body.audio
                        pendingAudio += body.audio.size
                        if (pendingCaptures.size >= RESTORE_BATCH || pendingAudio >= RESTORE_BATCH_AUDIO_BYTES) flushCaptures()
                    }
                }
                RecordType.REMINDERS -> reminderEntries = BackupRecords.decode(BackupRecords.REMINDERS, record.body)
                RecordType.EVENTS -> eventEntries = BackupRecords.decode(BackupRecords.EVENTS, record.body)
                RecordType.END -> error("the reader returns no END record")
            }
        }
        flushCaptures()
        flushNotes()

        val origins = (reminderEntries.filter { it.toItem() != null }.mapNotNull { it.originIn(archiveStore) } +
            eventEntries.filter { it.toEvent() != null }.mapNotNull { it.originIn(archiveStore) }).distinct()
        val localIds = if (origins.isEmpty()) emptyMap() else reminders.adopt(origins)
        // Alarms and notifications under an archive id that now maps elsewhere (the replaced store's, after a reset) would
        // show the reminder twice; an id a reminder here holds is left to that reminder.
        val heldHere = reminders.readAll(strict = true).mapTo(HashSet()) { it.id }
        val renumbered = eventEntries.filter { e -> e.reminderId !in heldHere && e.originIn(archiveStore)?.let(localIds::get).let { it != null && it != e.reminderId } }
        // An old snooze alarm goes only where the archive holds a snooze still to come; the re-arm decides on that one under the new id.
        val at = clock()
        alarms.disarmFormer(
            reminderEntries.filter { e -> e.id !in heldHere && e.originIn(archiveStore)?.let(localIds::get).let { it != null && it != e.id } }.map { it.id },
            renumbered.map { ReminderEventKey(it.reminderId, it.originalFireMillis) },
            renumbered.filter { e -> e.latestSnoozeFireAt?.let { it > at } == true }.map { ReminderEventKey(it.reminderId, it.originalFireMillis) },
        )
        // The event log lands before the reminders, so a re-arm never sees a restored reminder
        // without the occurrences it already fired.
        val items = reminderEntries.mapNotNull { e -> e.originIn(archiveStore)?.let(localIds::get)?.let { e.toItem(it) } }
        val eventItems = eventEntries.mapNotNull { e -> e.originIn(archiveStore)?.let(localIds::get)?.let { e.toEvent(it) } }
        val eventsAdded = if (eventItems.isEmpty()) BulkAddResult<ReminderEventKey>(emptyList(), 0, 0) else events.addAllSkippingExistingKeys(eventItems)
        val remindersAdded = if (items.isEmpty()) BulkAddResult<Int>(emptyList(), 0, 0) else reminders.addAllSkippingExistingIds(items)
        onProgress(BackupStage.REMINDERS, 1f)

        report = report.copy(
            remindersKept = remindersAdded.existing,
            remindersSkipped = reminderEntries.size - items.size + remindersAdded.dropped,
            eventsKept = eventsAdded.existing,
            eventsSkipped = eventEntries.size - eventItems.size + eventsAdded.dropped,
        )
        return Merged(report, remindersAdded.added, eventsAdded.added)
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private companion object {
        const val RESTORE_BATCH = 64
        const val RESTORE_BATCH_CHARS = 4L shl 20
        const val RESTORE_BATCH_AUDIO_BYTES = 32L shl 20
        const val COPY_BYTES = 64 * 1024
        val INDEX = MapSerializer(String.serializer(), NoteMetadata.serializer())
        val EVENTS = ListSerializer(ReminderEvent.serializer())
        val logger = Logger.withTag("NotebookBackup")
    }
}
