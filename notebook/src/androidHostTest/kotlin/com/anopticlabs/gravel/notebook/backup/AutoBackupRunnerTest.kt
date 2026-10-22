package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AutoBackupRunner] over real stores on a throwaway root and a [FakeFolder]. */
class AutoBackupRunnerTest {

    private var now = 1_700_000_000_000L
    private val key = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(2).take(64)
    private val tree = "tree:a"
    private val root: File = Files.createTempDirectory("notebook-runner").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val notes = NoteStore(root, crypto) { now }
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val refresh = ReminderRefreshSignal()
    private val backup = NotebookBackup(
        notes, QueueStore(root, crypto) { now }, reminders, events,
        ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), refresh, { now }, { TimeZone.UTC }),
        refresh, BackupStaging(root), clock = { now },
    )
    private val store = AutoBackupStore(root, crypto)
    private val folder = FakeFolder()
    private val noteFile = File(root, "auto_run_note")
    private val note = AutoBackupRunNote(noteFile)
    private val gate = BackupGate()
    private val notices = FakeNotices()
    private val runner = AutoBackupRunner(backup, store, folder, note, gate, notices, clock = { now }, zone = { TimeZone.UTC })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun setUp(): AutoBackupEntry {
        notes.createNote("work", "a.md", "alpha", modifiedAtMillis = 1L)
        reminders.create { ReminderItem(it, now + 60_000L, "call") }
        return assertIs<AutoRunResult.Saved>(runner.firstBackup(tree, key.toCharArray(), SetupPurpose.NEW_KEY)).entry
    }

    private suspend fun run(force: Boolean = false, interactive: Boolean = false) =
        runner.run(if (force) AutoBackupRunner.SLOT_NOW else AutoBackupRunner.SLOT_CHANGE, force = force, interactive = interactive)

    private fun stamp(): Long = store.readKey()!!.createdAtMillis

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun partials() = folder.docs.values.filter { it.name.endsWith(PARTIAL_SUFFIX) }

    @Test
    fun theFirstBackupStoresTheKeyTurnsBackupsOnAndRecordsTheCheckedFile() = runBlocking<Unit> {
        val entry = setUp()
        assertEquals(key, store.readKey()?.chars?.concatToString())
        val state = store.readState(strict = true)
        assertTrue(state.enabled)
        assertEquals(tree, state.folder)
        assertEquals("Backups", state.folderName)
        assertEquals(listOf(entry), state.backups)
        assertEquals(now, state.lastConfirmedAtMillis)
        assertEquals("gravel-notebook-auto-2023-11-14-221320.gnbk", entry.name)
        val doc = folder.docs.getValue(entry.uri)
        assertEquals(entry.name, doc.name)
        assertEquals(entry.sha256, sha256(doc.bytes))
        assertEquals(1, entry.notes)
        assertEquals(1, entry.reminders)
        assertEquals(backup.fingerprint(), entry.fingerprint)
        assertEquals(1, backup.check(ByteArrayInputStream(doc.bytes), key.toCharArray()).counts.notes)
        assertTrue(partials().isEmpty())
        assertTrue(!noteFile.exists())
    }

    @Test
    fun withNothingChangedARunReadsTheNewestBackAndWritesNothing() = runBlocking<Unit> {
        setUp()
        now += 3_600_000L
        assertEquals(AutoRunResult.Confirmed, run())
        assertEquals(1, folder.docs.size)
        assertEquals(now, store.readState(strict = true).lastConfirmedAtMillis)
    }

    @Test
    fun theRunAfterSetupOfANotebookWithoutRemindersFindsNothingChanged() = runBlocking<Unit> {
        notes.createNote("work", "a.md", "alpha")
        assertIs<AutoRunResult.Saved>(runner.firstBackup(tree, key.toCharArray(), SetupPurpose.NEW_KEY))
        now += 1_000L
        assertEquals(AutoRunResult.Confirmed, run())
    }

    @Test
    fun aChangeWritesANewBackupAndBackUpNowWritesOneEvenWithoutAChange() = runBlocking<Unit> {
        setUp()
        now += 1_000L
        notes.createNote("work", "b.md", "beta", modifiedAtMillis = 2L)
        val changed = assertIs<AutoRunResult.Saved>(run()).entry
        assertEquals(2, changed.notes)
        now += 1_000L
        assertIs<AutoRunResult.Saved>(run(force = true, interactive = true))
        assertEquals(3, folder.docs.size)
        assertEquals(3, store.readState(strict = true).backups.size)
        assertEquals(store.readState(strict = true).backups.map { it.uri }.toSet(), folder.docs.keys)
    }

    @Test
    fun aNewestBackupThatNoLongerReadsBackGetsAFreshOneAndIsLeftWhereItIs() = runBlocking<Unit> {
        val first = setUp()
        val tampered = folder.docs.getValue(first.uri).bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        folder.docs.getValue(first.uri).bytes = tampered
        now += 1_000L
        assertIs<AutoRunResult.Saved>(run())
        assertContentEquals(tampered, folder.docs.getValue(first.uri).bytes)
        folder.delete(store.readState(strict = true).backups.last().uri)
        now += 1_000L
        assertIs<AutoRunResult.Saved>(run())
    }

    @Test
    fun offOrNotSetUpDoesNothing() = runBlocking<Unit> {
        assertEquals(AutoRunResult.Off, run())
        setUp()
        store.updateState { it.copy(enabled = false) }
        notes.createNote("work", "b.md", "beta")
        assertEquals(AutoRunResult.Off, run(force = true))
        assertEquals(1, folder.docs.size)
    }

    @Test
    fun failuresARetryCannotFixAreReportedAtOnce() = runBlocking<Unit> {
        setUp()
        for ((access, problem) in listOf(FolderAccess.NO_ACCESS to AutoBackupProblem.NO_ACCESS, FolderAccess.GONE to AutoBackupProblem.FOLDER_GONE)) {
            folder.access[tree] = access
            assertEquals(AutoRunResult.Failed(problem, retry = false), run())
        }
        assertEquals(listOf(AutoBackupProblem.NO_ACCESS, AutoBackupProblem.FOLDER_GONE), notices.failed)
        val failure = store.readState(strict = true).failure!!
        assertEquals(AutoBackupProblem.FOLDER_GONE, failure.problem)
        assertTrue(failure.reported)
        assertEquals(2, failure.tries)
    }

    @Test
    fun aFolderAppThatWillNotMakeTheFileIsRetried() = runBlocking<Unit> {
        setUp()
        folder.failCreate = AutoBackupProblem.NOT_CREATED
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_CREATED, retry = true), run(force = true))
        assertTrue(notices.failed.isEmpty())
    }

    @Test
    fun aMissingKeyIsReportedAtOnce() = runBlocking<Unit> {
        setUp()
        File(root, AutoBackupStore.KEY_FILE).delete()
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.KEY_MISSING, retry = false), run())
        assertEquals(listOf(AutoBackupProblem.KEY_MISSING), notices.failed)
    }

    @Test
    fun failuresARetryMightFixAreRetriedThreeTimesThenReportedAndASuccessClearsThem() = runBlocking<Unit> {
        setUp()
        folder.failRename = true
        repeat(AutoBackupRunner.MAX_RETRIES) {
            now += 1_000L
            assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = true), run(force = true))
            assertTrue(notices.failed.isEmpty())
            assertTrue(store.readState(strict = true).failure?.reported == false)
        }
        now += 1_000L
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = false), run(force = true))
        assertEquals(listOf(AutoBackupProblem.NOT_SAVED), notices.failed)
        assertEquals(1, folder.docs.size)
        assertTrue(partials().isEmpty())

        folder.failRename = false
        now += 1_000L
        val clearsBefore = notices.clears
        assertIs<AutoRunResult.Saved>(run(force = true))
        assertNull(store.readState(strict = true).failure)
        assertTrue(notices.clears > clearsBefore)
    }

    @Test
    fun aWatchedRunShowsItsFailureWithoutANotification() = runBlocking<Unit> {
        setUp()
        folder.failRename = true
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = false), run(force = true, interactive = true))
        assertTrue(notices.failed.isEmpty())
        assertTrue(store.readState(strict = true).failure!!.reported)
    }

    @Test
    fun aRenamedFileThatReadsBackDifferentlyIsRemoved() = runBlocking<Unit> {
        setUp()
        folder.corruptOnRename = true
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = true), run(force = true))
        assertEquals(1, folder.docs.size)
        assertEquals(1, store.readState(strict = true).backups.size)
    }

    @Test
    fun aFolderAppThatKeepsTheHandleOnRenameWorks() = runBlocking<Unit> {
        setUp()
        folder.keepIdOnRename = true
        now += 1_000L
        val entry = assertIs<AutoRunResult.Saved>(run(force = true)).entry
        assertTrue(entry.name.endsWith(".gnbk"))
        assertEquals(entry.name, folder.docs.getValue(entry.uri).name)
    }

    @Test
    fun aStoreThatCannotBeReadIsRetried() = runBlocking<Unit> {
        setUp()
        notes.createNote("work", "b.md", "beta")
        crypto.failOpenWhenAadContains = ReminderStore.FILE_NAME
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_READ, retry = true), run())
        assertEquals(1, folder.docs.size)
    }

    @Test
    fun aHeldGateMakesAnUnwatchedRunBusyAndAWatchedOneWait() = runBlocking<Unit> {
        setUp()
        assertTrue(gate.tryLock())
        assertEquals(AutoRunResult.Busy, run())
        val watched = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { run(force = true, interactive = true) }
        delay(100)
        assertTrue(!watched.isCompleted)
        gate.unlock()
        assertIs<AutoRunResult.Saved>(withTimeout(10_000) { watched.await() })
    }

    @Test
    fun theNextRunRemovesWhatARunEndedByItsProcessLeft() = runBlocking<Unit> {
        setUp()
        val empty = folder.put(tree, "x.gnbk.part", ByteArray(0))
        val other = folder.put(tree, "y.gnbk.part", ByteArray(0))
        note.write(AutoRunStep.Creating("x.gnbk.part"))
        assertEquals(AutoRunResult.Confirmed, run())
        assertNull(folder.docs[empty])
        assertTrue(other in folder.docs, "a document the note does not name")

        val written = folder.put(tree, "w.gnbk.part", ByteArray(5))
        note.write(AutoRunStep.Writing(written))
        run()
        assertNull(folder.docs[written])

        val unrenamed = folder.put(tree, "r.gnbk.part", ByteArray(5))
        note.write(AutoRunStep.Renaming(unrenamed, "r.gnbk"))
        run()
        assertNull(folder.docs[unrenamed])

        val renamed = folder.put(tree, "s.gnbk", ByteArray(5))
        note.write(AutoRunStep.Renaming("gone", "s.gnbk"))
        run()
        assertTrue(renamed in folder.docs, "a checked file under its final name stays")
        assertTrue(!noteFile.exists())
    }

    @Test
    fun aBackupThatLeavesOutAnUnreadableNoteIsSavedAndReported() = runBlocking<Unit> {
        setUp()
        val id = notes.createNote("work", "b.md", "beta")
        File(root, "${NoteStore.NOTES_DIR}/$id").delete()
        val saved = assertIs<AutoRunResult.Saved>(run())
        assertEquals(1, saved.created.notesMissing)
        assertEquals(1, notices.shortfalls.size)
        now += 1_000L
        assertEquals(1, assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:b", key.toCharArray(), SetupPurpose.CHANGE_FOLDER, stamp())).created.notesMissing)
        assertEquals(1, notices.shortfalls.size, "setup shows its own gaps")
    }

    @Test
    fun setupIntoTheSameFolderKeepsTheRecordAndIntoAnotherStartsItOver() = runBlocking<Unit> {
        setUp()
        now += 1_000L
        assertIs<AutoRunResult.Saved>(runner.firstBackup(tree, key.toCharArray(), SetupPurpose.CHANGE_FOLDER, stamp()))
        assertEquals(2, store.readState(strict = true).backups.size)
        now += 1_000L
        assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:b", key.toCharArray(), SetupPurpose.CHANGE_FOLDER, stamp()))
        val state = store.readState(strict = true)
        assertEquals("tree:b", state.folder)
        assertEquals(1, state.backups.size)
        assertEquals(3, folder.docs.size, "files in the earlier folder stay")
    }

    @Test
    fun aRunEndedAfterTheRenameKeepsTheCheckedFileWhenTheFolderAppKeepsItsHandle() = runBlocking<Unit> {
        setUp()
        folder.keepIdOnRename = true
        now += 1_000L
        val entry = assertIs<AutoRunResult.Saved>(run(force = true)).entry
        // As if the process ended after the ledger write, before the note was cleared.
        note.write(AutoRunStep.Renaming(entry.uri, entry.name))
        assertEquals(AutoRunResult.Confirmed, run())
        assertTrue(entry.uri in folder.docs)
        val part = folder.put(tree, "p.gnbk.part", ByteArray(3))
        note.write(AutoRunStep.Renaming(part, "p.gnbk"))
        run()
        assertNull(folder.docs[part])
    }

    @Test
    fun aDocumentThatWouldNotGoKeepsItsNoteAndTheNextRunRemovesIt() = runBlocking<Unit> {
        setUp()
        folder.corruptWrites = true
        folder.failDelete = true
        now += 1_000L
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = true), run(force = true))
        assertEquals(1, partials().size)
        assertTrue(noteFile.exists())
        folder.corruptWrites = false
        folder.failDelete = false
        now += 1_000L
        assertIs<AutoRunResult.Saved>(run(force = true))
        assertTrue(partials().isEmpty())
        assertEquals(2, folder.docs.size)
    }

    @Test
    fun aReadBackThatFailsRemovesTheDocument() = runBlocking<Unit> {
        setUp()
        folder.corruptWrites = true
        now += 1_000L
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = true), run(force = true))
        assertEquals(1, folder.docs.size)
        assertTrue(!noteFile.exists())
    }

    @Test
    fun aDamagedKeyFileIsReportedAtOnce() = runBlocking<Unit> {
        setUp()
        File(root, AutoBackupStore.KEY_FILE).writeBytes(ByteArray(64) { 7 })
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.DAMAGED, retry = false), run())
        assertEquals(listOf(AutoBackupProblem.DAMAGED), notices.failed)
    }

    @Test
    fun aStateThatCannotBeReadCountsTheCallersAttempts() = runBlocking<Unit> {
        setUp()
        crypto.failOpenWhenAadContains = AutoBackupStore.STATE_FILE
        for (attempt in 0 until AutoBackupRunner.MAX_RETRIES) {
            assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_READ, retry = true), runner.run(AutoBackupRunner.SLOT_CHANGE, attempt = attempt))
        }
        assertTrue(notices.failed.isEmpty())
        assertEquals(
            AutoRunResult.Failed(AutoBackupProblem.NOT_READ, retry = false),
            runner.run(AutoBackupRunner.SLOT_CHANGE, attempt = AutoBackupRunner.MAX_RETRIES),
        )
        assertEquals(listOf(AutoBackupProblem.NOT_READ), notices.failed)
    }

    /** A runner whose staging never has room, so a run that writes fails for space. */
    private fun runnerWithoutStagingSpace(): AutoBackupRunner<String> {
        val tight = NotebookBackup(
            notes, QueueStore(root, crypto) { now }, reminders, events,
            ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), refresh, { now }, { TimeZone.UTC }),
            refresh, BackupStaging(root, Long.MAX_VALUE), clock = { now },
        )
        return AutoBackupRunner(tight, store, folder, note, gate, notices, clock = { now }, zone = { TimeZone.UTC })
    }

    /** With the state file unwritable, [failing] is retried without a notice for three attempts and then told once as [problem]. */
    private suspend fun assertCountedByAttempts(problem: AutoBackupProblem, failing: suspend (attempt: Int) -> AutoRunResult) {
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        for (attempt in 0 until AutoBackupRunner.MAX_RETRIES) assertEquals(AutoRunResult.Failed(problem, retry = true), failing(attempt))
        assertTrue(notices.failed.isEmpty())
        assertEquals(AutoRunResult.Failed(problem, retry = false), failing(AutoBackupRunner.MAX_RETRIES))
        assertEquals(listOf(problem), notices.failed)
    }

    @Test
    fun aConfirmThatCannotBeRecordedCountsTheCallersAttempts() = runBlocking<Unit> {
        setUp()
        now += 1_000L
        assertCountedByAttempts(AutoBackupProblem.NOT_READ) { runner.run(AutoBackupRunner.SLOT_CHANGE, attempt = it) }
        assertNull(store.readState(strict = true).failure)
    }

    @Test
    fun aRunWithNoStagingSpaceThatCannotBeRecordedCountsTheCallersAttempts() = runBlocking<Unit> {
        setUp()
        val r = runnerWithoutStagingSpace()
        assertCountedByAttempts(AutoBackupProblem.NO_SPACE) { r.run(AutoBackupRunner.SLOT_CHANGE, force = true, attempt = it) }
    }

    @Test
    fun aBackupThatCannotBeRecordedCountsTheCallersAttempts() = runBlocking<Unit> {
        setUp()
        notes.createNote("work", "b.md", "beta")
        assertCountedByAttempts(AutoBackupProblem.NOT_RECORDED) {
            now += 1_000L
            runner.run(AutoBackupRunner.SLOT_CHANGE, attempt = it)
        }
    }

    @Test
    fun aFolderThatCannotBeReachedAndAFailureThatCannotBeRecordedCountTheCallersAttempts() = runBlocking<Unit> {
        setUp()
        folder.access[tree] = FolderAccess.UNAVAILABLE
        assertCountedByAttempts(AutoBackupProblem.NOT_SAVED) { runner.run(AutoBackupRunner.SLOT_CHANGE, attempt = it) }
    }

    @Test
    fun aKeyThatCannotBeReadAndAFailureThatCannotBeRecordedCountTheCallersAttempts() = runBlocking<Unit> {
        setUp()
        crypto.failOpenWhenAadContains = AutoBackupStore.KEY_FILE
        assertCountedByAttempts(AutoBackupProblem.NOT_READ) { runner.run(AutoBackupRunner.SLOT_CHANGE, attempt = it) }
    }

    @Test
    fun anEndedRunsDocumentThatStaysAndAFailureThatCannotBeRecordedCountTheCallersAttempts() = runBlocking<Unit> {
        setUp()
        note.write(AutoRunStep.Writing(folder.put(tree, "w.gnbk.part", ByteArray(5))))
        folder.failDelete = true
        assertCountedByAttempts(AutoBackupProblem.NOT_SAVED) { runner.run(AutoBackupRunner.SLOT_CHANGE, attempt = it) }
    }

    @Test
    fun aNotebookThatCannotBeReadAndAFailureThatCannotBeRecordedCountTheCallersAttempts() = runBlocking<Unit> {
        setUp()
        crypto.failOpenWhenAadContains = ReminderStore.FILE_NAME
        assertCountedByAttempts(AutoBackupProblem.NOT_READ) { runner.run(AutoBackupRunner.SLOT_CHANGE, attempt = it) }
    }

    @Test
    fun aFailureThatIsRecordedIsCountedByTheRecordWhateverTheCallersAttempt() = runBlocking<Unit> {
        setUp()
        val r = runnerWithoutStagingSpace()
        assertEquals(
            AutoRunResult.Failed(AutoBackupProblem.NO_SPACE, retry = true),
            r.run(AutoBackupRunner.SLOT_CHANGE, force = true, attempt = AutoBackupRunner.MAX_RETRIES),
        )
        assertTrue(notices.failed.isEmpty())
        assertEquals(1, store.readState(strict = true).failure?.tries)
    }

    @Test
    fun aRecordedCountStillDecidesWhenTheNextFailureCannotBeRecorded() = runBlocking<Unit> {
        setUp()
        now += 1_000L
        store.updateState { it.copy(failure = AutoBackupFailure(AutoBackupProblem.NOT_READ, now, AutoBackupRunner.MAX_RETRIES, reported = false)) }
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_READ, retry = false), runner.run(AutoBackupRunner.SLOT_CHANGE, attempt = 0))
        assertEquals(listOf(AutoBackupProblem.NOT_READ), notices.failed)
    }

    @Test
    fun backUpNowWhoseFailureCannotBeRecordedIsNotified() = runBlocking<Unit> {
        setUp()
        now += 1_000L
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_RECORDED, retry = false), run(force = true, interactive = true))
        assertEquals(listOf(AutoBackupProblem.NOT_RECORDED), notices.failed)
    }

    @Test
    fun tooLittleFreeSpaceIsRetried() = runBlocking<Unit> {
        setUp()
        val tight = NotebookBackup(
            notes, QueueStore(root, crypto) { now }, reminders, events,
            ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), refresh, { now }, { TimeZone.UTC }),
            refresh, BackupStaging(root, Long.MAX_VALUE), clock = { now },
        )
        val r = AutoBackupRunner(tight, store, folder, note, gate, notices, clock = { now }, zone = { TimeZone.UTC })
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NO_SPACE, retry = true), r.run(AutoBackupRunner.SLOT_NOW, force = true))
    }

    @Test
    fun aNewSetupIsRefusedWhileSetUpAndANewFolderKeepsTheSwitch() = runBlocking<Unit> {
        setUp()
        val other = "zyxwvutsrqponmlkjihgfedcba9876543210".repeat(2).take(64)
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.ALREADY_SET_UP, retry = false), runner.firstBackup("tree:b", other.toCharArray(), SetupPurpose.NEW_KEY))
        assertEquals(key, store.readKey()?.chars?.concatToString())
        store.updateState { it.copy(enabled = false) }
        now += 1_000L
        assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:b", key.toCharArray(), SetupPurpose.CHANGE_FOLDER, stamp()))
        assertFalse(store.readState(strict = true).enabled)
        assertEquals("tree:b", store.readState(strict = true).folder)
    }

    @Test
    fun aBackupWithoutGapsTakesDownTheGapsNotice() = runBlocking<Unit> {
        setUp()
        val id = notes.createNote("work", "b.md", "beta")
        File(root, "${NoteStore.NOTES_DIR}/$id").delete()
        assertIs<AutoRunResult.Saved>(run())
        assertEquals(1, notices.shortfalls.size)
        val before = notices.shortfallClears
        notes.deleteNote(id)
        now += 1_000L
        assertEquals(0, assertIs<AutoRunResult.Saved>(run()).created.notesMissing)
        assertTrue(notices.shortfallClears > before)
    }

    @Test
    fun aConfirmThatCannotBeRecordedIsNotCalledASave() = runBlocking<Unit> {
        setUp()
        now += 1_000L
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_READ, retry = true), run())
        assertEquals(1, folder.docs.size)
    }

    @Test
    fun aDocumentAnEndedRunLeftThatCannotBeRemovedHoldsTheRunAndKeepsItsNote() = runBlocking<Unit> {
        setUp()
        val left = folder.put(tree, "w.gnbk.part", ByteArray(5))
        note.write(AutoRunStep.Writing(left))
        folder.failDelete = true
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = true), run(force = true))
        assertEquals(AutoRunStep.Writing(left), note.read())
        assertEquals(2, folder.docs.size)
        folder.failDelete = false
        now += 1_000L
        assertIs<AutoRunResult.Saved>(run(force = true))
        assertNull(folder.docs[left])
    }

    @Test
    fun setupRemovesWhatAnEndedRunLeftBeforeItWrites() = runBlocking<Unit> {
        notes.createNote("work", "a.md", "alpha")
        val left = folder.put(tree, "w.gnbk.part", ByteArray(5))
        note.write(AutoRunStep.Writing(left))
        assertIs<AutoRunResult.Saved>(runner.firstBackup(tree, key.toCharArray(), SetupPurpose.NEW_KEY))
        assertNull(folder.docs[left])
        assertNull(note.read())
    }

    @Test
    fun aChangeDuringARunAsksForAFollowUpForThatSlot() = runBlocking<Unit> {
        setUp()
        var changes = 0L
        val followUps = mutableListOf<String>()
        val r = AutoBackupRunner(backup, store, folder, note, gate, notices, clock = { now }, zone = { TimeZone.UTC }, changeCount = { changes }, followUp = { followUps += it })
        assertEquals(AutoRunResult.Confirmed, r.run(AutoBackupRunner.SLOT_PERIODIC))
        assertTrue(followUps.isEmpty())
        folder.afterRename = { changes++ }
        now += 1_000L
        assertIs<AutoRunResult.Saved>(r.run(AutoBackupRunner.SLOT_CHANGE, force = true))
        assertEquals(listOf(AutoBackupRunner.SLOT_CHANGE), followUps)
        store.updateState { it.copy(enabled = false) }
        changes++
        assertEquals(AutoRunResult.Off, r.run(AutoBackupRunner.SLOT_CHANGE))
        assertEquals(1, followUps.size)
    }

    @Test
    fun aChangeWhileTheArchiveIsReadMakesTheNextRunWriteEvenWhenUndone() = runBlocking<Unit> {
        setUp()
        val id = notes.readMetadata(strict = true).keys.single()
        var fired = false
        crypto.beforeOpen = { location ->
            if (!fired && location.startsWith(NoteStore.NOTES_DIR + "/")) {
                fired = true
                runBlocking { reminders.create { ReminderItem(it, now + 5L, "during") } }
            }
        }
        now += 1_000L
        assertEquals("", assertIs<AutoRunResult.Saved>(run(force = true)).entry.fingerprint)
        crypto.beforeOpen = null
        assertTrue(fired)
        now += 1_000L
        assertIs<AutoRunResult.Saved>(run())
        assertTrue(id.isNotEmpty())
    }

    @Test
    fun aCanceledRunStillRecordsAFileItRenamed() = runBlocking<Unit> {
        setUp()
        val renamed = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        folder.afterRename = { renamed.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        now += 1_000L
        val job = launch(Dispatchers.IO) { runner.run(AutoBackupRunner.SLOT_NOW, force = true, interactive = true) }
        assertTrue(renamed.await(10, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()
        assertEquals(2, store.readState(strict = true).backups.size)
        assertEquals(store.readState(strict = true).backups.map { it.uri }.toSet(), folder.docs.keys)
    }

    @Test
    fun aNameTheFolderAlreadyHoldsGetsANumber() = runBlocking<Unit> {
        setUp()
        now += 1_000L
        folder.put(tree, automaticBackupName(now, TimeZone.UTC), ByteArray(3))
        val entry = assertIs<AutoRunResult.Saved>(run(force = true)).entry
        assertEquals(automaticBackupName(now, TimeZone.UTC).removeSuffix(".gnbk") + "-1.gnbk", entry.name)
    }
}
