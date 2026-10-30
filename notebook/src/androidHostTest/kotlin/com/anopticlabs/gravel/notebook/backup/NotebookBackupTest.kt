package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.capture.MAX_QUEUE_ATTEMPTS
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.RecordingBuffer
import com.anopticlabs.gravel.notebook.notes.NoteMarkdown
import com.anopticlabs.gravel.notebook.reminders.MAX_REMINDER_MESSAGE_CHARS
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.BlobRead
import com.anopticlabs.gravel.notebook.storage.EditNoteResult
import com.anopticlabs.gravel.notebook.storage.EncryptedFiles
import com.anopticlabs.gravel.notebook.storage.EncryptedJsonFile
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.KeyUnavailableException
import com.anopticlabs.gravel.notebook.storage.NoteMetadata
import com.anopticlabs.gravel.notebook.storage.NoteRecord
import com.anopticlabs.gravel.notebook.storage.NotebookJson
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReconcileResult
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderFile
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.TransientCryptoException
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two Notebook roots: [source] is backed up, [target] restored into, each with its own stores over a software key. */
class NotebookBackupTest {

    private val password = "correct horse battery".toCharArray()
    private var now = 1_700_000_000_000L
    private val source = Side(Files.createTempDirectory("notebook-src").toFile())
    private val target = Side(Files.createTempDirectory("notebook-dst").toFile())

    private inner class Side(
        val root: File,
        queueMetaBytes: Long = QueueStore.MAX_QUEUE_META_BYTES,
        reminderBytes: Long = EncryptedJsonFile.SMALL_FILE_BYTES,
        metaBytes: Long = NoteStore.MAX_META_BYTES,
        minFreeBytes: Long = BackupStaging.MIN_FREE_BYTES,
        firstId: () -> Int = { 1 },
        usableSpace: (File) -> Long = { it.usableSpace },
    ) {
        val fake = FakeCryptographer(softwareCrypto())
        val notes = NoteStore(root, fake, maxMetaBytes = metaBytes) { now }
        val queue = QueueStore(root, fake, queueMetaBytes) { now }
        val reminders = ReminderStore(root, fake, reminderBytes, firstId)
        val events = ReminderEventStore(root, fake)
        val scheduler = FakeScheduler()
        val notifier = FakeNotifier()
        val settings = FakeSettings()
        val refresh = ReminderRefreshSignal()
        val alarms = ReminderAlarms(reminders, events, scheduler, notifier, settings, refresh, { now }, { TimeZone.UTC })
        val staging = BackupStaging(root, minFreeBytes, usableSpace)
        val backup = NotebookBackup(notes, queue, reminders, events, alarms, refresh, staging, clock = { now })
        val files = EncryptedFiles(root, fake)

        fun noteFile(id: String) = File(root, "${NoteStore.NOTES_DIR}/$id")

        fun noteFiles(): List<File> = File(root, NoteStore.NOTES_DIR).listFiles().orEmpty().toList()

        fun stagingFiles(): List<File> = File(root, BackupStaging.DIR).listFiles().orEmpty().toList()

        fun quarantined() = files.quarantineDir.listFiles().orEmpty().map { it.name }

        /** Every file under the root with its bytes, for "nothing changed" checks. */
        fun fingerprint(): Map<String, List<Byte>> = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }

        /** A note file the index does not list, holding [json] as its plaintext. */
        fun unlistedFile(id: String, json: String) = files.writeText(noteFile(id), json)
    }

    @AfterTest
    fun cleanUp() {
        source.root.deleteRecursively()
        target.root.deleteRecursively()
    }

    private fun md(title: String, tags: List<String>, body: String) = NoteMarkdown.buildMarkdown(title, tags, body, "2026-05-31")

    private fun id(n: Int) = n.toString(16).padStart(32, '0')

    private val audio = ByteArray(3000) { (it * 7).toByte() }
    private val future get() = now + 3_600_000L

    private suspend fun create(side: Side = source): ByteArray = side.backup.create(password).use { it.bytes() }

    /** The archive as the publisher copies it: the staging file's own layer opened. */
    private fun StagedBackup.bytes(): ByteArray = source.open().use { it.readBytes() }

    private suspend fun restore(bytes: ByteArray, side: Side = target) = side.backup.stage(ByteArrayInputStream(bytes)).use { side.backup.restore(it, side.backup.unlock(it, password)) }

    private suspend fun inspect(bytes: ByteArray, side: Side = target) = side.backup.stage(ByteArrayInputStream(bytes)).use { side.backup.inspect(it, side.backup.unlock(it, password)) }

    /** [side]'s store identity, for a hand-built archive that counts reminder ids as that store does. */
    private suspend fun own(side: Side = target) = side.reminders.identity()

    private lateinit var plansId: String
    private lateinit var rawId: String

    private suspend fun seed(): ByteArray {
        plansId = source.notes.createNote(
            "work", "q3-plans_05.31.26_1407.md", md("Q3 plans", listOf("Shopping", "Errands"), "the budget meeting is on tuesday"),
            title = "Q3 plans", date = "2026-05-31", modifiedAtMillis = 1_700_000_000_500L,
        )
        rawId = source.notes.createNote("archive", "raw_05.31.26_1408.md", md("Raw", listOf("x"), "verbatim words"), title = "Raw", modifiedAtMillis = 2L)
        source.notes.createFolder("work", "budget, meeting")
        source.queue.enqueue(QueuedCapture("fg_1", "recording_1.m4a", 1_700_000_000_250L, attempts = 2, flagged = true, zoneId = "Europe/Berlin"), audio)
        val weekly = source.reminders.create { ReminderItem(it, future, "weekly", RecurrenceRule(RecurrenceFrequency.WEEKLY, 2, now)) }
        source.reminders.create { ReminderItem(it, future + 1, "later once") }
        source.events.appendFire(ReminderEvent(weekly.id, now - 5000L, "weekly", now - 4000L, completedAt = now - 3000L))
        return create()
    }

    private fun note(id: String, folder: String = "work", name: String = "a.md", content: String = "body", modifiedAtMillis: Long = 1000L, title: String? = null) =
        BackupNote(id, folder, name, title, null, modifiedAtMillis, content)

    /** An archive holding exactly these records, META first. */
    private fun archive(
        notes: List<BackupNote> = emptyList(),
        folders: List<String>? = null,
        captures: List<Pair<BackupCapture, ByteArray>> = emptyList(),
        reminders: List<BackupReminder>? = null,
        folderTags: Map<String, List<String>>? = null,
        events: List<BackupReminderEvent>? = null,
        raw: List<Pair<RecordType, ByteArray>> = emptyList(),
        meta: Boolean = true,
        storeId: String = ARCHIVE_STORE,
        random: java.security.SecureRandom = java.security.SecureRandom(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ArchiveWriter.open(out, password, random)
        if (meta) writer.write(RecordType.META, BackupRecords.encode(BackupMeta.serializer(), BackupMeta(now, storeId)))
        folders?.let { writer.write(RecordType.FOLDERS, BackupRecords.encode(BackupRecords.FOLDERS, it)) }
        folderTags?.let { writer.write(RecordType.FOLDER_TAGS, BackupRecords.encode(BackupRecords.FOLDER_TAGS, it)) }
        for (n in notes) writer.write(RecordType.NOTE, BackupRecords.encode(BackupNote.serializer(), n))
        for ((c, a) in captures) writer.write(RecordType.CAPTURE, BackupRecords.capture(c, a))
        reminders?.let { writer.write(RecordType.REMINDERS, BackupRecords.encode(BackupRecords.REMINDERS, it)) }
        events?.let { writer.write(RecordType.EVENTS, BackupRecords.encode(BackupRecords.EVENTS, it)) }
        for ((type, body) in raw) writer.write(type, body)
        writer.finish()
        return out.toByteArray()
    }

    // Round trips

    @Test
    fun everyStoreRoundTripsIntoAFreshNotebook() = runBlocking<Unit> {
        val bytes = seed()

        val report = restore(bytes)

        assertEquals(2, report.notesWritten)
        assertEquals(0, report.notesSkipped)
        assertEquals(listOf("unclassified", "work"), target.notes.readFolders())
        assertEquals(mapOf("work" to listOf("work", "budget", "meeting")), target.notes.readFolderTags())
        assertEquals(source.notes.readMetadata(), target.notes.readMetadata())
        assertEquals(source.notes.readNote(plansId), target.notes.readNote(plansId))
        val stems = target.notes.readStems(strict = true)
        assertEquals(setOf("budget", "meet", "tuesdai"), stems.getValue(plansId).tokens)
        assertEquals(setOf("shop", "errand"), stems.getValue(plansId).tags)
        assertNull(stems[rawId])
        assertEquals(listOf(QueuedCapture("fg_1", "recording_1.m4a", 1_700_000_000_250L, attempts = 2, flagged = true, zoneId = "Europe/Berlin")), target.queue.readQueue())
        assertContentEquals(audio, assertIs<BlobRead.Present>(target.queue.readBlob("fg_1")).bytes)
        assertEquals(1, report.capturesAdded)
        // Another store's ids, adopted in archive order into a fresh store, come out as 1 and 2 again.
        assertEquals(source.reminders.readAll(strict = true), target.reminders.readAll(strict = true))
        assertEquals(source.events.readAll(strict = true), target.events.readAll(strict = true))
        assertEquals(2, report.remindersAdded)
        assertEquals(1, report.eventsAdded)
        assertEquals(emptyList(), target.quarantined())
        assertEquals(emptyList(), target.stagingFiles())
    }

    @Test
    fun anotherStoresRemindersTakeTheNextIdsAndTheAlarmsAreReArmed() = runBlocking<Unit> {
        val bytes = seed()
        val ticks = mutableListOf<Unit>()
        val collector = launch { target.refresh.events.first().also { ticks += Unit } }

        restore(bytes)

        withTimeout(2000) { collector.join() }
        val created = target.reminders.create { ReminderItem(it, future, "new") }
        assertEquals(3, created.id)
        assertEquals(setOf(1, 2), target.scheduler.fires.keys)
        assertEquals(1, ticks.size)
    }

    @Test
    fun aRestoredOneShotAlreadyDueIsShownOnceAndDroppedAndTheReportSaysWhatRemains() = runBlocking<Unit> {
        val report = restore(archive(reminders = listOf(BackupReminder(4, now - 1000L, "past once"), BackupReminder(5, future, "later")), storeId = own()))

        assertEquals(listOf(ReminderEventKey(4, now - 1000L) to "past once"), target.notifier.posted)
        assertEquals(listOf(5), target.reminders.readAll(strict = true).map { it.id })
        assertEquals(setOf(5), target.scheduler.fires.keys)
        assertEquals(1, report.remindersAdded)
        assertEquals(6, target.reminders.create { ReminderItem(it, future, "next") }.id)
    }

    @Test
    fun restoredHistoryPastTheRetentionWindowIsPrunedAndNotCountedAsAdded() = runBlocking<Unit> {
        val old = now - 200L * 86_400_000L
        val report = restore(archive(events = listOf(BackupReminderEvent(1, old, "old", old + 1), BackupReminderEvent(1, now - 5L, "recent", now - 4L))))

        assertEquals(listOf("recent"), target.events.readAll(strict = true).map { it.message })
        assertEquals(1, report.eventsAdded)
        assertEquals(0, report.eventsSkipped)
    }

    @Test
    fun anEmptyNotebookRoundTripsToAnEmptyReport() = runBlocking<Unit> {
        val report = restore(create())

        assertEquals(RestoreReport(), report)
        assertEquals(emptyMap(), target.notes.readMetadata())
        assertTrue(target.notes.readFolders().isNotEmpty())
    }

    @Test
    fun theInspectionReportsWhatTheArchiveHoldsAndChangesNothing() = runBlocking<Unit> {
        val bytes = seed()
        val before = target.fingerprint()

        val summary = inspect(bytes)

        assertEquals(before, target.fingerprint())
        assertEquals(1, summary.formatVersion)
        assertEquals(bytes.size.toLong(), summary.fileBytes)
        assertEquals(2, summary.notes)
        assertEquals(mapOf("archive" to 1, "work" to 1), summary.notesByFolder)
        assertEquals(2, summary.folders)
        assertEquals(1, summary.folderTagRules)
        assertEquals(1, summary.captures)
        assertEquals(2, summary.reminders)
        assertEquals(1, summary.events)
        assertEquals("2023-11-14T22:13:20Z", summary.createdAt)
        assertTrue(summary.healthy)
    }

    @Test
    fun aRerunRestoreGivesKeptNotesTheirMissingStems() = runBlocking<Unit> {
        val bytes = seed()
        restore(bytes)
        File(target.root, NoteStore.STEMS_DIR).deleteRecursively()

        restore(bytes)

        assertNotNull(target.notes.readStems(strict = true)[plansId])
    }

    // Merge rules

    @Test
    fun aNoteMovedSinceTheBackupIsMatchedByIdAndNotDuplicated() = runBlocking<Unit> {
        val bytes = seed()
        restore(bytes)
        target.notes.moveNote(plansId, "home")

        val report = restore(bytes)

        assertEquals(2, report.notesKept)
        assertEquals(2, target.notes.readMetadata().size)
        assertEquals("home", target.notes.readMetadata().getValue(plansId).folder)
    }

    @Test
    fun aNoteWrittenOverTakesTheArchivedContentInItsOwnFolderUnderItsOwnName() = runBlocking<Unit> {
        val bytes = seed()
        restore(bytes)
        target.notes.moveNote(plansId, "home")
        target.notes.updateNote(plansId, "older edit", title = "Older", modifiedAtMillis = 1L)

        val report = restore(bytes)

        assertEquals(1, report.notesWritten)
        val entry = target.notes.readMetadata().getValue(plansId)
        assertEquals("home", entry.folder)
        assertEquals("q3-plans_05.31.26_1407.md", entry.name)
        assertEquals("Q3 plans", entry.title)
        assertEquals(source.notes.readNote(plansId), target.notes.readNote(plansId))
    }

    @Test
    fun anUnknownIdWhoseNameIsTakenInItsFolderIsWrittenUnderASuffixedName() = runBlocking<Unit> {
        val local = target.notes.createNote("work", "a.md", "local")

        val report = restore(archive(notes = listOf(note(id(7), content = "restored"), note(id(8), content = "also"))))

        assertEquals(2, report.notesWritten)
        val names = target.notes.readMetadata().mapValues { it.value.name }
        assertEquals("a.md", names.getValue(local))
        assertEquals("a-2.md", names.getValue(id(7)))
        assertEquals("a-3.md", names.getValue(id(8)))
        assertEquals("restored", target.notes.readNote(id(7)))
    }

    @Test
    fun anUnlistedFileUnderAnArchivedIdIsLeftAloneAndCounted() = runBlocking<Unit> {
        target.unlistedFile(id(7), "not a record")
        val before = target.noteFile(id(7)).readBytes()

        val report = restore(archive(notes = listOf(note(id(7)))))

        assertEquals(1, report.notesSkipped)
        assertEquals(0, report.notesWritten)
        assertContentEquals(before, target.noteFile(id(7)).readBytes())
        assertNull(target.notes.readMetadata()[id(7)])
    }

    @Test
    fun anIdenticalNoteIsKeptAndItsMissingFileRewritten() = runBlocking<Unit> {
        val bytes = seed()
        restore(bytes)
        target.noteFile(plansId).delete()

        val report = restore(bytes)

        assertEquals(0, report.notesWritten)
        assertEquals(2, report.notesKept)
        assertTrue(target.noteFile(plansId).exists())
        assertEquals(1, report.capturesKept)
        assertEquals(2, report.remindersKept)
        assertEquals(1, report.eventsKept)
        assertEquals(0, report.foldersAdded)
        assertEquals(0, report.folderTagsAdded)
    }

    @Test
    fun aNewerLocalNoteIsKeptAndAnOlderOneOverwritten() = runBlocking<Unit> {
        val bytes = seed()
        restore(bytes)
        target.notes.updateNote(plansId, "edited later", title = "Later", modifiedAtMillis = 1_700_000_001_000L)
        target.notes.updateNote(rawId, "edited earlier", modifiedAtMillis = 1L)

        val report = restore(bytes)

        assertEquals("edited later", target.notes.readNote(plansId))
        assertEquals("Later", target.notes.readMetadata().getValue(plansId).title)
        assertEquals(source.notes.readNote(rawId), target.notes.readNote(rawId))
        assertEquals(1, report.notesWritten)
        assertEquals(1, report.notesKept)
    }

    @Test
    fun aNoteEditedWhileTheClockReadBehindItsBackedUpCopyIsKeptAtTheRestore() = runBlocking<Unit> {
        val bytes = seed()
        val plans = source.notes.readNote(plansId)!!
        assertTrue(now < source.notes.readMetadata().getValue(plansId).modifiedAtMillis)
        assertEquals(EditNoteResult.Saved("q3-plans_05.31.26_1407.md"), source.notes.editNote(plansId, NoteStore.sha256(plans), "edited after the backup", null))

        val report = restore(bytes, source)

        assertEquals("edited after the backup", source.notes.readNote(plansId, strict = true))
        assertEquals(0, report.notesWritten)
        assertEquals(2, report.notesKept)
    }

    @Test
    fun aKeptNoteThatCannotBeReadStopsTheRestoreAndIsNotRewritten() = runBlocking<Unit> {
        val bytes = seed()
        restore(bytes)
        val before = target.noteFile(plansId).readBytes()
        target.fake.failOpenWhenAadContains = "notes/$plansId"
        assertFailsWith<TransientCryptoException> { restore(bytes) }
        target.fake.failOpenWhenAadContains = null
        target.fake.keyUnavailableWhenAadContains = "notes/$plansId"
        assertFailsWith<KeyUnavailableException> { restore(bytes) }
        target.fake.keyUnavailableWhenAadContains = null
        assertContentEquals(before, target.noteFile(plansId).readBytes())
    }

    @Test
    fun anIdenticalNoteIsRewrittenOnlyWhenItsFileDoesNotOpen() = runBlocking<Unit> {
        val bytes = seed()
        restore(bytes)
        val plans = source.notes.readNote(plansId)!!
        val raw = target.noteFile(rawId).readBytes()

        for (damage in listOf<(File) -> Unit>(
            { it.writeBytes(it.readBytes().also { b -> b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte() }) },
            { target.files.writeText(it, "not a record") },
        )) {
            damage(target.noteFile(plansId))
            val report = restore(bytes)
            assertEquals(plans, target.notes.readNote(plansId, strict = true))
            assertContentEquals(raw, target.noteFile(rawId).readBytes())
            assertEquals(0, report.notesWritten)
            assertEquals(2, report.notesKept)
        }
        assertEquals(emptyList(), target.quarantined())

        // A file that opens stays, whatever its text.
        val entry = target.notes.readMetadata().getValue(plansId)
        target.files.writeText(
            target.noteFile(plansId),
            NotebookJson.encodeToString(NoteRecord(NoteRecord.VERSION, entry.folder, entry.name, entry.title, entry.date, entry.modifiedAtMillis, "cut-off edit")),
        )
        val other = target.noteFile(plansId).readBytes()
        restore(bytes)
        assertContentEquals(other, target.noteFile(plansId).readBytes())
    }

    @Test
    fun ofTwoRecordsUnderOneIdTheNewerWinsWhateverTheirOrder() = runBlocking<Unit> {
        val older = note(id(1), content = "old", modifiedAtMillis = 1000L)
        val newer = note(id(1), content = "new", modifiedAtMillis = 2000L)
        restore(archive(notes = listOf(newer, older)))
        assertEquals("new", target.notes.readNote(id(1)))
        val fresh = Side(Files.createTempDirectory("notebook-order").toFile())
        try {
            restore(archive(notes = listOf(older, newer)), fresh)
            assertEquals("new", fresh.notes.readNote(id(1)))
            assertEquals(1, fresh.notes.readMetadata().size)
        } finally {
            fresh.root.deleteRecursively()
        }
    }

    @Test
    fun aNewerLocalNoteWhoseFileIsGoneTakesTheArchivedContent() = runBlocking<Unit> {
        val bytes = seed()
        restore(bytes)
        target.notes.updateNote(plansId, "edited later", modifiedAtMillis = 1_700_000_001_000L)
        target.noteFile(plansId).delete()

        val report = restore(bytes)

        assertEquals(1, report.notesWritten)
        assertEquals(source.notes.readNote(plansId), target.notes.readNote(plansId))
    }

    @Test
    fun aRestoreBudgetsTheIndexInBytesAndLeavesItReadableWhenFull() = runBlocking<Unit> {
        val small = Side(Files.createTempDirectory("notebook-cap").toFile(), metaBytes = 16L * 1024)
        try {
            val wide = "\u8A18".repeat(RestorePlan.MAX_TITLE_CHARS)
            val notes = (1..100).map { note(id(it), name = "n$it.md", content = "c$it", title = wide) }

            val report = restore(archive(notes = notes), small)

            assertTrue(report.notesSkipped > 0, report.toString())
            assertEquals(100, report.notesWritten + report.notesSkipped)
            assertEquals(report.notesWritten, small.notes.readMetadata(strict = true).size)
            small.notes.reconcile()
            small.backup.create(password).close()
        } finally {
            small.root.deleteRecursively()
        }
    }

    @Test
    fun aRepairLeavesInPlaceWhatTheIndexHasNoRoomForAndTheBackupStillRuns() = runBlocking<Unit> {
        val small = Side(Files.createTempDirectory("notebook-repair").toFile(), metaBytes = 8L * 1024)
        try {
            val wide = "\u8A18".repeat(RestorePlan.MAX_TITLE_CHARS)
            for (i in 1..40) {
                val record = NoteRecord(NoteRecord.VERSION, "work", "n$i.md", wide, null, i.toLong(), "c$i")
                small.files.writeText(small.noteFile(id(i)), NotebookJson.encodeToString(NoteRecord.serializer(), record))
            }

            val result = small.notes.reconcile()

            assertTrue(result.leftInPlace > 0, result.toString())
            assertEquals(40, result.listed + result.leftInPlace)
            small.backup.create(password).use { staged -> assertEquals(result.leftInPlace, staged.created.notesUnlisted) }
        } finally {
            small.root.deleteRecursively()
        }
    }

    @Test
    fun aRestoreWritesLargeNotesInSmallerBatches() = runBlocking<Unit> {
        val notes = (1..10).map { note(id(it), name = "n$it.md", content = "x".repeat(RestorePlan.MAX_NOTE_CHARS - it)) }

        val report = restore(archive(notes = notes))

        assertEquals(10, report.notesWritten)
        val indexSeals = target.fake.sealed.count { it == NoteStore.META_FILE }
        assertEquals(2, indexSeals, "one batch per 4 Mi characters")
    }

    @Test
    fun publishCopiesTheArchiveAndChecksItsReadBackWithTheArchiveReader() = runBlocking<Unit> {
        seed()
        class MemoryDocs(var corrupt: Boolean = false) : BackupDocumentOps<String> {
            val docs = LinkedHashMap<String, ByteArray>()
            override fun describe(handle: String) = docs[handle]?.let { BackupDocumentInfo(handle, it.size.toLong()) }
            override fun openWrite(handle: String): java.io.OutputStream = object : ByteArrayOutputStream() {
                override fun close() { docs[handle] = toByteArray() }
            }
            override fun openRead(handle: String) = ByteArrayInputStream(docs.getValue(handle).let { if (corrupt && it.isNotEmpty()) it.copyOf(it.size - 1) else it })
            override fun delete(handle: String) = docs.remove(handle) != null
        }
        source.backup.create(password).use { staged ->
            val good = MemoryDocs().apply { docs["b.backup"] = ByteArray(0) }
            val published = source.backup.publish(good, "b.backup", staged, password)
            assertEquals(BackupPublishOutcome("b.backup", staged.staged.size), published.outcome)
            assertTrue(published.summary.healthy)
            assertEquals(2, inspect(good.docs.getValue("b.backup")).notes)

            val bad = MemoryDocs(corrupt = true).apply { docs["b.backup"] = ByteArray(0) }
            assertFailsWith<BackupPublishException> { source.backup.publish(bad, "b.backup", staged, password) }
            assertTrue(bad.docs.isEmpty())
        }
    }

    @Test
    fun aRestoreAddsFoldersOnlyUpToTheCap() = runBlocking<Unit> {
        val names = (1..RestorePlan.MAX_FOLDERS + 50).map { "f$it" }

        val report = restore(archive(folders = names))

        assertEquals(RestorePlan.MAX_FOLDERS, target.notes.readFolders().size)
        assertEquals(RestorePlan.MAX_FOLDERS - 1, report.foldersAdded)
        assertEquals(names.size - report.foldersAdded, report.foldersSkipped)
    }

    @Test
    fun foldersAreUnionedAndFolderTagsAreAddedOnlyForFoldersWithoutAny() = runBlocking<Unit> {
        target.notes.createFolder("home", "chores")
        target.notes.createFolder("work", "mine")

        val report = restore(archive(folders = listOf("work", "Bad Folder", "ideas", "ideas"), folderTags = mapOf("work" to listOf("theirs"), "ideas" to listOf("spark"))))

        assertEquals(listOf("unclassified", "home", "work", "ideas"), target.notes.readFolders())
        assertEquals(mapOf("home" to listOf("home", "chores"), "work" to listOf("work", "mine"), "ideas" to listOf("spark")), target.notes.readFolderTags())
        assertEquals(1, report.foldersAdded)
        assertEquals(1, report.foldersSkipped)
        assertEquals(1, report.folderTagsAdded)
    }

    @Test
    fun aQueuedCaptureAlreadyPresentKeepsItsEntryAndBlob() = runBlocking<Unit> {
        val bytes = seed()
        val local = byteArrayOf(1, 2, 3)
        target.queue.enqueue("fg_1", local, "local.m4a", 9L)

        val report = restore(bytes)

        assertEquals(listOf(QueuedCapture("fg_1", "local.m4a", 9L)), target.queue.readQueue())
        assertContentEquals(local, assertIs<BlobRead.Present>(target.queue.readBlob("fg_1")).bytes)
        assertEquals(0, report.capturesAdded)
        assertEquals(1, report.capturesKept)
    }

    @Test
    fun aBackupRestoredIntoItsOwnStoreKeepsWhatChangedSinceAndBringsBackWhatWasDeleted() = runBlocking<Unit> {
        val bytes = seed()
        val mine = source.reminders.update(1) { it.copy(message = "mine") }!!
        source.reminders.remove(2)
        source.events.markCancelled(ReminderEventKey(1, now - 5000L), now)

        val report = restore(bytes, source)

        val all = source.reminders.readAll(strict = true)
        assertEquals(mine, all.first { it.id == 1 })
        assertEquals("later once", all.first { it.id == 2 }.message)
        assertEquals(1, report.remindersAdded)
        assertEquals(1, report.remindersKept)
        assertEquals(now, source.events.readAll(strict = true).single().cancelledAt)
        assertEquals(1, report.eventsKept)
    }

    @Test
    fun anotherStoresRemindersAndHistoryTakeNewIdsSoNoneLandsOnAnIdThisStoreUsed() = runBlocking<Unit> {
        target.reminders.create { ReminderItem(it, future, "gone") }
        target.events.appendFire(ReminderEvent(1, now - 9000L, "gone", now - 8900L, completedAt = now - 8800L))
        target.reminders.remove(1)
        val mine = target.reminders.create { ReminderItem(it, future + 1, "mine") }
        target.events.appendFire(ReminderEvent(2, now - 7000L, "mine", now - 6900L))
        val history = target.events.readAll(strict = true)

        val report = restore(seed())

        val byMessage = target.reminders.readAll(strict = true).associateBy { it.message }
        assertEquals(mine, byMessage.getValue("mine"))
        assertEquals(3, byMessage.getValue("weekly").id)
        assertEquals(4, byMessage.getValue("later once").id)
        val events = target.events.readAll(strict = true)
        assertEquals(history, events.filter { it.reminderId <= 2 })
        assertEquals(listOf(ReminderEventKey(3, now - 5000L)), events.filter { it.reminderId > 2 }.map { it.key })
        assertEquals(2, report.remindersAdded)
        assertEquals(1, report.eventsAdded)
        assertEquals(5, target.reminders.create { ReminderItem(it, future, "next") }.id)
    }

    @Test
    fun anotherStoresArchiveRestoredAgainAddsNothing() = runBlocking<Unit> {
        val bytes = seed()
        restore(bytes)
        val reminders = target.reminders.readAll(strict = true)
        val events = target.events.readAll(strict = true)

        val again = restore(bytes)

        assertEquals(reminders, target.reminders.readAll(strict = true))
        assertEquals(events, target.events.readAll(strict = true))
        assertEquals(0, again.remindersAdded)
        assertEquals(2, again.remindersKept)
        assertEquals(1, again.eventsKept)
    }

    @Test
    fun theHistoryOfAReminderTheArchiveNoLongerHoldsKeepsOneIdAcrossRestores() = runBlocking<Unit> {
        target.reminders.create { ReminderItem(it, future, "here") }
        val bytes = archive(events = listOf(BackupReminderEvent(1, now - 5L, "old", now - 4L)))

        restore(bytes)
        restore(bytes)

        val events = target.events.readAll(strict = true)
        assertEquals(listOf(ReminderEventKey(2, now - 5L)), events.map { it.key })
    }

    @Test
    fun aBackupNamesItsStoreAndWhereAdoptedRemindersCameFrom() = runBlocking<Unit> {
        val fromSource = seed()
        restore(fromSource)
        target.reminders.create { ReminderItem(it, future, "own") }

        val records = ArchiveReader.open(ByteArrayInputStream(create(target)), password).let { reader -> generateSequence { reader.next() }.toList() }

        val meta = BackupRecords.decode(BackupMeta.serializer(), records.first { it.type == RecordType.META }.body)
        assertEquals(own(target), meta.storeId)
        val reminders = BackupRecords.decode(BackupRecords.REMINDERS, records.single { it.type == RecordType.REMINDERS }.body)
        val sourceStore = own(source)
        assertEquals(
            mapOf("weekly" to BackupOrigin(sourceStore, 1), "later once" to BackupOrigin(sourceStore, 2), "own" to null),
            reminders.associate { it.message to it.origin },
        )
        val events = BackupRecords.decode(BackupRecords.EVENTS, records.single { it.type == RecordType.EVENTS }.body)
        assertEquals(listOf(BackupOrigin(sourceStore, 1)), events.map { it.reminderOrigin })
    }

    @Test
    fun aRemindersOriginFollowsItThroughAThirdStore() = runBlocking<Unit> {
        val third = Side(Files.createTempDirectory("notebook-third").toFile())
        try {
            val fromSource = seed()
            restore(fromSource)
            restore(create(target), third)

            val report = restore(fromSource, third)

            assertEquals(0, report.remindersAdded)
            assertEquals(2, report.remindersKept)
            assertEquals(1, report.eventsKept)
            assertEquals(listOf("weekly", "later once"), third.reminders.readAll(strict = true).map { it.message })
        } finally {
            third.root.deleteRecursively()
        }
    }

    @Test
    fun aRestoreThatStoppedAfterTheAdoptionGivesTheSameIdsWhenRunAgain() = runBlocking<Unit> {
        val bytes = seed()
        target.fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME

        assertFailsWith<TransientCryptoException> { restore(bytes) }
        val adopted = target.reminders.readFile(strict = true).adopted
        assertEquals(setOf(1, 2), adopted.keys)

        target.fake.failSealWhenAadContains = null
        restore(bytes)

        assertEquals(adopted, target.reminders.readFile(strict = true).adopted)
        assertEquals(mapOf(1 to "weekly", 2 to "later once"), target.reminders.readAll(strict = true).associate { it.id to it.message })
        assertEquals(listOf(ReminderEventKey(1, now - 5000L)), target.events.readAll(strict = true).map { it.key })
    }

    @Test
    fun aBackupRestoredOnAnotherStoreAndBackAgainAddsNothing() = runBlocking<Unit> {
        restore(seed())
        val reminders = source.reminders.readAll(strict = true)
        val events = source.events.readAll(strict = true)

        val report = restore(create(target), source)

        assertEquals(0, report.remindersAdded)
        assertEquals(2, report.remindersKept)
        assertEquals(1, report.eventsKept)
        assertEquals(reminders, source.reminders.readAll(strict = true))
        assertEquals(events, source.events.readAll(strict = true))
        assertEquals(emptyMap(), source.reminders.readFile(strict = true).adopted)
    }

    @Test
    fun anItemTheRestoreRefusesTakesNoIdAndALeftOverAdoptionIsDropped() = runBlocking<Unit> {
        val report = restore(archive(
            reminders = listOf(
                BackupReminder(1, future, "ok"),
                BackupReminder(2, future, "bad rule", BackupRecurrence("never")),
                BackupReminder(3, future, "bad origin", origin = BackupOrigin("not a store", 3)),
            ),
        ))

        assertEquals(2, report.remindersSkipped)
        assertEquals(listOf(1 to "ok"), target.reminders.readAll(strict = true).map { it.id to it.message })
        assertEquals(setOf(1), target.reminders.readFile(strict = true).adopted.keys)
        assertEquals(2, target.reminders.readFile(strict = true).nextId)

        target.reminders.remove(1)
        restore(archive())
        assertEquals(emptyMap(), target.reminders.readFile(strict = true).adopted)
    }

    @Test
    fun aPendingSnoozeOfAReminderUnknownHereIsKeptAndArmed() = runBlocking<Unit> {
        val snoozed = BackupReminderEvent(9, now - 5L, "ghost", now - 4L, latestSnoozeFireAt = future)
        val known = BackupReminderEvent(1, now - 6L, "known", now - 5L, latestSnoozeFireAt = future + 5)

        restore(archive(reminders = listOf(BackupReminder(1, future + 9, "known")), events = listOf(snoozed, known), storeId = own()))

        val events = target.events.readAll(strict = true).associateBy { it.reminderId }
        assertEquals(future, events.getValue(9).latestSnoozeFireAt)
        assertEquals(future + 5, events.getValue(1).latestSnoozeFireAt)
        assertEquals(mapOf(ReminderEventKey(9, now - 5L) to future, ReminderEventKey(1, now - 6L) to future + 5), target.scheduler.snoozes)
        assertTrue(target.notifier.posted.isEmpty())
    }

    @Test
    fun aResolvedEventOfAReminderUnknownHereKeepsItsSnoozeAndAnOpenOnesPassedSnoozeIsPostedAtTheRestore() = runBlocking<Unit> {
        val passed = now - 600_000L
        val done = BackupReminderEvent(9, now - 7_200_000L, "done", now - 7_200_000L, latestSnoozeFireAt = passed, completedAt = now - 60_000L)
        val open = BackupReminderEvent(8, now - 7_200_000L, "open", now - 7_200_000L, latestSnoozeFireAt = passed)

        restore(archive(events = listOf(done, open), storeId = own()))

        val events = target.events.readAll(strict = true).associateBy { it.reminderId }
        assertEquals(done.toEvent(), events.getValue(9))
        assertEquals(open.toEvent()?.copy(snoozeDeliveredAt = passed), events.getValue(8))
        assertEquals(listOf(ReminderEventKey(8, now - 7_200_000L) to "open"), target.notifier.posted)
    }

    // Skips

    @Test
    fun notesTheStoreWouldNotSpellOrHoldAreCountedNotWritten() = runBlocking<Unit> {
        val report = restore(archive(notes = listOf(
            note("not-an-id"),
            note(id(1).uppercase().replace('0', 'A')),
            note(id(2), folder = "Work"),
            note(id(3), folder = "../x"),
            note(id(4), name = "../escape.md"),
            note(id(5), name = ".hidden.md"),
            note(id(6), content = "x".repeat(RestorePlan.MAX_NOTE_CHARS + 1)),
            note(id(7), modifiedAtMillis = Long.MAX_VALUE),
            note(id(8), modifiedAtMillis = 0L),
            note(id(9), name = "ok.md", content = "fine", title = "t".repeat(500)),
        )))

        assertEquals(9, report.notesSkipped)
        assertEquals(1, report.notesWritten)
        assertEquals(setOf(id(9)), target.notes.readMetadata().keys)
        assertEquals(RestorePlan.MAX_TITLE_CHARS, target.notes.readMetadata().getValue(id(9)).title!!.length)
        assertFalse(File(target.root, "escape.md").exists())
    }

    @Test
    fun capturesWithoutARecordingOrWithAnEntryTheQueueRefusesAreSkipped() = runBlocking<Unit> {
        val clip = byteArrayOf(2, 3, 4)
        val report = restore(archive(captures = listOf(
            BackupCapture("ok", "a.m4a", 1000L, attempts = -7) to clip,
            BackupCapture("ok", "b.m4a", 1000L) to clip,
            BackupCapture("ghost", "a.m4a", 1000L) to ByteArray(0),
            BackupCapture("bad/id", "a.m4a", 1000L) to clip,
            BackupCapture("badname", "a b.m4a", 1000L) to clip,
            BackupCapture("far", "a.m4a", Long.MAX_VALUE) to clip,
        )))

        assertEquals(listOf(QueuedCapture("ok", "a.m4a", 1000L, attempts = 0)), target.queue.readQueue())
        assertContentEquals(clip, assertIs<BlobRead.Present>(target.queue.readBlob("ok")).bytes)
        assertEquals(1, report.capturesAdded)
        assertEquals(1, report.capturesKept)
        assertEquals(4, report.capturesSkipped)
        restore(archive(captures = listOf(BackupCapture("more", "a.m4a", 1000L, attempts = 99) to clip)))
        assertEquals(MAX_QUEUE_ATTEMPTS, target.queue.readQueue().single { it.id == "more" }.attempts)
    }

    @Test
    fun aCapturesZoneIsRestoredOnlyWhenTheRuntimeKnowsIt() = runBlocking<Unit> {
        val clip = byteArrayOf(2, 3, 4)
        restore(archive(captures = listOf(
            BackupCapture("known", "a.wav", 1000L, zoneId = "Asia/Tokyo") to clip,
            BackupCapture("unknown", "b.wav", 1000L, zoneId = "Not/AZone") to clip,
        )))

        assertEquals(mapOf("known" to "Asia/Tokyo", "unknown" to null), target.queue.readQueue().associate { it.id to it.zoneId })
    }

    @Test
    fun capturesLandInBatchesAndARepeatedIdIsKept() = runBlocking<Unit> {
        val clip = byteArrayOf(1, 2, 3)
        val many = (1..10).map { BackupCapture("c$it", "r.m4a", 1000L) to clip } +
            (BackupCapture("c1", "other.m4a", 1000L) to clip) +
            (11..100).map { BackupCapture("c$it", "r.m4a", 1000L) to clip }

        val report = restore(archive(captures = many))

        assertEquals(100, report.capturesAdded)
        assertEquals(1, report.capturesKept)
        val queueSeals = target.fake.sealed.count { it == QueueStore.QUEUE_META_FILE }
        assertTrue(queueSeals in 2..4, "queue sealed $queueSeals times")
    }

    @Test
    fun remindersAndEventsTheStoresWouldRefuseAreSkippedAndCounted() = runBlocking<Unit> {
        val report = restore(archive(
            reminders = listOf(
                BackupReminder(1, future, "ok"),
                BackupReminder(2, future, "bad rule", BackupRecurrence("fortnightly")),
                BackupReminder(0, future, "bad id"),
                BackupReminder(3, 1L, "endless", BackupRecurrence("yearly", Int.MAX_VALUE, 1000L)),
            ),
            events = listOf(BackupReminderEvent(1, now - 5L, "ok", now - 4L), BackupReminderEvent(1, now - 7L, "bad", now - 6L, snoozeChainCount = -1)),
        ))

        assertEquals(listOf("ok"), target.reminders.readAll(strict = true).map { it.message })
        assertEquals(listOf("ok"), target.events.readAll(strict = true).map { it.message })
        assertEquals(3, report.remindersSkipped)
        assertEquals(1, report.eventsSkipped)
        assertEquals(setOf(1), target.scheduler.fires.keys)
    }

    @Test
    fun aReminderIdAtTheStoreCeilingIsSkippedSoTheDeviceCanStillCreateReminders() = runBlocking<Unit> {
        val report = restore(archive(reminders = listOf(BackupReminder(ReminderFile.MAX_REMINDER_ID, future, "top"), BackupReminder(MAX_RESTORED_REMINDER_ID, future, "high")), storeId = own()))

        assertEquals(1, report.remindersSkipped)
        assertEquals(1, report.remindersAdded)
        assertEquals(MAX_RESTORED_REMINDER_ID + 1, target.reminders.create { ReminderItem(it, future, "next") }.id)
    }

    @Test
    fun aCarriedOriginNamingThisStorePastTheRestoreCapIsSkippedSoTheDeviceCanStillCreateReminders() = runBlocking<Unit> {
        val here = own()
        val report = restore(
            archive(
                reminders = listOf(BackupReminder(5, future, "foreign"), BackupReminder(1, future, "carried", origin = BackupOrigin(here, ReminderFile.MAX_REMINDER_ID))),
                events = listOf(BackupReminderEvent(2, now - 5000L, "fired", now - 4000L, reminderOrigin = BackupOrigin(here, ReminderFile.MAX_REMINDER_ID - 1))),
            ),
        )

        assertEquals(1, report.remindersAdded)
        assertEquals(1, report.remindersSkipped)
        assertEquals(1, report.eventsSkipped)
        assertTrue(target.reminders.readAll(strict = true).all { it.id <= MAX_RESTORED_REMINDER_ID })
        assertTrue(target.reminders.create { ReminderItem(it, future, "next") }.id <= MAX_RESTORED_REMINDER_ID)
    }

    @Test
    fun aBatchTheReminderFileCannotHoldIsCutAtTheCapAndCounted() = runBlocking<Unit> {
        val small = Side(Files.createTempDirectory("notebook-small").toFile(), reminderBytes = 64L * 1024)
        try {
            val many = (1..100).map { BackupReminder(it, future, "m".repeat(MAX_REMINDER_MESSAGE_CHARS)) }
            val report = restore(archive(reminders = many), small)

            val stored = small.reminders.readAll(strict = true)
            assertTrue(stored.size in 10..16, "stored ${stored.size}")
            assertEquals(stored.size, report.remindersAdded)
            assertEquals(100 - stored.size, report.remindersSkipped)
            assertEquals(stored.map { it.id }.toSet(), small.scheduler.fires.keys)
        } finally {
            small.root.deleteRecursively()
        }
    }

    @Test
    fun capturesTheQueueFileCannotHoldAreSkippedAndTheQueueStaysReadable() = runBlocking<Unit> {
        val small = Side(Files.createTempDirectory("notebook-smallq").toFile(), queueMetaBytes = 8L * 1024)
        try {
            val clip = ByteArray(4) { 1 }
            val many = (1..200).map { BackupCapture("c${it.toString().padStart(3, '0')}${"x".repeat(100)}", "r.m4a", 1000L) to clip }
            val report = restore(archive(captures = many), small)

            val stored = small.queue.readQueue(strict = true)
            assertTrue(stored.size in 30..60, "stored ${stored.size}")
            assertEquals(stored.size, report.capturesAdded)
            assertEquals(200 - stored.size, report.capturesSkipped)
            assertEquals(stored.size, File(small.root, QueueStore.QUEUE_DIR).listFiles().orEmpty().size)
        } finally {
            small.root.deleteRecursively()
        }
    }

    // Archive problems

    @Test
    fun aWrongPasswordOrDamagedArchiveChangesNothing() = runBlocking<Unit> {
        val bytes = seed()
        val before = target.fingerprint()

        suspend fun problem(data: ByteArray, pw: CharArray = password) =
            assertFailsWith<InvalidBackupException> { target.backup.stage(ByteArrayInputStream(data)).use { target.backup.restore(it, target.backup.unlock(it, pw)) } }.problem
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(bytes, "correct horse battery?".toCharArray()))
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(bytes, "correct horse".toCharArray()))
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(bytes.copyOf(3)))
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(bytes.copyOf(bytes.size - 1)))
        assertEquals(BackupProblem.NEWER_FORMAT, problem(bytes.copyOf().also { it[5] = 2 }))

        assertEquals(before, target.fingerprint())
        assertTrue(target.scheduler.fires.isEmpty())
    }

    @Test
    fun damageLateInTheArchiveIsFoundBeforeAnythingIsWritten() = runBlocking<Unit> {
        val notes = (1..4).map { note(id(it), name = "n$it.md", content = "x".repeat(40_000) + it) }
        val bytes = archive(notes = notes, reminders = listOf(BackupReminder(1, future, "r")))
        assertTrue(bytes.size > 2 * ArchiveFormat.SEGMENT_BYTES)
        val damaged = bytes.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        val before = target.fingerprint()

        val e = assertFailsWith<InvalidBackupException> { restore(damaged) }

        assertEquals(BackupProblem.DAMAGED, e.problem)
        assertEquals(before, target.fingerprint())
        assertEquals(emptyMap(), target.notes.readMetadata())
    }

    @Test
    fun anArchiveRewrittenBetweenThePassesIsRefusedAtTheHeaderCheckBeforeTheMerge() = runBlocking<Unit> {
        val first = archive(notes = listOf(note(id(1))))
        val salt = first.copyOfRange(7, 7 + ArchiveFormat.SALT_LEN)
        val sameSalt = archive(notes = listOf(note(id(2))), random = Replaying(salt, ByteArray(ArchiveFormat.NONCE_PREFIX_LEN) { 9 }))
        val otherSalt = archive(notes = listOf(note(id(2))))
        val before = target.fingerprint()

        suspend fun swappedFor(second: ByteArray) = assertFailsWith<InvalidBackupException> {
            target.backup.stage(ByteArrayInputStream(first)).use { staged ->
                var swapped = false
                target.backup.restore(staged, target.backup.unlock(staged, password)) { stage, fraction ->
                    if (stage == BackupStage.CHECKING && fraction >= 1f && !swapped) {
                        swapped = true
                        // The first pass keeps reading the file it opened; the second opens the rewrite.
                        Files.move(staged.staged.file.toPath(), File(staged.staged.file.parentFile, "aside").toPath())
                        staged.staged.openWrite().use { it.write(second) }
                    }
                }
            }
        }.also { File(target.root, "${BackupStaging.DIR}/aside").delete() }

        val e = swappedFor(sameSalt)
        assertEquals(BackupProblem.DAMAGED, e.problem)
        assertTrue("changed between reads" in e.message!!, e.message)
        val f = swappedFor(otherSalt)
        assertEquals(BackupProblem.DAMAGED, f.problem)
        assertTrue("salt" in f.message!!, f.message)
        assertEquals(before, target.fingerprint())
    }

    /** Hands out [chunks] in order, one per call. */
    private class Replaying(vararg chunks: ByteArray) : java.security.SecureRandom() {
        private val queue = ArrayDeque(chunks.toList())

        override fun nextBytes(bytes: ByteArray) {
            val next = queue.removeFirst()
            check(next.size == bytes.size) { "asked for ${bytes.size} bytes, have ${next.size}" }
            next.copyInto(bytes)
        }
    }

    @Test
    fun aStagingFileSwappedInFromAnotherRunDoesNotOpen() = runBlocking<Unit> {
        val first = archive(notes = listOf(note(id(1))))
        val before = target.fingerprint()

        assertFailsWith<java.io.IOException> {
            target.backup.stage(ByteArrayInputStream(first)).use { staged ->
                target.backup.stage(ByteArrayInputStream(first)).use { other ->
                    Files.copy(other.staged.file.toPath(), staged.staged.file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                target.backup.restore(staged, target.backup.unlock(staged, password))
            }
        }

        assertEquals(before, target.fingerprint())
    }

    @Test
    fun anArchiveWhoseStructureNoWriterProducesIsDamage() = runBlocking<Unit> {
        suspend fun problem(data: ByteArray) = assertFailsWith<InvalidBackupException> { inspect(data) }.problem
        val meta = BackupRecords.encode(BackupMeta.serializer(), BackupMeta(now, ARCHIVE_STORE))
        assertEquals(BackupProblem.DAMAGED, problem(archive(meta = false, notes = listOf(note(id(1))))), "no META")
        assertEquals(BackupProblem.DAMAGED, problem(archive(meta = false)), "empty")
        assertEquals(BackupProblem.DAMAGED, problem(archive(raw = listOf(RecordType.META to meta))), "META twice")
        assertEquals(BackupProblem.DAMAGED, problem(archive(folders = listOf("a"), raw = listOf(RecordType.FOLDERS to BackupRecords.encode(BackupRecords.FOLDERS, listOf("b"))))), "FOLDERS twice")
        assertEquals(BackupProblem.DAMAGED, problem(archive(raw = listOf(RecordType.NOTE to "not json".encodeToByteArray()))), "NOTE not JSON")
        assertEquals(BackupProblem.DAMAGED, problem(archive(raw = listOf(RecordType.CAPTURE to byteArrayOf(0, 0, 1, 0, 1)))), "capture cut short")
    }

    @Test
    fun aShortPasswordIsRefusedAndLeavesNoStagingFile() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { source.backup.create("short".toCharArray()) }
        assertEquals(emptyList(), source.stagingFiles())
    }

    // What a backup reads

    @Test
    fun aNoteThatCannotBeReadThisTimeFailsTheBackupAndLeavesNoStagingFile() = runBlocking<Unit> {
        seed()
        source.fake.failOpenWhenAadContains = "notes/$plansId"

        assertFailsWith<TransientCryptoException> { create() }
        assertEquals(emptyList(), source.stagingFiles())
    }

    @Test
    fun aBlobThatCannotBeReadThisTimeFailsTheBackup() = runBlocking<Unit> {
        seed()
        source.fake.failOpenWhenAadContains = "queue/fg_1"

        assertFailsWith<TransientCryptoException> { create() }
    }

    @Test
    fun anIndexedNoteWithoutAFileOrACaptureWithoutABlobIsLeftOutAndCounted() = runBlocking<Unit> {
        seed()
        source.noteFile(rawId).delete()
        source.queue.enqueue("fg_2", audio, "second.m4a")
        val corrupt = File(source.root, "${QueueStore.QUEUE_DIR}/fg_2${QueueStore.BLOB_EXTENSION}")
        corrupt.writeBytes(corrupt.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })

        source.backup.create(password).use { staged ->
            assertEquals(1, staged.created.notesMissing)
            assertEquals(1, staged.created.capturesMissing)
            assertEquals(BackupCounts(notes = 1, folders = 2, folderTags = 1, captures = 1, reminders = 2, events = 1), staged.created.counts)
            assertEquals(emptyList(), source.quarantined())
            val report = restore(staged.bytes())
            assertEquals(1, report.notesWritten)
            assertEquals(1, report.capturesAdded)
        }
    }

    @Test
    fun aNoteThatOpensButDoesNotParseIsLeftOutAndCounted() = runBlocking<Unit> {
        seed()
        source.files.writeText(source.noteFile(rawId), "not a record")

        source.backup.create(password).use { staged ->
            assertEquals(1, staged.created.notesMissing)
            assertEquals(1, staged.created.counts.notes)
        }
    }

    @Test
    fun aBackupRunsTheRepairFirstSoALostIndexStillBacksUpEveryNote() = runBlocking<Unit> {
        seed()
        File(source.root, NoteStore.META_FILE).delete()
        source.notes.forgetCachedIndexForTest()

        source.backup.create(password).use { staged ->
            assertEquals(2, staged.created.counts.notes)
            assertEquals(0, staged.created.notesMissing)
            assertEquals(2, inspect(staged.bytes()).notes)
        }
    }

    @Test
    fun aNoteFileTheRepairCannotPlaceIsCountedAsUnlisted() = runBlocking<Unit> {
        seed()
        source.unlistedFile(id(99), "not a record")

        source.backup.create(password).use { staged ->
            assertEquals(1, staged.created.notesUnlisted)
            assertEquals(2, staged.created.counts.notes)
        }
    }

    @Test
    fun aNoteDeletedWhileTheBackupRunsIsNotAGap() = runBlocking<Unit> {
        seed()
        val order = source.notes.readMetadata().keys.toList()
        var deleted = false

        source.backup.create(password) { stage, _ ->
            if (stage == BackupStage.NOTES && !deleted) {
                deleted = true
                runBlocking { source.notes.deleteNote(order[1]) }
            }
        }.use { staged ->
            assertEquals(1, staged.created.counts.notes)
            assertEquals(0, staged.created.notesMissing)
            staged.source.open().use { source.backup.verify(it, password, staged) }
        }
    }

    // Verify

    @Test
    fun aReadBackOfTheWrittenArchiveVerifies() = runBlocking<Unit> {
        seed()
        source.backup.create(password).use { staged ->
            val summary = staged.source.open().use { source.backup.verify(it, password, staged) }
            assertTrue(summary.healthy)
            assertEquals(2, summary.notes)
        }
    }

    @Test
    fun aReadBackThatIsNotTheArchiveWrittenFailsVerification() = runBlocking<Unit> {
        seed()
        source.backup.create(password).use { staged ->
            source.notes.createNote("work", "later.md", "written after the backup")
            val other = create()
            assertFailsWith<BackupVerificationException> { source.backup.verify(ByteArrayInputStream(other), password, staged) }
            assertFailsWith<InvalidBackupException> { staged.source.open().use { source.backup.verify(it, "another password".toCharArray(), staged) } }
            val cut = staged.bytes().let { it.copyOf(it.size - 1) }
            assertFailsWith<InvalidBackupException> { source.backup.verify(ByteArrayInputStream(cut), password, staged) }
            val wrongCounts = StagedBackup(staged.staged, staged.created.let { CreatedBackup(it.tally, it.counts.copy(notes = it.counts.notes + 1), it.repair, 0, 0, 0, 0) })
            assertFailsWith<BackupVerificationException> { staged.source.open().use { source.backup.verify(it, password, wrongCounts) } }
        }
    }

    @Test
    fun twoBackupsOfTheSameStoresDifferInEveryByteAfterTheMagic() = runBlocking<Unit> {
        seed()
        val a = create()
        val b = create()
        assertNotEquals(a.copyOfRange(7, ArchiveFormat.HEADER_LEN).toList(), b.copyOfRange(7, ArchiveFormat.HEADER_LEN).toList())
    }

    // Store failures during a restore

    @Test
    fun anInterruptedRestoreLeavesTheStoresConsistentAndARerunFinishesIt() = runBlocking<Unit> {
        val bytes = archive(
            notes = listOf(note(id(1), name = "a.md", content = "a"), note(id(2), name = "b.md", content = "b"), note(id(3), name = "c.md", content = "c")),
            reminders = listOf(BackupReminder(1, future, "r")),
        )
        target.fake.failSealWhenAadContains = NoteStore.META_FILE

        assertFailsWith<TransientCryptoException> { restore(bytes) }

        assertEquals(emptyMap(), target.notes.readMetadata(strict = true))
        assertEquals(3, target.noteFiles().size)
        assertEquals(emptyList(), target.reminders.readAll(strict = true))
        assertEquals(emptyList(), target.quarantined())

        target.fake.failSealWhenAadContains = null
        val report = restore(bytes)

        assertEquals(0, report.notesWritten)
        assertEquals(3, report.notesKept)
        assertEquals(ReconcileResult(), target.notes.reconcile())
        assertEquals(setOf(id(1), id(2), id(3)), target.notes.readMetadata().keys)
        assertEquals("a", target.notes.readNote(id(1)))
        assertEquals("r", target.reminders.readAll(strict = true).single().message)
        assertEquals(setOf(1), target.scheduler.fires.keys)
    }

    @Test
    fun aFailureAfterTheEventsLandedStillReArmsWhatIsStoredAndPostsNothingForAReminderNeverAdded() = runBlocking<Unit> {
        val bytes = archive(
            reminders = listOf(BackupReminder(1, now - 1000L, "due"), BackupReminder(2, future, "r")),
            events = listOf(BackupReminderEvent(1, now - 1000L, "due", now - 900L, completedAt = now - 800L)),
            storeId = own(),
        )
        target.fake.failSealWhenAadContains = ReminderStore.FILE_NAME

        assertFailsWith<TransientCryptoException> { restore(bytes) }

        assertEquals(1, target.events.readAll(strict = true).size)
        assertEquals(emptyList(), target.reminders.readAll(strict = true))
        assertTrue(target.notifier.posted.isEmpty())
        assertTrue(target.scheduler.fires.isEmpty())

        target.fake.failSealWhenAadContains = null
        val report = restore(bytes)
        assertEquals(1, report.eventsKept)
        assertEquals(setOf(2), target.scheduler.fires.keys)
        assertTrue(target.notifier.posted.isEmpty())
    }

    @Test
    fun aRestoreWhoseStoresCannotBeReadAgainReportsTheMergesOwnCounts() = runBlocking<Unit> {
        val bytes = seed()

        val report = target.backup.stage(ByteArrayInputStream(bytes)).use { staged ->
            target.backup.restore(staged, target.backup.unlock(staged, password)) { stage, _ ->
                if (stage == BackupStage.REARMING) target.fake.failOpenWhenAadContains = ReminderStore.FILE_NAME
            }
        }

        target.fake.failOpenWhenAadContains = null
        assertEquals(2, report.remindersAdded)
        assertEquals(1, report.eventsAdded)
        assertEquals(2, target.reminders.readAll(strict = true).size)
    }

    @Test
    fun aRestoreWritesTheIndexOncePerChunkNotOncePerNote() = runBlocking<Unit> {
        val notes = (1..150).map { note(id(it), name = "n$it.md", content = "note $it") }

        restore(archive(notes = notes))

        val indexSeals = target.fake.sealed.count { it == NoteStore.META_FILE }
        assertTrue(indexSeals in 3..4, "index sealed $indexSeals times")
        assertEquals(150, target.notes.readMetadata().size)
    }

    @Test
    fun aStagedArchiveIsDeletedWhenClosed() = runBlocking<Unit> {
        val staged = target.backup.stage(ByteArrayInputStream(archive()))
        assertEquals(listOf(staged.staged.file), target.stagingFiles())
        staged.close()
        assertEquals(emptyList(), target.stagingFiles())
    }

    // Findings of the pre-merge review of this slice

    @Test
    fun aCaptureTheDrainPublishesAsANoteWhileTheBackupRunsIsInTheArchive() = runBlocking<Unit> {
        source.queue.enqueue("c1", audio, "one.m4a")
        source.queue.enqueue("c2", audio, "two.m4a")
        var published: String? = null

        source.backup.create(password) { stage, _ ->
            if (stage == BackupStage.CAPTURES && published == null) runBlocking {
                published = source.notes.createNote("work", "from-c2.md", "what c2 said")
                source.queue.remove("c2")
            }
        }.use { staged ->
            assertEquals(0, staged.created.capturesMissing)
            val report = restore(staged.bytes())
            assertEquals(1, report.capturesAdded)
            assertEquals("what c2 said", target.notes.readNote(published!!))
        }
    }

    @Test
    fun aCaptureTheDrainPublishesAsAReminderWhileTheBackupRunsIsInTheArchive() = runBlocking<Unit> {
        source.queue.enqueue("c1", audio, "one.m4a")
        source.queue.enqueue("c2", audio, "two.m4a")
        var done = false

        source.backup.create(password) { stage, _ ->
            if (stage == BackupStage.CAPTURES && !done) runBlocking {
                done = true
                source.reminders.create { ReminderItem(it, future, "from c2") }
                source.queue.remove("c2")
            }
        }.use { staged ->
            assertEquals(0, staged.created.capturesMissing)
            restore(staged.bytes())
            assertEquals(listOf("from c2"), target.reminders.readAll(strict = true).map { it.message })
        }
    }

    @Test
    fun aCancelledBackupLeavesNoStagingFile() = runBlocking<Unit> {
        seed()
        lateinit var job: kotlinx.coroutines.Job
        // Lazy, so [job] is assigned before the progress callback can cancel it.
        job = launch(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            source.backup.create(password) { stage, _ -> if (stage == BackupStage.REMINDERS) job.cancel() }.close()
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(emptyList(), source.stagingFiles())
    }

    @Test
    fun aCancelledStageStopsCopyingAndLeavesNoStagingFile() = runBlocking<Unit> {
        val bytes = seed()
        lateinit var job: kotlinx.coroutines.Job
        var read = 0
        val input = object : java.io.InputStream() {
            val inner = ByteArrayInputStream(bytes)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                inner.read(b, off, minOf(len, 64)).also { if (it > 0) read += it; job.cancel() }
        }
        // Lazy, so [job] is assigned before the first read can cancel it.
        job = launch(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) { target.backup.stage(input).close() }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(read < bytes.size, "read $read of ${bytes.size} bytes")
        assertEquals(emptyList(), target.stagingFiles())
    }

    @Test
    fun aHeaderThisAppDoesNotReadIsRefusedBeforeStaging() = runBlocking<Unit> {
        var read = 0L
        fun counting(data: ByteArray) = object : java.io.InputStream() {
            val inner = ByteArrayInputStream(data)
            override fun read(): Int = inner.read().also { if (it >= 0) read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) read += it }
        }
        val zip = "PK\u0003\u0004".encodeToByteArray() + ByteArray(1 shl 20)

        val e = assertFailsWith<InvalidBackupException> { target.backup.stage(counting(zip)) }

        assertEquals(BackupProblem.NOT_A_BACKUP, e.problem)
        assertTrue(read <= ArchiveFormat.HEADER_LEN, "read $read bytes")
        assertEquals(emptyList(), target.stagingFiles())
        val newer = seed().also { it[5] = 2 }
        assertEquals(BackupProblem.NEWER_FORMAT, assertFailsWith<InvalidBackupException> { target.backup.stage(ByteArrayInputStream(newer)) }.problem)
        assertEquals(emptyList(), target.stagingFiles())
    }

    @Test
    fun aStagingFileOpensOnlyThroughTheObjectThatWroteIt() = runBlocking<Unit> {
        seed()
        source.backup.create(password).use { staged ->
            val raw = staged.staged.file.readBytes()
            assertFalse(raw.copyOf(4).contentEquals(ArchiveFormat.MAGIC))
            assertEquals(BackupProblem.NOT_A_BACKUP, assertFailsWith<InvalidBackupException> { ArchiveReader.open(ByteArrayInputStream(raw), password) }.problem)
            assertEquals(staged.staged.size, staged.bytes().size.toLong())
            staged.staged.file.writeBytes(raw.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() })
            assertFailsWith<java.io.IOException> { staged.source.open().use { it.readBytes() } }
        }
        assertEquals(emptyList(), source.stagingFiles())
    }

    @Test
    fun stagingRefusesToFillTheStorage() = runBlocking<Unit> {
        val full = Side(Files.createTempDirectory("notebook-full").toFile(), minFreeBytes = Long.MAX_VALUE)
        try {
            val e = assertFailsWith<java.io.IOException> { full.backup.create(password) }
            assertTrue("free space" in e.message!!, e.message)
            assertEquals(emptyList(), full.stagingFiles())
        } finally {
            full.root.deleteRecursively()
        }
    }

    @Test
    fun stagingStopsWhenTheFreeSpaceRunsOutPartWayThroughAWrite() = runBlocking<Unit> {
        var readings = 0
        val tight = Side(Files.createTempDirectory("notebook-tight").toFile(), usableSpace = { if (readings++ == 0) Long.MAX_VALUE else 0L })
        try {
            assertFailsWith<StagingSpaceException> { tight.backup.stage(ByteArrayInputStream(archive() + ByteArray(2 shl 20))) }
            assertEquals(emptyList(), tight.stagingFiles())
        } finally {
            tight.root.deleteRecursively()
        }
    }

    @Test
    fun theArchiveLimitsSitAboveWhatTheStoresCanHold() {
        val minimalEntry = NotebookJson.encodeToString(NoteMetadata.serializer(), NoteMetadata("a", "a", "0".repeat(64), 1L)).length + 32 + 4
        assertTrue(RestorePlan.MAX_ARCHIVE_NOTES.toLong() * minimalEntry >= NoteStore.MAX_META_BYTES)
        val minimalCapture = NotebookJson.encodeToString(QueuedCapture.serializer(), QueuedCapture("a", "a", 1L)).length + 1
        assertTrue(RestorePlan.MAX_ARCHIVE_CAPTURES.toLong() * minimalCapture >= QueueStore.MAX_QUEUE_META_BYTES)
    }

    @Test
    fun aNoteTooLargeForARestoreIsLeftOutAndCounted() = runBlocking<Unit> {
        seed()
        source.notes.createNote("work", "huge.md", "x".repeat(RestorePlan.MAX_NOTE_CHARS + 1))

        source.backup.create(password).use { staged ->
            assertEquals(1, staged.created.notesTooLarge)
            assertEquals(2, staged.created.counts.notes)
            assertTrue(inspect(staged.bytes()).healthy)
        }
    }

    @Test
    fun aNoteFileListedWhileTheBackupRunsIsNotCountedAsUnlisted() = runBlocking<Unit> {
        seed()
        val late = id(77)
        source.queue.enqueue("c1", audio, "one.m4a")
        var placed = false
        source.backup.create(password) { stage, _ ->
            if (stage == BackupStage.CAPTURES && !placed) {
                placed = true
                source.files.writeText(source.noteFile(late), NotebookJson.encodeToString(NoteRecord.serializer(), NoteRecord(NoteRecord.VERSION, "work", "late.md", null, null, 1L, "late")))
            }
            if (stage == BackupStage.NOTES && placed) runBlocking { source.notes.reconcile() }
        }.use { staged -> assertEquals(0, staged.created.notesUnlisted) }
    }

    @Test
    fun whatTheRepairSetAsideIsReported() = runBlocking<Unit> {
        seed()
        source.unlistedFile(id(55), "x")
        val file = source.noteFile(id(55))
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })

        source.backup.create(password).use { staged -> assertEquals(1, staged.created.repair.setAside) }
    }

    @Test
    fun aReadBackWithTheSameCountsButOtherRecordsFailsVerification() = runBlocking<Unit> {
        seed()
        source.backup.create(password).use { staged ->
            source.notes.updateNote(rawId, "changed after the backup")
            val other = create()
            assertFailsWith<BackupVerificationException> { source.backup.verify(ByteArrayInputStream(other), password, staged) }
        }
    }

    @Test
    fun theStepBeforeTheMergeRunsOnceTheArchiveHasBeenReadThroughAndNotForADamagedOne() = runBlocking<Unit> {
        source.notes.createNote("work", "a.md", "alpha")
        val bytes = create()
        var notesWhenRun = -1
        target.backup.stage(ByteArrayInputStream(bytes)).use { staged ->
            target.backup.restore(staged, target.backup.unlock(staged, password), { notesWhenRun = target.notes.readMetadata(strict = true).size })
        }
        assertEquals(0, notesWhenRun)
        assertEquals(1, target.notes.readMetadata(strict = true).size)

        val damaged = bytes.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
        var ran = false
        target.backup.stage(ByteArrayInputStream(damaged)).use { staged ->
            val key = target.backup.unlock(staged, password)
            assertFailsWith<InvalidBackupException> { target.backup.restore(staged, key, { ran = true }) }
        }
        assertFalse(ran)
    }

    @Test
    fun remindersNumberedFromAStoresRandomFirstIdRestore() = runBlocking<Unit> {
        val high = ReminderStore.FIRST_ID_FROM + ReminderStore.FIRST_ID_SPAN - 1
        restore(archive(reminders = listOf(BackupReminder(high, future, "own")), storeId = own()))
        assertEquals(listOf(high), target.reminders.readAll(strict = true).map { it.id })
        restore(archive(reminders = listOf(BackupReminder(high, future, "foreign"))))
        assertEquals(2, target.reminders.readAll(strict = true).size)
    }

    @Test
    fun aRestoreDropsTheAlarmsAndNotificationsUnderArchiveIdsItGaveNewIds() = runBlocking<Unit> {
        val key = ReminderEventKey(50, now - 1_000L)
        target.scheduler.fires[50] = ReminderItem(50, future, "old alarm")
        target.scheduler.snoozes[key] = future
        target.notifier.live += key
        val fired = BackupReminderEvent(50, now - 1_000L, "later", now - 1_000L, latestSnoozeFireAt = future)

        restore(archive(reminders = listOf(BackupReminder(50, future, "later")), events = listOf(fired)))

        val id = target.reminders.readAll(strict = true).single().id
        assertTrue(id != 50)
        assertEquals(setOf(id), target.scheduler.fires.keys)
        assertTrue(key !in target.scheduler.snoozes)
        assertTrue(key !in target.notifier.live)
    }

    @Test
    fun aRestoreSetsTheSnoozeOfAFiredOneShotAgainUnderItsNewIdAndDropsItsOldNotification() = runBlocking<Unit> {
        val old = ReminderEventKey(50, now - 1_000L)
        target.scheduler.snoozes[old] = future
        target.notifier.live += old
        val fired = BackupReminderEvent(50, now - 1_000L, "later", now - 1_000L, latestSnoozeFireAt = future + 5)

        restore(archive(events = listOf(fired)))

        val restored = target.events.readAll(strict = true).single()
        assertTrue(restored.reminderId != 50)
        assertEquals(mapOf(restored.key to future + 5), target.scheduler.snoozes)
        assertTrue(old !in target.notifier.live)
    }

    @Test
    fun aRestoreLeavesASnoozePickedAfterTheBackupSetAndItsReFireRings() = runBlocking<Unit> {
        val key = ReminderEventKey(50, now - 1_000L)
        target.scheduler.snoozes[key] = future

        restore(archive(events = listOf(BackupReminderEvent(50, now - 1_000L, "later", now - 1_000L))))

        assertEquals(mapOf(key to future), target.scheduler.snoozes)
        target.alarms.snoozedFire(key, "later")
        assertEquals(listOf(key to "later"), target.notifier.posted)
    }

    @Test
    fun aRestoreLeavesASnoozePickedAfterTheBackupSetWhenTheBackupHoldsAnEarlierOneThatPassed() = runBlocking<Unit> {
        val key = ReminderEventKey(50, now - 7_200_000L)
        target.scheduler.snoozes[key] = future
        val earlier = BackupReminderEvent(50, now - 7_200_000L, "later", now - 7_200_000L, latestSnoozeFireAt = now - 600_000L, snoozeDeliveredAt = now - 600_000L)

        restore(archive(events = listOf(earlier)))

        assertEquals(mapOf(key to future), target.scheduler.snoozes)
        target.alarms.snoozedFire(key, "later")
        assertEquals(listOf(key to "later"), target.notifier.posted)
    }

    @Test
    fun aSnoozePickedAfterABackupOfThisStoreRingsThoughTheRestoredOccurrencesDayIsOver() = runBlocking<Unit> {
        val key = ReminderEventKey(50, now - 86_400_000L)
        target.scheduler.snoozes[key] = future

        restore(archive(events = listOf(BackupReminderEvent(50, now - 86_400_000L, "later", now - 86_400_000L)), storeId = own()))

        assertEquals(listOf(key), target.events.readAll(strict = true).map { it.key })
        target.alarms.snoozedFire(key, "later")
        assertEquals(listOf(key to "later"), target.notifier.posted)
    }

    @Test
    fun aSnoozeOfAnOccurrenceThatCameAfterTheBackupRingsAfterTheRestore() = runBlocking<Unit> {
        val key = ReminderEventKey(50, now - 1_000L)
        target.scheduler.snoozes[key] = future

        restore(archive(reminders = listOf(BackupReminder(50, future, "later"))))

        assertEquals(mapOf(key to future), target.scheduler.snoozes)
        target.alarms.snoozedFire(key, "sooner")
        assertEquals(listOf(key to "sooner"), target.notifier.posted)
    }

    @Test
    fun aRestoreDropsANotificationThatCameAfterTheBackupUnderAnIdItGaveANewId() = runBlocking<Unit> {
        val after = ReminderEventKey(50, now - 500L)
        target.notifier.live += after

        restore(archive(reminders = listOf(BackupReminder(50, future, "later"))))

        assertTrue(target.notifier.live.none { it.reminderId == 50 })
    }

    @Test
    fun theStepBeforeTheMergeComesBeforeTheStoresRepair() = runBlocking<Unit> {
        source.notes.createNote("work", "a.md", "alpha")
        val bytes = create()
        target.notes.createNote("home", "b.md", "beta")
        target.notes.forgetCachedIndexForTest()
        target.fake.failOpenWithKeyUnavailable = true
        target.backup.stage(ByteArrayInputStream(bytes)).use { staged ->
            target.backup.restore(staged, target.backup.unlock(staged, password), { target.fake.failOpenWithKeyUnavailable = false })
        }
        assertEquals(2, target.notes.readMetadata(strict = true).size)
    }

    @Test
    fun aRestoreLeavesTheAlarmOfAnIdAReminderHereHolds() = runBlocking<Unit> {
        val here = target.reminders.create { ReminderItem(it, future, "here") }.id
        target.scheduler.cancelledFires.clear()
        target.notifier.live += ReminderEventKey(here, now - 500L)
        restore(archive(reminders = listOf(BackupReminder(here, future + 5, "foreign"))))
        assertEquals(2, target.reminders.readAll(strict = true).size)
        assertTrue(here !in target.scheduler.cancelledFires)
        assertTrue(ReminderEventKey(here, now - 500L) in target.notifier.live)
    }

    @Test
    fun aRandomlyNumberedStoresOwnBackupBringsItsRemindersBack() = runBlocking<Unit> {
        val root = Files.createTempDirectory("notebook-random").toFile()
        try {
            val side = Side(root, firstId = { ReminderStore.FIRST_ID_FROM + ReminderStore.FIRST_ID_SPAN - 1 })
            val id = side.reminders.create { ReminderItem(it, future, "kept") }.id
            val bytes = create(side)
            side.reminders.remove(id)
            restore(bytes, side)
            assertEquals(listOf(id to "kept"), side.reminders.readAll(strict = true).map { it.id to it.message })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aRestoredCaptureKeepsItsWatchMarkAndTakesItsLengthFromItsAudio() = runBlocking<Unit> {
        val wav = RecordingBuffer().apply { append(ShortArray(32_000), 32_000) }.toWav()
        source.queue.enqueue(QueuedCapture("w_1", "recording_2.wav", 1_700_000_000_250L, durationMillis = 999_999, fromWatch = true), wav)
        restore(create())
        val restored = target.queue.readQueue().single()
        assertTrue(restored.fromWatch)
        assertEquals(2_000L, restored.durationMillis)
    }

    @Test
    fun aRestoredFailedCaptureKeepsItsReasonAndAnUnknownCodeReadsAsUnknown() = runBlocking<Unit> {
        source.queue.enqueue(QueuedCapture("f_1", "recording_1.m4a", 1L, MAX_QUEUE_ATTEMPTS, true, failure = "no_speech"), audio)
        restore(create())
        assertEquals("no_speech", target.queue.readQueue().single().failure)

        val later = BackupCapture("f_2", "recording_2.m4a", 2L, MAX_QUEUE_ATTEMPTS, true, failure = "a_code_from_a_later_build")
        restore(archive(captures = listOf(later to audio)))
        assertEquals("unknown", target.queue.readQueue().single { it.id == "f_2" }.failure)
    }
}

/** The store a hand-built archive names in its META. */
private const val ARCHIVE_STORE = "0123456789abcdef0123456789abcdef"
