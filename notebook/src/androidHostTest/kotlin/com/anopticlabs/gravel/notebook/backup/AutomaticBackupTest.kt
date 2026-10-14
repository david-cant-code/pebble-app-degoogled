package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.TransientCryptoException
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [NotebookBackup.createAutomatic], [NotebookBackup.check] and [NotebookBackup.fingerprint] over a throwaway root. */
class AutomaticBackupTest {

    private val now = 1_700_000_000_000L
    private val key = "k".repeat(32) + "0123456789abcdef0123456789abcdef"
    private val root: File = Files.createTempDirectory("notebook-auto").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val notes = NoteStore(root, crypto) { now }
    private val queue = QueueStore(root, crypto) { now }
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val refresh = ReminderRefreshSignal()
    private val alarms = ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), refresh, { now }, { TimeZone.UTC })
    private val backup = NotebookBackup(notes, queue, reminders, events, alarms, refresh, BackupStaging(root), clock = { now })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun seed() {
        notes.createNote("work", "a.md", "alpha", title = "A", modifiedAtMillis = 10L)
        queue.enqueue(QueuedCapture("fg_1", "recording_1.m4a", now, attempts = 0, flagged = false, zoneId = null), ByteArray(100) { 1 })
        val r = reminders.create { ReminderItem(it, now + 60_000L, "call") }
        events.appendFire(ReminderEvent(r.id, now - 5_000L, "call", now - 4_000L))
    }

    @Test
    fun anAutomaticBackupHoldsNotesAndRemindersButNoRecordingsAndOpensOnlyWithItsKey() = runBlocking<Unit> {
        seed()
        val staged = backup.createAutomatic(key.toCharArray())
        staged.use {
            assertEquals(0, it.created.tally.count(RecordType.CAPTURE))
            assertEquals(0, it.created.counts.captures)
            assertEquals(1, it.created.counts.notes)
            assertEquals(1, it.created.counts.reminders)
            assertEquals(1, it.created.counts.events)
            val bytes = it.source.open().use { s -> s.readBytes() }
            assertEquals(ArchiveFormat.KDF_RECOVERY_KEY, ArchiveReader.kdfOf(bytes.copyOf(ArchiveFormat.HEADER_LEN)))
            backup.verify(ByteArrayInputStream(bytes), key.toCharArray(), it)
            val checked = backup.check(ByteArrayInputStream(bytes), RecoveryKey.display(key.toCharArray()).toCharArray())
            assertEquals(it.created.tally, checked.tally)
            assertEquals(it.created.counts, checked.counts)
            val wrong = assertFailsWith<InvalidBackupException> { backup.check(ByteArrayInputStream(bytes), key.reversed().toCharArray()) }
            assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, wrong.problem)
            assertFailsWith<InvalidBackupException> { backup.check(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), key.toCharArray()) }
        }
        // A manual backup still carries the recording.
        backup.create("correct horse battery".toCharArray()).use { assertEquals(1, it.created.counts.captures) }
    }

    @Test
    fun anAutomaticBackupRestoresThroughTheManualPathWithTheKey() = runBlocking<Unit> {
        seed()
        val bytes = backup.createAutomatic(key.toCharArray()).use { it.source.open().use { s -> s.readBytes() } }
        val other = Files.createTempDirectory("notebook-auto-dst").toFile()
        try {
            val c2 = FakeCryptographer(softwareCrypto())
            val n2 = NoteStore(other, c2) { now }
            val r2 = ReminderStore(other, c2)
            val e2 = ReminderEventStore(other, c2)
            val refresh2 = ReminderRefreshSignal()
            val b2 = NotebookBackup(
                n2, QueueStore(other, c2) { now }, r2, e2,
                ReminderAlarms(r2, e2, FakeScheduler(), FakeNotifier(), FakeSettings(), refresh2, { now }, { TimeZone.UTC }),
                refresh2, BackupStaging(other), clock = { now },
            )
            b2.stage(ByteArrayInputStream(bytes)).use { archive ->
                assertTrue(archive.isAutomatic)
                b2.restore(archive, b2.unlock(archive, RecoveryKey.display(key.toCharArray()).toCharArray()))
            }
            assertEquals(1, n2.readMetadata(strict = true).size)
            assertEquals(1, r2.readAll(strict = true).size)
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun theFingerprintMovesWithWhatABackupHoldsAndNothingElse() = runBlocking<Unit> {
        seed()
        val seen = mutableListOf(backup.fingerprint())
        suspend fun changed(what: String) {
            val next = backup.fingerprint()
            assertTrue(next !in seen, what)
            seen += next
        }
        suspend fun same(what: String) = assertEquals(seen.last(), backup.fingerprint(), what)

        same("read again")
        queue.enqueue(QueuedCapture("fg_2", "recording_2.m4a", now, attempts = 0, flagged = false, zoneId = null), ByteArray(10))
        same("a waiting recording")
        val made = reminders.create { ReminderItem(it, now, "gone again") }
        reminders.remove(made.id)
        same("a reminder added and removed: only the next id moved")

        val id = notes.createNote("work", "b.md", "beta", modifiedAtMillis = 20L)
        changed("a note added")
        notes.updateNote(id, "beta two")
        changed("a note edited")
        notes.createFolder("home", "")
        changed("a folder added")
        notes.updateFolderTags { it + ("home" to listOf("house")) }
        changed("folder tags")
        reminders.create { ReminderItem(it, now + 1, "another") }
        changed("a reminder added")
        events.appendFire(ReminderEvent(made.id, now - 1_000L, "gone again", now - 900L))
        changed("reminder history")
        File(root, "${NoteStore.NOTES_DIR}/$id").setLastModified(1_000L)
        changed("a note file written without its index entry")
    }

    @Test
    fun aStoreThatCannotBeReadFailsTheFingerprint() = runBlocking<Unit> {
        seed()
        crypto.failOpenWhenAadContains = ReminderStore.FILE_NAME
        assertFailsWith<TransientCryptoException> { backup.fingerprint() }
        Unit
    }
}
