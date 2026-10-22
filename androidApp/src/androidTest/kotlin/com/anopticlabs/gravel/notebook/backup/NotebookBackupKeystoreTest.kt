package com.anopticlabs.gravel.notebook.backup

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.alarms.AndroidReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.AndroidReminderNotifier
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.notes.NoteMarkdown
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings
import com.anopticlabs.gravel.notebook.storage.BlobRead
import com.anopticlabs.gravel.notebook.storage.EncryptedFiles
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.appKeyCrypto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A backup of one throwaway root published into a [TestDocumentsProvider] document, verified
 * there, read back through the resolver and restored into another root, both over the real
 * Keystore codec, with the restored reminder armed on the real AlarmManager and disarmed again;
 * ids sit far above any the app has minted and below the ceiling a restore accepts.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.backup.NotebookBackupKeystoreTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class NotebookBackupKeystoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val crypto = appKeyCrypto()
    private val password = "correct horse battery".toCharArray()
    private val settings = object : ReminderSettings {
        override val swipeSnoozeMinutes = 10
        override val historyRetentionDays = 90
    }
    private val source = Side(File(context.filesDir, "notebook-backup-src-${System.nanoTime()}"))
    private val target = Side(File(context.filesDir, "notebook-backup-dst-${System.nanoTime()}"))

    private inner class Side(val root: File) {
        val notes = NoteStore(root, crypto)
        val queue = QueueStore(root, crypto)
        val reminders = ReminderStore(root, crypto)
        val events = ReminderEventStore(root, crypto)
        val alarms = ReminderAlarms(reminders, events, AndroidReminderAlarms(context), AndroidReminderNotifier(context) { "test-token" }, settings, ReminderRefreshSignal())
        val backup = NotebookBackup(notes, queue, reminders, events, alarms, ReminderRefreshSignal(), BackupStaging(root))
    }

    private val docs = TestDocsClient(context).also { it.connect() }

    @After
    fun cleanUp() = runBlocking<Unit> {
        docs.reset()
        for (side in listOf(source, target)) {
            for (item in runCatching { side.reminders.readAll(strict = true) }.getOrDefault(emptyList())) side.alarms.disarm(item.id)
            side.root.deleteRecursively()
        }
    }

    @Test
    fun aBackupRoundTripsThroughTheKeystoreAndArmsTheRestoredReminder() = runBlocking<Unit> {
        val future = System.currentTimeMillis() + 3_600_000L
        val content = NoteMarkdown.buildMarkdown("Groceries", listOf("Shopping"), "milk and eggs", "2026-09-22")
        source.notes.createNote("home", "groceries_09.22.26_0900.md", content, title = "Groceries", date = "2026-09-22")
        source.notes.createFolder("home", "chores")
        val audio = ByteArray(200_000) { (it * 3).toByte() }
        source.queue.enqueue(QueuedCapture("bk_1", "recording_1.m4a", 1_700_000_000_000L, attempts = 1), audio)
        source.reminders.addAllSkippingExistingIds(listOf(ReminderItem(ID_FLOOR, 1L, "floor")))
        source.reminders.removeByIds(listOf(ID_FLOOR))
        val item = source.reminders.create { ReminderItem(it, future, "backup test") }
        source.events.appendFire(ReminderEvent(item.id, future - 60_000L, "backup test", future - 59_000L, completedAt = future - 58_000L))
        target.reminders.addAllSkippingExistingIds(listOf(ReminderItem(ID_FLOOR, 1L, "floor")))
        target.reminders.removeByIds(listOf(ID_FLOOR))

        val ops = BackupDocuments(context.contentResolver)
        val staged = source.backup.create(password)
        val size = staged.staged.size
        assertTrue(size > 2 * ArchiveFormat.SEGMENT_BYTES)
        val published = staged.use { source.backup.publish(ops, docs.createGranted(FINAL_NAME), it, password) }
        assertEquals(BackupPublishOutcome(FINAL_NAME, size), published.outcome)
        assertFalse(staged.staged.file.exists())
        val finalUri = TestDocumentsProvider.documentUri(FINAL_NAME).also { docs.grant(it) }
        val archive = ops.openRead(finalUri).use { target.backup.stage(it) }
        val (summary, report) = archive.use { target.backup.unlock(it, password).let { key -> target.backup.inspect(it, key) to target.backup.restore(it, key) } }

        assertEquals(0, staged.created.notesMissing)
        assertEquals(0, staged.created.capturesMissing)
        assertEquals(summary, published.summary)
        assertTrue(summary.healthy)
        assertEquals(1, summary.notes)
        assertEquals(1, report.notesWritten)
        assertEquals(1, report.capturesAdded)
        assertEquals(1, report.remindersAdded)
        assertEquals(1, report.eventsAdded)
        val (restoredId, restored) = target.notes.readMetadata().entries.single()
        assertEquals("home/groceries_09.22.26_0900.md", "${restored.folder}/${restored.name}")
        assertEquals(content, target.notes.readNote(restoredId))
        assertEquals(setOf("shop"), target.notes.readStems(strict = true).getValue(restoredId).tags)
        assertEquals(mapOf("home" to listOf("home", "chores")), target.notes.readFolderTags())
        assertContentEquals(audio, assertIs<BlobRead.Present>(target.queue.readBlob("bk_1")).bytes)
        assertEquals(listOf(item), target.reminders.readAll(strict = true))
        assertEquals(future - 58_000L, target.events.readAll(strict = true).single().completedAt)
        assertFalse(File(target.root, EncryptedFiles.QUARANTINE_DIR).exists())
        assertEquals(emptyList(), File(target.root, BackupStaging.DIR).listFiles().orEmpty().toList())
    }

    private companion object {
        const val ID_FLOOR = 90_000_000
        const val FINAL_NAME = "gravel-notebook-keystore-test.backup"
    }
}
