package com.anopticlabs.gravel.notebook.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.alarms.AndroidReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.AndroidReminderNotifier
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.appKeyCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Automatic backups into a [TestDocumentsProvider] folder the app holds a persistable grant on,
 * over the real Keystore codec and the production [BackupFolderDocuments], then a restore of one
 * with its recovery key into another root.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.backup.AutoBackupDeviceTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class AutoBackupDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val crypto = appKeyCrypto()
    private val settings = object : ReminderSettings {
        override val swipeSnoozeMinutes = 10
        override val historyRetentionDays = 90
    }
    private val key = RecoveryKey.generate(SecureRandom()::nextInt)
    private val runDir = File(context.cacheDir, "notebook-auto-run-${System.nanoTime()}").also { it.mkdirs() }
    private val source = Side(File(context.filesDir, "notebook-auto-src-${System.nanoTime()}"))
    private val target = Side(File(context.filesDir, "notebook-auto-dst-${System.nanoTime()}"))
    private val docs = TestDocsClient(context).also { it.connect() }
    private val folder = BackupFolderDocuments(context.contentResolver)
    private val notices = object : AutoBackupNotices {
        val failed = CopyOnWriteArrayList<AutoBackupProblem>()
        override fun failed(problem: AutoBackupProblem) {
            failed += problem
        }
        val missing = CopyOnWriteArrayList<Int>()
        override fun shortfall(created: CreatedBackup) = Unit
        override fun clearShortfall() = Unit
        override fun clearFailure() = Unit
        override fun farFewer(before: AutoBackupEntry, after: AutoBackupEntry, notes: Boolean, remindersGone: Int) = Unit
        override fun damaged(count: Int, backupsOn: Boolean) = Unit
        override fun missing(count: Int, backupsOn: Boolean) {
            missing += count
        }
        override fun stale(lastConfirmedAtMillis: Long) = Unit
        override fun clearStale() = Unit
        override fun keyCheckDue() = Unit
        override fun clearKeyCheck() = Unit
    }
    private val store = AutoBackupStore(source.root, crypto)
    private val note = AutoBackupRunNote(File(runDir, "note"))
    private val runner = AutoBackupRunner(source.backup, store, folder, note, BackupGate(), notices)
    private var tree: Uri? = null

    private inner class Side(val root: File) {
        val notes = NoteStore(root, crypto)
        val reminders = ReminderStore(root, crypto)
        val events = ReminderEventStore(root, crypto)
        val backup = NotebookBackup(
            notes, QueueStore(root, crypto), reminders, events,
            ReminderAlarms(reminders, events, AndroidReminderAlarms(context), AndroidReminderNotifier(context) { "test-token" }, settings, ReminderRefreshSignal()),
            ReminderRefreshSignal(), BackupStaging(root),
        )
    }

    @After
    fun cleanUp() {
        tree?.let { folder.releaseFolder(it.toString()) }
        docs.reset()
        key.fill('\u0000')
        source.root.deleteRecursively()
        target.root.deleteRecursively()
        runDir.deleteRecursively()
    }

    private fun grantedFolder(): String {
        val uri = docs.grantFolder()
        tree = uri
        assertTrue(folder.holdFolder(uri.toString()))
        return uri.toString()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun backupsAreWrittenCheckedAndNamedInTheFolderAndRestoreWithTheKey() = runBlocking<Unit> {
        source.notes.createNote("home", "groceries.md", "milk and eggs", title = "Groceries")
        val folderId = grantedFolder()
        assertEquals(FolderAccess.OK, folder.access(folderId))
        assertEquals("test-documents-root", folder.folderName(folderId))

        val first = assertIs<AutoRunResult.Saved>(runner.firstBackup(folderId, key, SetupPurpose.NEW_KEY)).entry
        assertEquals(listOf(first.name), docs.names())
        assertTrue(Regex("gravel-notebook-auto-\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.gnbk").matches(first.name), first.name)
        val bytes = docs.bytesOf(first.name)
        assertEquals(first.sha256, sha256(bytes))
        assertEquals(1, source.backup.check(bytes.inputStream(), key).counts.notes)

        assertEquals(AutoRunResult.Confirmed, runner.run(AutoBackupRunner.SLOT_PERIODIC))
        Thread.sleep(1_100)
        source.notes.createNote("home", "chores.md", "sweep")
        val second = assertIs<AutoRunResult.Saved>(runner.run(AutoBackupRunner.SLOT_CHANGE)).entry
        assertEquals(2, second.notes)
        assertEquals(listOf(first.name, second.name).sorted(), docs.names())
        assertTrue(docs.names().none { it.endsWith(PARTIAL_SUFFIX) })

        val shown = RecoveryKey.display(key).toCharArray()
        target.backup.stage(docs.bytesOf(second.name).inputStream()).use { archive ->
            assertTrue(archive.isAutomatic)
            target.backup.restore(archive, target.backup.unlock(archive, shown))
        }
        assertEquals(setOf("Groceries", null), target.notes.readMetadata(strict = true).values.map { it.title }.toSet())
        assertEquals(2, target.notes.readMetadata(strict = true).size)
    }

    @Test
    fun aRunEndedByItsProcessHasItsPartialFileRemovedByTheNextRun() = runBlocking<Unit> {
        source.notes.createNote("home", "groceries.md", "milk")
        val folderId = grantedFolder()
        assertIs<AutoRunResult.Saved>(runner.firstBackup(folderId, key, SetupPurpose.NEW_KEY))
        docs.control(TestDocumentsProvider.METHOD_CREATE, "left.gnbk.part", android.os.Bundle().apply { putByteArray(TestDocumentsProvider.EXTRA_BYTES, ByteArray(10)) })
        val left = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(folderId), "root/left.gnbk.part")
        assertTrue(note.write(AutoRunStep.Writing(left.toString())))
        assertEquals(AutoRunResult.Confirmed, runner.run(AutoBackupRunner.SLOT_PERIODIC))
        assertTrue("left.gnbk.part" !in docs.names())
        assertEquals(null, note.read())
    }

    @Test
    fun thinningDeletesThroughTheFolderAppAndAFileDeletedElsewhereIsReportedGone() = runBlocking<Unit> {
        // Noon on one local day, so the first three backups share a day.
        var now = LocalDateTime(2026, 6, 10, 12, 0).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val timed = AutoBackupRunner(source.backup, store, folder, note, BackupGate(), notices, clock = { now })
        source.notes.createNote("home", "a.md", "a")
        val folderId = grantedFolder()
        val first = assertIs<AutoRunResult.Saved>(timed.firstBackup(folderId, key, SetupPurpose.NEW_KEY)).entry
        val sameDay = (1..2).map { i ->
            now += 60_000L
            source.notes.createNote("home", "n$i.md", "n")
            assertIs<AutoRunResult.Saved>(timed.run(AutoBackupRunner.SLOT_CHANGE)).entry
        }
        now += 3 * 24 * 3_600_000L
        source.notes.createNote("home", "later.md", "l")
        val latest = assertIs<AutoRunResult.Saved>(timed.run(AutoBackupRunner.SLOT_CHANGE)).entry
        assertEquals(listOf(sameDay[1].name, latest.name).sorted(), docs.names())
        assertEquals(listOf(sameDay[1].uri, latest.uri), store.readState(strict = true).backups.map { it.uri })
        assertTrue(first.name !in docs.names())

        assertTrue(DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(sameDay[1].uri)))
        now += 3_600_000L
        assertEquals(AutoRunResult.Confirmed, timed.run(AutoBackupRunner.SLOT_PERIODIC))
        assertEquals(listOf(1), notices.missing.toList())
        assertEquals(listOf(latest.uri), store.readState(strict = true).backups.map { it.uri })
        assertTrue(store.readState(strict = true).writeNext)
    }

    @Test
    fun aFolderWhoseGrantWasReleasedIsReportedAtOnce() = runBlocking<Unit> {
        source.notes.createNote("home", "groceries.md", "milk")
        val folderId = grantedFolder()
        assertIs<AutoRunResult.Saved>(runner.firstBackup(folderId, key, SetupPurpose.NEW_KEY))
        folder.releaseFolder(folderId)
        assertEquals(FolderAccess.NO_ACCESS, folder.access(folderId))
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NO_ACCESS, retry = false), runner.run(AutoBackupRunner.SLOT_PERIODIC))
        assertEquals(listOf(AutoBackupProblem.NO_ACCESS), notices.failed.toList())
    }
}
