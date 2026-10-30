package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.KeyStoreFailureException
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.NotebookPart
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.RecoveryPlan
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import com.anopticlabs.gravel.notebook.ui.StorageProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BackupFlow] over a real [NotebookBackup] on throwaway roots and in-memory documents named by their handles. */
class BackupFlowTest {

    private val password = "correct horse battery"
    private val now = 1_700_000_000_000L
    private val docs = Docs()
    @Volatile private var drains = 0
    private val source = Side(Files.createTempDirectory("flow-src").toFile())
    private val target = Side(Files.createTempDirectory("flow-dst").toFile())

    private inner class Side(val root: File, minFreeBytes: Long = BackupStaging.MIN_FREE_BYTES) {
        val crypto = FakeCryptographer(softwareCrypto())
        val notes = NoteStore(root, crypto) { now }
        val queue = QueueStore(root, crypto) { now }
        val reminders = ReminderStore(root, crypto)
        val events = ReminderEventStore(root, crypto)
        val refresh = ReminderRefreshSignal()
        val alarms = ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), refresh, { now }, { TimeZone.UTC })
        val backup = Recording(NotebookBackup(notes, queue, reminders, events, alarms, refresh, BackupStaging(root, minFreeBytes), clock = { now }))
        val runs = MemoryRuns()
        val flow = newFlow()

        /** A flow as the next process builds it, over the same stores, documents and note of unfinished runs. */
        fun newFlow(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) =
            BackupFlow(backup, docs, runs, requestDrain = { drains++ }, clock = { now }, zone = { TimeZone.UTC }, scope = scope)

        fun stagingFiles(): List<File> = File(root, BackupStaging.DIR).listFiles().orEmpty().toList()

        /** Staging files are deleted after the state that ends their run, so this waits for that. */
        suspend fun assertNoStaging() = withTimeout(5_000) { while (stagingFiles().isNotEmpty()) kotlinx.coroutines.delay(10) }

        fun fingerprint(): Map<String, List<Byte>> = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
    }

    /** Records what the flow asks for, and lets a test hold a step until it is released. */
    private class Recording(val real: NotebookBackup) : BackupOperations {
        val unlocked = CopyOnWriteArrayList<ArchiveKey>()
        val inspectedWith = CopyOnWriteArrayList<ArchiveKey>()
        val restoredWith = CopyOnWriteArrayList<ArchiveKey>()
        val passwordsSeen = CopyOnWriteArrayList<CharArray>()
        @Volatile var holdCreate: CompletableDeferred<Unit>? = null
        @Volatile var holdPublish: CountDownLatch? = null
        @Volatile var holdRestore: CompletableDeferred<Unit>? = null
        @Volatile var holdStage: CompletableDeferred<Unit>? = null
        @Volatile var holdUnlock: CompletableDeferred<Unit>? = null
        @Volatile var failPublishWith: Exception? = null
        @Volatile var failRestoreWith: Exception? = null
        /** Held after the real work, past a cancellation, so the result arrives after its run ended. */
        @Volatile var holdAfterCreate: CompletableDeferred<Unit>? = null
        @Volatile var holdAfterStage: CompletableDeferred<Unit>? = null
        val afterCreateReached = CompletableDeferred<Unit>()
        val afterStageReached = CompletableDeferred<Unit>()
        @Volatile var afterVerified: () -> Unit = {}

        override suspend fun create(password: CharArray, onProgress: BackupProgress): StagedBackup {
            passwordsSeen += password
            holdCreate?.await()
            return real.create(password, onProgress).also { holdAfterCreate?.let { h -> afterCreateReached.complete(Unit); withContext(NonCancellable) { h.await() } } }
        }

        override fun <H> publish(
            ops: BackupDocumentOps<H>,
            target: H,
            backup: StagedBackup,
            password: CharArray,
            onWriting: () -> Unit,
            onVerified: () -> Unit,
            onProgress: BackupProgress,
        ): PublishedBackup {
            holdPublish?.await(10, TimeUnit.SECONDS)
            failPublishWith?.let { throw it }
            return real.publish(ops, target, backup, password, onWriting, { onVerified(); afterVerified() }, onProgress)
        }

        override suspend fun stage(input: InputStream): StagedArchive {
            holdStage?.await()
            return real.stage(input).also { holdAfterStage?.let { h -> afterStageReached.complete(Unit); withContext(NonCancellable) { h.await() } } }
        }

        override suspend fun unlock(archive: StagedArchive, password: CharArray): ArchiveKey {
            passwordsSeen += password
            holdUnlock?.await()
            return real.unlock(archive, password).also { unlocked += it }
        }

        override suspend fun inspect(archive: StagedArchive, key: ArchiveKey, onProgress: BackupProgress): BackupSummary {
            inspectedWith += key
            return real.inspect(archive, key, onProgress)
        }

        override suspend fun restore(archive: StagedArchive, key: ArchiveKey, beforeMerge: suspend () -> Unit, onProgress: BackupProgress): RestoreReport {
            restoredWith += key
            holdRestore?.await()
            failRestoreWith?.let { throw it }
            return real.restore(archive, key, beforeMerge, onProgress)
        }
    }

    /** The note of unfinished runs a process keeps; every write and clear is kept in [log]; [refuse] makes a write fail. */
    private class MemoryRuns : BackupRunRecord<String> {
        @Volatile var current: UnfinishedRun<String>? = null
        @Volatile var refuse: (UnfinishedRun<String>) -> Boolean = { false }
        val log = CopyOnWriteArrayList<UnfinishedRun<String>?>()

        override fun read() = current

        override fun write(run: UnfinishedRun<String>): Boolean {
            if (refuse(run)) return false
            current = run
            log += run
            return true
        }

        @Volatile var clears = 0

        override fun clear(expected: UnfinishedRun<String>) = synchronized(this) {
            clears++
            if (current == expected) {
                current = null
                log += null
            }
        }
    }

    /** Documents keyed by display name, which is also the handle. */
    private class Docs : BackupDocumentOps<String> {
        val files: MutableMap<String, ByteArray> = java.util.concurrent.ConcurrentHashMap()
        val held = CopyOnWriteArrayList<String>()
        val released = CopyOnWriteArrayList<String>()
        @Volatile var reportSize = true
        @Volatile var reportedSize: Long? = null
        @Volatile var failRead = false
        @Volatile var holdDelete: CountDownLatch? = null
        @Volatile var deleteStarted = false

        override fun describe(handle: String) = files[handle]?.let { BackupDocumentInfo(handle, reportedSize ?: if (reportSize) it.size.toLong() else null) }

        override fun hold(handle: String) = held.add(handle)

        override fun release(handle: String) {
            released += handle
        }

        override fun openWrite(handle: String): OutputStream {
            if (handle !in files) throw IOException("no $handle")
            files[handle] = ByteArray(0)
            return object : ByteArrayOutputStream() {
                override fun close() {
                    files[handle] = toByteArray()
                }
            }
        }

        override fun openRead(handle: String): InputStream {
            if (failRead) throw IOException("read refused")
            return ByteArrayInputStream(files[handle] ?: throw IOException("no $handle"))
        }

        override fun delete(handle: String): Boolean {
            deleteStarted = true
            holdDelete?.await(10, TimeUnit.SECONDS)
            return files.remove(handle) != null
        }
    }

    @AfterTest
    fun cleanUp() {
        source.root.deleteRecursively()
        target.root.deleteRecursively()
    }

    /** The first state of type [T], or a failure while waiting for anything else. */
    private suspend inline fun <reified T : BackupFlowState> BackupFlow<String>.await(): T {
        val s = withTimeout(20_000) { state.first { it is T || it is BackupFlowState.Failed } }
        return s as? T ?: error("got $s while waiting for ${T::class.simpleName}")
    }

    private suspend fun seed() {
        source.notes.createNote("work", "plans.md", "the budget meeting is on tuesday", title = "Plans")
    }

    /** A backup of [source] saved through its flow as [name]; returns the archive's bytes. */
    private suspend fun savedArchive(name: String = "saved.gnbk"): ByteArray {
        source.flow.startBackup()
        source.flow.backUp(password.toCharArray())
        source.flow.await<BackupFlowState.SaveTo>()
        assertTrue(source.flow.claimPicker())
        docs.files[name] = ByteArray(0)
        source.flow.documentChosen(name)
        source.flow.await<BackupFlowState.Saved>()
        source.flow.close()
        return docs.files.getValue(name)
    }

    private fun CharArray.isZeroed() = all { it == '\u0000' }

    private fun ArchiveKey.isWiped() = spec().encoded.all { it == 0.toByte() }

    // Backup

    @Test
    fun aBackupIsBuiltBeforeTheSavePickerOpensAndSavedCheckedUnderTheNameTheUserPicked() = runBlocking<Unit> {
        seed()
        val chars = password.toCharArray()
        source.flow.startBackup()
        source.flow.backUp(chars)

        val saveTo = source.flow.await<BackupFlowState.SaveTo>()
        assertEquals("gravel-notebook-2023-11-14-2213.gnbk", saveTo.suggestedName)
        assertEquals(1, source.stagingFiles().size)
        assertTrue(docs.files.isEmpty())
        assertTrue(source.flow.claimPicker())
        assertFalse(source.flow.claimPicker())

        docs.files["mine.gnbk"] = ByteArray(0)
        source.flow.documentChosen("mine.gnbk")
        val saved = source.flow.await<BackupFlowState.Saved>()

        assertEquals("mine.gnbk", saved.outcome.displayName)
        assertEquals(1, saved.summary.notes)
        assertEquals(setOf("mine.gnbk"), docs.files.keys)
        assertEquals("GNBK", docs.files.getValue("mine.gnbk").copyOf(4).decodeToString())
        source.assertNoStaging()
        assertTrue(chars.isZeroed())
        source.flow.close()
        assertEquals(BackupFlowState.Idle, source.flow.state.value)
    }

    @Test
    fun aBackupThatCannotReadTheNotebookFailsWithoutOpeningTheSavePicker() = runBlocking<Unit> {
        seed()
        source.crypto.failOpenWithKeyUnavailable = true
        val seen = CopyOnWriteArrayList<BackupFlowState>()
        val watcher = launch { source.flow.state.collect { seen += it } }
        val chars = password.toCharArray()
        source.flow.startBackup()
        source.flow.backUp(chars)

        val failed = source.flow.await<BackupFlowState.Failed>()
        watcher.cancel()

        assertEquals(BackupFailure.NotRead(StorageProblem.KEY_MISSING), failed.failure)
        assertTrue(seen.none { it is BackupFlowState.SaveTo }, "$seen")
        assertFalse(source.flow.claimPicker())
        assertTrue(docs.files.isEmpty())
        source.assertNoStaging()
        assertTrue(chars.isZeroed())
    }

    @Test
    fun aPasswordShorterThanTheMinimumStartsNothingAndIsZeroed() = runBlocking<Unit> {
        val chars = "fourteen chars".toCharArray()
        assertEquals(14, chars.size)
        source.flow.startBackup()
        source.flow.backUp(chars)

        assertEquals(BackupFlowState.NewPassword, source.flow.state.value)
        assertTrue(chars.isZeroed())
        assertTrue(source.backup.passwordsSeen.isEmpty())
    }

    @Test
    fun backingOutOfTheSavePickerOrCancelingTheBuildLeavesNoStagingFile() = runBlocking<Unit> {
        seed()
        val first = password.toCharArray()
        source.flow.startBackup()
        source.flow.backUp(first)
        source.flow.await<BackupFlowState.SaveTo>()
        assertTrue(source.flow.claimPicker())
        source.flow.documentChosen(null)
        assertEquals(BackupFlowState.Idle, source.flow.state.value)
        source.assertNoStaging()
        assertTrue(first.isZeroed())

        source.backup.holdCreate = CompletableDeferred()
        val second = password.toCharArray()
        source.flow.startBackup()
        source.flow.backUp(second)
        source.flow.await<BackupFlowState.Building>()
        source.flow.close()
        assertEquals(BackupFlowState.Idle, source.flow.state.value)
        assertTrue(second.isZeroed())
        source.assertNoStaging()
        assertTrue(docs.files.isEmpty())
    }

    @Test
    fun savingCannotBeClosedAndEndsSaved() = runBlocking<Unit> {
        seed()
        val gate = CountDownLatch(1)
        source.backup.holdPublish = gate
        source.flow.startBackup()
        source.flow.backUp(password.toCharArray())
        source.flow.await<BackupFlowState.SaveTo>()
        source.flow.claimPicker()
        docs.files["a.gnbk"] = ByteArray(0)
        source.flow.documentChosen("a.gnbk")
        source.flow.await<BackupFlowState.Saving>()

        source.flow.close()
        source.flow.startCheck()
        assertIs<BackupFlowState.Saving>(source.flow.state.value)
        gate.countDown()
        source.flow.await<BackupFlowState.Saved>()
    }

    @Test
    fun aSavePickerAnswerWithNoRunWaitingRemovesTheEmptyFileTheSavePickerLeft() = runBlocking<Unit> {
        docs.files["left.gnbk"] = ByteArray(0)
        source.flow.documentChosen("left.gnbk")
        assertEquals(BackupFailure.Interrupted("left.gnbk", removed = true, written = false), source.flow.await<BackupFlowState.Failed>().failure)
        assertFalse("left.gnbk" in docs.files)
        source.flow.close()

        docs.files["kept.gnbk"] = byteArrayOf(1, 2, 3)
        source.flow.documentChosen("kept.gnbk")
        assertEquals(BackupFailure.Interrupted("kept.gnbk", removed = false, written = false), source.flow.await<BackupFlowState.Failed>().failure)
        assertTrue("kept.gnbk" in docs.files)
    }

    // Restore and check

    @Test
    fun aRestoreUsesTheKeyItsCheckDerivedForBothPassesAndAsksAgainOnAWrongPassword() = runBlocking<Unit> {
        seed()
        savedArchive("b.gnbk")
        val flow = target.flow
        flow.startRestore()
        assertTrue(flow.claimPicker())
        flow.fileChosen(BackupPurpose.RESTORE, "b.gnbk")
        assertEquals(BackupFlowState.EnterPassword(BackupPurpose.RESTORE, "b.gnbk", wrong = false), flow.await<BackupFlowState.EnterPassword>())

        val wrong = "correct horse batterz".toCharArray()
        flow.open(wrong)
        withTimeout(20_000) { flow.state.first { it == BackupFlowState.EnterPassword(BackupPurpose.RESTORE, "b.gnbk", wrong = true) } }
        assertTrue(wrong.isZeroed())

        val right = password.toCharArray()
        flow.open(right)
        val contents = flow.await<BackupFlowState.Contents>()
        assertEquals(1, contents.summary.notes)
        assertTrue(right.isZeroed())
        flow.restore()
        val restored = flow.await<BackupFlowState.Restored>()

        assertEquals(2, target.backup.unlocked.size)
        assertSame(target.backup.unlocked[1], target.backup.inspectedWith.last())
        assertSame(target.backup.unlocked[1], target.backup.restoredWith.single())
        assertTrue(target.backup.unlocked[1].isWiped())
        assertEquals(1, restored.report.notesWritten)
        assertEquals("the budget meeting is on tuesday", target.notes.readNote(target.notes.readMetadata().keys.single()))
        target.assertNoStaging()
        assertEquals(0, drains)
    }

    @Test
    fun aCheckShowsWhatTheBackupHoldsAndWritesNothing() = runBlocking<Unit> {
        seed()
        savedArchive("c.gnbk")
        val before = target.fingerprint()
        val flow = target.flow
        flow.startCheck()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.CHECK, "c.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(password.toCharArray())
        val contents = flow.await<BackupFlowState.Contents>()
        assertEquals(BackupPurpose.CHECK, contents.purpose)
        assertEquals(mapOf("work" to 1), contents.summary.notesByFolder)

        flow.restore()
        assertSame(contents, flow.state.value)
        assertFalse(target.backup.unlocked.single().isWiped())
        flow.close()
        assertEquals(BackupFlowState.Idle, flow.state.value)
        assertTrue(target.backup.unlocked.single().isWiped())
        assertTrue(target.backup.restoredWith.isEmpty())
        target.assertNoStaging()
        assertEquals(before, target.fingerprint())
    }

    @Test
    fun aFileThatIsNotABackupIsRefusedBeforeAPasswordIsAsked() = runBlocking<Unit> {
        docs.files["photo.jpg"] = ByteArray(4096) { 0x42 }
        val flow = target.flow
        flow.startRestore()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.RESTORE, "photo.jpg")
        assertEquals(BackupFailure.NotOpened(BackupProblem.NOT_A_BACKUP), flow.await<BackupFlowState.Failed>().failure)
        target.assertNoStaging()
        assertTrue(target.backup.passwordsSeen.isEmpty())
    }

    @Test
    fun anOpenPickerAnswerWithNoRunWaitingStartsThatRunAndOneForTheOtherPurposeIsIgnored() = runBlocking<Unit> {
        seed()
        savedArchive("d.gnbk")
        val flow = target.flow
        flow.fileChosen(BackupPurpose.CHECK, "d.gnbk")
        assertEquals(BackupPurpose.CHECK, flow.await<BackupFlowState.EnterPassword>().purpose)
        flow.fileChosen(BackupPurpose.RESTORE, "d.gnbk")
        assertEquals(BackupPurpose.CHECK, assertIs<BackupFlowState.EnterPassword>(flow.state.value).purpose)
    }

    @Test
    fun aRestoreThatAddsRecordingsAsksForADrainAndCannotBeClosed() = runBlocking<Unit> {
        source.queue.enqueue(QueuedCapture("fg_1", "recording_1.m4a", now), ByteArray(3000) { 7 })
        savedArchive("e.gnbk")
        val flow = target.flow
        flow.startRestore()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.RESTORE, "e.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(password.toCharArray())
        flow.await<BackupFlowState.Contents>()
        val gate = CompletableDeferred<Unit>()
        target.backup.holdRestore = gate
        flow.restore()
        flow.await<BackupFlowState.Restoring>()

        flow.close()
        assertIs<BackupFlowState.Restoring>(flow.state.value)
        gate.complete(Unit)
        assertEquals(1, flow.await<BackupFlowState.Restored>().report.capturesAdded)
        withTimeout(5_000) { while (drains == 0) kotlinx.coroutines.delay(10) }
        assertEquals(1, drains)
        assertEquals(listOf("fg_1"), target.queue.readQueue(strict = true).map { it.id })
    }

    @Test
    fun noPickerEndsTheRun() = runBlocking<Unit> {
        target.flow.startCheck()
        target.flow.pickerUnavailable()
        assertEquals(BackupFlowState.Failed(BackupFailure.NoPicker), target.flow.state.value)
    }

    // Failures, unfinished runs and edges

    @Test
    fun tooLittleSpaceToStageEndsABackupOrACopyIn() = runBlocking<Unit> {
        val tight = Side(Files.createTempDirectory("flow-tight").toFile(), minFreeBytes = Long.MAX_VALUE)
        try {
            tight.notes.createNote("work", "a.md", "x", title = "A")
            val chars = password.toCharArray()
            tight.flow.startBackup()
            tight.flow.backUp(chars)
            assertEquals(BackupFailure.NoSpace, tight.flow.await<BackupFlowState.Failed>().failure)
            assertTrue(chars.isZeroed())
            tight.flow.close()

            seed()
            savedArchive("s.gnbk")
            tight.flow.startCheck()
            tight.flow.claimPicker()
            tight.flow.fileChosen(BackupPurpose.CHECK, "s.gnbk")
            assertEquals(BackupFailure.NoSpace, tight.flow.await<BackupFlowState.Failed>().failure)
            tight.assertNoStaging()
        } finally {
            tight.root.deleteRecursively()
        }
    }

    @Test
    fun aSaveIntoADocumentThatHoldsDataIsRefusedAndTheDocumentKept() = runBlocking<Unit> {
        seed()
        val old = byteArrayOf(7, 7, 7)
        docs.files["old.gnbk"] = old
        val chars = password.toCharArray()
        source.flow.startBackup()
        source.flow.backUp(chars)
        source.flow.await<BackupFlowState.SaveTo>()
        source.flow.claimPicker()
        source.flow.documentChosen("old.gnbk")

        assertEquals(BackupFailure.NotSaved(PublishFailure.NOT_EMPTY, "old.gnbk", null), source.flow.await<BackupFlowState.Failed>().failure)
        assertContentEquals(old, docs.files.getValue("old.gnbk"))
        assertTrue(chars.isZeroed())
        assertNull(source.runs.current)
        assertEquals(listOf("old.gnbk"), docs.released)
        source.assertNoStaging()
    }

    @Test
    fun aSaveIsNotedByStageWithAHoldOnItsDocumentAndClearedAtTheEnd() = runBlocking<Unit> {
        seed()
        val gate = CountDownLatch(1)
        source.backup.holdPublish = gate
        source.flow.startBackup()
        source.flow.backUp(password.toCharArray())
        source.flow.await<BackupFlowState.SaveTo>()
        source.flow.claimPicker()
        docs.files["n.gnbk"] = ByteArray(0)
        source.flow.documentChosen("n.gnbk")
        source.flow.await<BackupFlowState.Saving>()
        withTimeout(5_000) { while (source.runs.current == null) kotlinx.coroutines.delay(10) }

        assertEquals(UnfinishedRun.Save("n.gnbk", "n.gnbk", SaveStage.PICKED), source.runs.current)
        assertEquals(listOf("n.gnbk"), docs.held)
        gate.countDown()
        source.flow.await<BackupFlowState.Saved>()

        assertEquals(
            listOf(SaveStage.PICKED, SaveStage.WRITING, SaveStage.VERIFIED).map { UnfinishedRun.Save("n.gnbk", "n.gnbk", it) } + listOf(null),
            source.runs.log.toList(),
        )
        assertEquals(listOf("n.gnbk"), docs.released)
    }

    @Test
    fun noByteIsWrittenWhenTheWritingNoteCannotBeWrittenAndAVerifiedNoteThatFailsClearsTheNote() = runBlocking<Unit> {
        seed()
        source.runs.refuse = { it is UnfinishedRun.Save && it.stage == SaveStage.WRITING }
        source.flow.startBackup()
        source.flow.backUp(password.toCharArray())
        source.flow.await<BackupFlowState.SaveTo>()
        source.flow.claimPicker()
        docs.files["w.gnbk"] = ByteArray(0)
        source.flow.documentChosen("w.gnbk")
        assertEquals(BackupFailure.NotSaved(PublishFailure.NOT_WRITTEN, "w.gnbk", true), source.flow.await<BackupFlowState.Failed>().failure)
        assertFalse("w.gnbk" in docs.files)
        assertNull(source.runs.current)
        source.flow.close()

        source.runs.refuse = { it is UnfinishedRun.Save && it.stage == SaveStage.VERIFIED }
        var noteAfterVerify: UnfinishedRun<String>? = UnfinishedRun.Restore
        source.backup.afterVerified = { noteAfterVerify = source.runs.current }
        source.flow.startBackup()
        source.flow.backUp(password.toCharArray())
        source.flow.await<BackupFlowState.SaveTo>()
        source.flow.claimPicker()
        docs.files["v.gnbk"] = ByteArray(0)
        source.flow.documentChosen("v.gnbk")
        source.flow.await<BackupFlowState.Saved>()
        assertNull(noteAfterVerify)
        assertNull(source.runs.current)
        assertTrue(docs.files.getValue("v.gnbk").isNotEmpty())
    }

    @Test
    fun theNextProcessRemovesWhatASaveWroteAndLeavesWhatItDidNot() = runBlocking<Unit> {
        fun recovered(stage: SaveStage, bytes: ByteArray): Pair<BackupFlowState, Boolean> = runBlocking {
            docs.files["f.gnbk"] = bytes
            source.runs.current = UnfinishedRun.Save("f.gnbk", "f.gnbk", stage)
            val next = source.newFlow()
            next.recoverUnfinished()
            withTimeout(5_000) { while (source.runs.current != null) kotlinx.coroutines.delay(10) }
            val state = withTimeout(5_000) { next.state.first { it !is BackupFlowState.CleaningUp } }
            (state to ("f.gnbk" in docs.files)).also { docs.files.remove("f.gnbk") }
        }
        val old = byteArrayOf(1, 2)
        assertEquals(BackupFlowState.Failed(BackupFailure.Interrupted("f.gnbk", true, written = true)) to false, recovered(SaveStage.WRITING, old))
        assertEquals(BackupFlowState.Failed(BackupFailure.Interrupted("f.gnbk", false, written = false)) to true, recovered(SaveStage.PICKED, old))
        assertEquals(BackupFlowState.Failed(BackupFailure.Interrupted("f.gnbk", true, written = false)) to false, recovered(SaveStage.PICKED, ByteArray(0)))
        assertEquals(BackupFlowState.Idle to true, recovered(SaveStage.VERIFIED, old))
        assertEquals(4, docs.released.size)
    }

    @Test
    fun theNextProcessTidiesWhileBusyAndClearsOnlyTheNoteItRead() = runBlocking<Unit> {
        docs.files["w.gnbk"] = byteArrayOf(1)
        source.runs.current = UnfinishedRun.Save("w.gnbk", "w.gnbk", SaveStage.WRITING)
        val gate = CountDownLatch(1)
        docs.holdDelete = gate
        val next = source.newFlow()
        next.startBackup()

        next.recoverUnfinished()
        withTimeout(5_000) { while (!docs.deleteStarted) kotlinx.coroutines.delay(10) }
        val newer = UnfinishedRun.Save("n.gnbk", "n.gnbk", SaveStage.PICKED)
        source.runs.write(newer)
        gate.countDown()
        withTimeout(5_000) { while (source.runs.clears == 0) kotlinx.coroutines.delay(10) }

        assertFalse("w.gnbk" in docs.files)
        assertEquals(newer, source.runs.current)
        assertEquals(BackupFlowState.NewPassword, next.state.value)
    }

    @Test
    fun theNextProcessReportsARestoreThatWasUnderWayOnce() = runBlocking<Unit> {
        source.runs.current = UnfinishedRun.Restore
        val jobs = SupervisorJob()
        val next = source.newFlow(CoroutineScope(jobs + Dispatchers.IO))

        next.recoverUnfinished()
        assertEquals(BackupFailure.RestoreInterrupted, next.await<BackupFlowState.Failed>().failure)
        assertNull(source.runs.current)
        next.close()
        source.runs.current = UnfinishedRun.Restore
        next.recoverUnfinished()
        withTimeout(5_000) { jobs.children.toList().joinAll() }
        assertEquals(UnfinishedRun.Restore, source.runs.current)
        assertEquals(BackupFlowState.Idle, next.state.value)
    }

    @Test
    fun aDeadRunsPickerAnswerRemovesOnlyAnEmptyDocumentAndIsTidiedQuietlyDuringAnotherRun() = runBlocking<Unit> {
        docs.reportSize = false
        docs.files["unknown-empty.gnbk"] = ByteArray(0)
        source.flow.documentChosen("unknown-empty.gnbk")
        assertEquals(BackupFailure.Interrupted("unknown-empty.gnbk", removed = true, written = false), source.flow.await<BackupFlowState.Failed>().failure)
        source.flow.close()
        docs.files["unknown-full.gnbk"] = byteArrayOf(5)
        source.flow.documentChosen("unknown-full.gnbk")
        assertEquals(BackupFailure.Interrupted("unknown-full.gnbk", removed = false, written = false), source.flow.await<BackupFlowState.Failed>().failure)
        assertTrue("unknown-full.gnbk" in docs.files)
        source.flow.close()
        source.flow.documentChosen("gone.gnbk")
        assertEquals(BackupFailure.Interrupted(null, removed = false, written = false), source.flow.await<BackupFlowState.Failed>().failure)
        source.flow.close()

        docs.reportSize = true
        docs.files["empty.gnbk"] = ByteArray(0)
        source.flow.startBackup()
        source.flow.documentChosen("empty.gnbk")
        withTimeout(5_000) { while ("empty.gnbk" in docs.files) kotlinx.coroutines.delay(10) }
        assertEquals(BackupFlowState.NewPassword, source.flow.state.value)
        source.flow.close()

        docs.failRead = true
        docs.files["sized-empty.gnbk"] = ByteArray(0)
        source.flow.documentChosen("sized-empty.gnbk")
        assertEquals(BackupFailure.Interrupted("sized-empty.gnbk", removed = false, written = false), source.flow.await<BackupFlowState.Failed>().failure)
        assertTrue("sized-empty.gnbk" in docs.files)
        source.flow.close()

        docs.failRead = false
        docs.reportedSize = 1L
        docs.files["said-full.gnbk"] = ByteArray(0)
        source.flow.documentChosen("said-full.gnbk")
        assertEquals(BackupFailure.Interrupted("said-full.gnbk", removed = false, written = false), source.flow.await<BackupFlowState.Failed>().failure)
        assertTrue("said-full.gnbk" in docs.files)
    }

    @Test
    fun aDocumentReportedEmptyThatHoldsBytesIsKeptAfterADeadRunAtTheNextStartAndAfterAFailedSave() = runBlocking<Unit> {
        val held = byteArrayOf(1, 2, 3)
        docs.reportedSize = 0L
        docs.files["earlier.gnbk"] = held
        source.flow.documentChosen("earlier.gnbk")
        assertEquals(BackupFailure.Interrupted("earlier.gnbk", removed = false, written = false), source.flow.await<BackupFlowState.Failed>().failure)
        assertContentEquals(held, docs.files["earlier.gnbk"])
        source.flow.close()

        source.runs.current = UnfinishedRun.Save("earlier.gnbk", "earlier.gnbk", SaveStage.PICKED)
        val next = source.newFlow()
        next.recoverUnfinished()
        withTimeout(5_000) { while (source.runs.current != null) kotlinx.coroutines.delay(10) }
        val recovered = withTimeout(5_000) { next.state.first { it !is BackupFlowState.CleaningUp } }
        assertEquals(BackupFlowState.Failed(BackupFailure.Interrupted("earlier.gnbk", removed = false, written = false)), recovered)
        assertContentEquals(held, docs.files["earlier.gnbk"])
        next.close()

        seed()
        source.backup.failPublishWith = IllegalStateException("odd")
        source.flow.startBackup()
        source.flow.backUp(password.toCharArray())
        source.flow.await<BackupFlowState.SaveTo>()
        source.flow.claimPicker()
        source.flow.documentChosen("earlier.gnbk")
        assertEquals(BackupFailure.NotSaved(PublishFailure.NOT_WRITTEN, "earlier.gnbk", false), source.flow.await<BackupFlowState.Failed>().failure)
        assertContentEquals(held, docs.files["earlier.gnbk"])
    }

    @Test
    fun aSaveThatFailsOutsideThePublishersStepsRemovesTheStillEmptyDocument() = runBlocking<Unit> {
        seed()
        for ((thrown, expected) in listOf(
            IllegalStateException("odd") to BackupFailure.NotSaved(PublishFailure.NOT_WRITTEN, "e.gnbk", true),
            StagingException("staging gone") to BackupFailure.Staging("e.gnbk", true),
        )) {
            source.backup.failPublishWith = thrown
            docs.files["e.gnbk"] = ByteArray(0)
            source.flow.startBackup()
            source.flow.backUp(password.toCharArray())
            source.flow.await<BackupFlowState.SaveTo>()
            source.flow.claimPicker()
            source.flow.documentChosen("e.gnbk")
            assertEquals(expected, source.flow.await<BackupFlowState.Failed>().failure)
            assertFalse("e.gnbk" in docs.files)
            source.assertNoStaging()
            source.flow.close()
        }
    }

    @Test
    fun aRestoreThatStopsIsReportedTidiedAndStillAsksForADrain() = runBlocking<Unit> {
        seed()
        savedArchive("f.gnbk")
        val flow = target.flow
        flow.startRestore()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.RESTORE, "f.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(password.toCharArray())
        flow.await<BackupFlowState.Contents>()
        target.crypto.failSealWhenAadContains = NoteStore.META_FILE

        flow.restore()

        assertEquals(BackupFailure.RestoreStopped(RestoreStop.UNREADABLE), flow.await<BackupFlowState.Failed>().failure)
        assertNull(target.runs.current)
        assertEquals(listOf(UnfinishedRun.Restore, null), target.runs.log.toList())
        target.assertNoStaging()
        withTimeout(5_000) { while (drains == 0) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun closingWhileCopyingOrCheckingEndsTheRunAndZeroesThePassword() = runBlocking<Unit> {
        seed()
        savedArchive("g.gnbk")
        val flow = target.flow
        val copying = CompletableDeferred<Unit>()
        target.backup.holdStage = copying
        flow.startCheck()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.CHECK, "g.gnbk")
        flow.await<BackupFlowState.Copying>()
        flow.close()
        assertEquals(BackupFlowState.Idle, flow.state.value)
        copying.complete(Unit)
        target.assertNoStaging()

        target.backup.holdStage = null
        target.backup.holdUnlock = CompletableDeferred()
        flow.startCheck()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.CHECK, "g.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        val chars = password.toCharArray()
        flow.open(chars)
        flow.await<BackupFlowState.Checking>()
        flow.close()
        assertTrue(chars.isZeroed())
        assertEquals(BackupFlowState.Idle, flow.state.value)
        target.assertNoStaging()
    }

    @Test
    fun pickerAnswersTheFlowIsNotWaitingForChangeNothingAndBackingOutOfTheOpenPickerEndsTheRun() = runBlocking<Unit> {
        docs.files["x.gnbk"] = ByteArray(0)
        source.flow.startRestore()
        source.flow.fileChosen(BackupPurpose.RESTORE, "x.gnbk")
        assertEquals(BackupFlowState.PickFile(BackupPurpose.RESTORE, opened = false), source.flow.state.value)
        source.flow.claimPicker()
        source.flow.fileChosen(BackupPurpose.CHECK, "x.gnbk")
        assertEquals(BackupFlowState.PickFile(BackupPurpose.RESTORE, opened = true), source.flow.state.value)
        source.flow.fileChosen(BackupPurpose.RESTORE, null)
        assertEquals(BackupFlowState.Idle, source.flow.state.value)
    }

    @Test
    fun aDamagedArchiveIsRefused() = runBlocking<Unit> {
        source.notes.createNote("work", "big.md", "x".repeat(200_000), title = "Big")
        val bytes = savedArchive("h.gnbk")
        docs.files["cut.gnbk"] = bytes.copyOf(bytes.size - 1)
        val flow = target.flow
        flow.startCheck()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.CHECK, "cut.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(password.toCharArray())
        assertEquals(BackupFailure.NotOpened(BackupProblem.DAMAGED), flow.await<BackupFlowState.Failed>().failure)
        target.assertNoStaging()
    }

    @Test
    fun aPickerAnswerTheActivityLostEndsTheRunAsBackedOutWhenTheHostReturns() = runBlocking<Unit> {
        seed()
        val chars = password.toCharArray()
        source.flow.startBackup()
        source.flow.backUp(chars)
        source.flow.await<BackupFlowState.SaveTo>()
        source.flow.returned()
        assertIs<BackupFlowState.SaveTo>(source.flow.state.value)
        source.flow.claimPicker()

        source.flow.returned()

        assertEquals(BackupFlowState.Idle, source.flow.state.value)
        assertTrue(chars.isZeroed())
        source.assertNoStaging()
        source.flow.startCheck()
        source.flow.claimPicker()
        source.flow.returned()
        assertEquals(BackupFlowState.Idle, source.flow.state.value)
    }

    @Test
    fun aBuildOrCopyThatFinishesAfterItsRunEndedDeletesWhatItStaged() = runBlocking<Unit> {
        seed()
        val built = CompletableDeferred<Unit>()
        source.backup.holdAfterCreate = built
        source.flow.startBackup()
        source.flow.backUp(password.toCharArray())
        withTimeout(20_000) { source.backup.afterCreateReached.await() }
        source.flow.close()
        built.complete(Unit)
        source.assertNoStaging()
        source.backup.holdAfterCreate = null

        savedArchive("late.gnbk")
        val copied = CompletableDeferred<Unit>()
        target.backup.holdAfterStage = copied
        target.flow.startCheck()
        target.flow.claimPicker()
        target.flow.fileChosen(BackupPurpose.CHECK, "late.gnbk")
        withTimeout(20_000) { target.backup.afterStageReached.await() }
        target.flow.close()
        copied.complete(Unit)
        target.assertNoStaging()
        assertEquals(BackupFlowState.Idle, target.flow.state.value)
    }

    @Test
    fun aRestoreStopIsNamedByWhatStoppedIt() = runBlocking<Unit> {
        seed()
        savedArchive("s.gnbk")
        for ((thrown, reason) in listOf(
            InvalidBackupException(BackupProblem.DAMAGED, "changed") to RestoreStop.FILE,
            StagingException("staging") to RestoreStop.STAGING,
            com.anopticlabs.gravel.notebook.storage.KeyUnavailableException("gone") to RestoreStop.KEY_MISSING,
            com.anopticlabs.gravel.notebook.storage.CorruptFileException("bad", RuntimeException()) to RestoreStop.DAMAGED,
        )) {
            target.backup.failRestoreWith = thrown
            target.flow.startRestore()
            target.flow.claimPicker()
            target.flow.fileChosen(BackupPurpose.RESTORE, "s.gnbk")
            target.flow.await<BackupFlowState.EnterPassword>()
            target.flow.open(password.toCharArray())
            target.flow.await<BackupFlowState.Contents>()
            target.flow.restore()
            assertEquals(BackupFailure.RestoreStopped(reason), target.flow.await<BackupFlowState.Failed>().failure)
            target.flow.close()
        }
    }

    @Test
    fun noPickerAtTheSaveStepDropsTheStagedArchiveAndThePassword() = runBlocking<Unit> {
        seed()
        val chars = password.toCharArray()
        source.flow.startBackup()
        source.flow.backUp(chars)
        source.flow.await<BackupFlowState.SaveTo>()

        source.flow.pickerUnavailable()

        assertEquals(BackupFlowState.Failed(BackupFailure.NoPicker), source.flow.state.value)
        assertTrue(chars.isZeroed())
        source.assertNoStaging()
    }

    @Test
    fun aPasswordHandedInOutOfTurnIsZeroed() = runBlocking<Unit> {
        val forOpen = password.toCharArray()
        source.flow.open(forOpen)
        assertTrue(forOpen.isZeroed())
        val forBackup = password.toCharArray()
        source.flow.backUp(forBackup)
        assertTrue(forBackup.isZeroed())
        assertEquals(BackupFlowState.Idle, source.flow.state.value)
    }

    // Automatic backups

    private val recoveryKey = "0123456789abcdefghijklmnopqrstuvwxyz".repeat(2).take(64)

    /** The automatic backup [name] as the store records it, with the SHA-256 of the file now there. */
    private fun auto(name: String, key: CharArray) =
        AutomaticRestore(name, name, key, docs.files[name]?.let { MessageDigest.getInstance("SHA-256").digest(it).toHex() }.orEmpty())

    private suspend fun automaticArchive(name: String): ByteArray {
        seed()
        val bytes = source.backup.real.createAutomatic(recoveryKey.toCharArray()).use { it.source.open().use { s -> s.readBytes() } }
        docs.files[name] = bytes
        return bytes
    }

    @Test
    fun anAutomaticBackupAsksForItsRecoveryKeyAndARestoreWithItOffersTheKeyForAutomaticBackups() = runBlocking<Unit> {
        automaticArchive("auto.gnbk")
        val taken = CopyOnWriteArrayList<String>()
        val flow = BackupFlow(
            target.backup, docs, target.runs, requestDrain = {}, clock = { now }, zone = { TimeZone.UTC },
            canTakeKey = { true }, takeKey = { taken += it.concatToString(); it.fill('\u0000') },
        )
        flow.startRestore()
        assertTrue(flow.claimPicker())
        flow.fileChosen(BackupPurpose.RESTORE, "auto.gnbk")
        assertEquals(BackupFlowState.EnterPassword(BackupPurpose.RESTORE, "auto.gnbk", wrong = false, recoveryKey = true), flow.await<BackupFlowState.EnterPassword>())
        flow.open(password.toCharArray())
        withTimeout(20_000) { flow.state.first { it == BackupFlowState.EnterPassword(BackupPurpose.RESTORE, "auto.gnbk", wrong = true, recoveryKey = true) } }

        val typed = RecoveryKey.display(recoveryKey.toCharArray()).toCharArray()
        flow.open(typed)
        flow.await<BackupFlowState.Contents>()
        assertTrue(typed.isZeroed())
        flow.restore()
        val restored = flow.await<BackupFlowState.Restored>()
        assertTrue(restored.offerKey)
        assertEquals(1, restored.report.notesWritten)
        flow.useKeyForAutomaticBackups()
        assertEquals(BackupFlowState.Idle, flow.state.value)
        assertEquals(listOf(recoveryKey), taken.toList())
        flow.useKeyForAutomaticBackups()
        assertEquals(1, taken.size)
    }

    @Test
    fun noOfferWithoutPermissionOrForACheckOrAPasswordBackup() = runBlocking<Unit> {
        automaticArchive("auto.gnbk")
        val taken = CopyOnWriteArrayList<String>()
        val flow = BackupFlow(
            target.backup, docs, target.runs, requestDrain = {}, clock = { now }, zone = { TimeZone.UTC },
            canTakeKey = { false }, takeKey = { taken += it.concatToString() },
        )
        flow.startRestore()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.RESTORE, "auto.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(recoveryKey.toCharArray())
        flow.await<BackupFlowState.Contents>()
        flow.restore()
        assertFalse(flow.await<BackupFlowState.Restored>().offerKey)
        flow.useKeyForAutomaticBackups()
        assertIs<BackupFlowState.Restored>(flow.state.value)
        flow.close()

        flow.startCheck()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.CHECK, "auto.gnbk")
        assertTrue(flow.await<BackupFlowState.EnterPassword>().recoveryKey)
        flow.open(recoveryKey.toCharArray())
        assertEquals(BackupPurpose.CHECK, flow.await<BackupFlowState.Contents>().purpose)
        flow.close()

        savedArchive("manual.gnbk")
        val offering = BackupFlow(
            target.backup, docs, target.runs, requestDrain = {}, clock = { now }, zone = { TimeZone.UTC },
            canTakeKey = { true }, takeKey = { taken += it.concatToString() },
        )
        offering.startRestore()
        offering.claimPicker()
        offering.fileChosen(BackupPurpose.RESTORE, "manual.gnbk")
        assertFalse(offering.await<BackupFlowState.EnterPassword>().recoveryKey)
        offering.open(password.toCharArray())
        offering.await<BackupFlowState.Contents>()
        offering.restore()
        assertFalse(offering.await<BackupFlowState.Restored>().offerKey)
        assertTrue(taken.isEmpty())
    }

    @Test
    fun theRecoveryKeyARestoreOpenedWithIsZeroedUnlessTheOfferHoldsIt() = runBlocking<Unit> {
        automaticArchive("auto.gnbk")
        val cases = listOf<Triple<suspend () -> Boolean, Exception?, Boolean>>(
            Triple({ true }, null, true),
            Triple({ false }, null, false),
            Triple({ error("permission unknown") }, null, false),
            Triple({ true }, IllegalStateException("stopped"), false),
        )
        for ((canTake, failure, offered) in cases) {
            target.backup.failRestoreWith = failure
            val flow = BackupFlow(target.backup, docs, target.runs, requestDrain = {}, clock = { now }, zone = { TimeZone.UTC }, canTakeKey = canTake, takeKey = {})
            flow.startRestore()
            flow.claimPicker()
            flow.fileChosen(BackupPurpose.RESTORE, "auto.gnbk")
            flow.await<BackupFlowState.EnterPassword>()
            flow.open(recoveryKey.toCharArray())
            flow.await<BackupFlowState.Contents>()
            val held = BackupFlow::class.java.getDeclaredField("recoveryKey").apply { isAccessible = true }.get(flow) as CharArray
            assertFalse(held.isZeroed())
            flow.restore()
            if (failure == null) assertEquals(offered, flow.await<BackupFlowState.Restored>().offerKey) else flow.await<BackupFlowState.Failed>()
            assertEquals(!offered, held.isZeroed())
            flow.close()
            assertTrue(held.isZeroed())
        }
    }

    @Test
    fun buildingAndRestoringWaitForTheGate() = runBlocking<Unit> {
        seed()
        val gate = BackupGate()
        val flow = BackupFlow(source.backup, docs, source.runs, requestDrain = {}, clock = { now }, zone = { TimeZone.UTC }, gate = gate)
        assertTrue(gate.tryLock())
        flow.startBackup()
        flow.backUp(password.toCharArray())
        kotlinx.coroutines.delay(200)
        assertIs<BackupFlowState.Building>(flow.state.value)
        assertTrue(source.backup.passwordsSeen.isEmpty())
        gate.unlock()
        flow.await<BackupFlowState.SaveTo>()
        flow.close()

        savedArchive("g.gnbk")
        val restoring = BackupFlow(target.backup, docs, target.runs, requestDrain = {}, clock = { now }, zone = { TimeZone.UTC }, gate = gate)
        restoring.startRestore()
        restoring.claimPicker()
        restoring.fileChosen(BackupPurpose.RESTORE, "g.gnbk")
        restoring.await<BackupFlowState.EnterPassword>()
        restoring.open(password.toCharArray())
        restoring.await<BackupFlowState.Contents>()
        assertTrue(gate.tryLock())
        restoring.restore()
        kotlinx.coroutines.delay(200)
        assertTrue(target.backup.restoredWith.isEmpty())
        gate.unlock()
        restoring.await<BackupFlowState.Restored>()
    }

    // Restores the storage card starts

    private val plan = RecoveryPlan.StartFresh(setOf(NotebookPart.REMINDERS))

    /** A flow on [target] whose plans are recorded with the notes the stores held when each ran. */
    private inner class Recovering(locked: Boolean, step: () -> Unit = {}, found: () -> AutomaticRestore<String>?) {
        val prepared = CopyOnWriteArrayList<Pair<RecoveryPlan, Int>>()
        val flow = BackupFlow(
            target.backup, docs, target.runs, requestDrain = {}, clock = { now }, zone = { TimeZone.UTC },
            prepareRestore = {
                step()
                prepared += it to target.notes.readMetadata(strict = true).size
            },
            automaticBackup = { found() },
            screenLocked = { locked },
        )
    }

    @Test
    fun aRestoreFromAFileCarriesItsPlanAndRunsItOnceBeforeAnythingIsAdded() = runBlocking<Unit> {
        seed()
        savedArchive("p.gnbk")
        val recovering = Recovering(locked = true) { null }
        val flow = recovering.flow
        flow.startRestore(plan)
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.RESTORE, "p.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(password.toCharArray())
        assertEquals(plan, flow.await<BackupFlowState.Contents>().plan)
        assertTrue(recovering.prepared.isEmpty())
        flow.restore()
        assertEquals(1, flow.await<BackupFlowState.Restored>().report.notesWritten)
        assertEquals(listOf(plan to 0), recovering.prepared.toList())

        flow.close()
        flow.startRestore()
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.RESTORE, "p.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(password.toCharArray())
        assertNull(flow.await<BackupFlowState.Contents>().plan)
        flow.restore()
        flow.await<BackupFlowState.Restored>()
        assertEquals(1, recovering.prepared.size)
    }

    @Test
    fun aRestoreOfTheAutomaticBackupWaitsForTheScreenLockAndOpensWithTheStoredKey() = runBlocking<Unit> {
        automaticArchive("auto.gnbk")
        val key = recoveryKey.toCharArray()
        val recovering = Recovering(locked = true) { auto("auto.gnbk", key) }
        val flow = recovering.flow
        flow.startAutomaticRestore(plan)
        assertEquals(BackupFlowState.ConfirmLock(opened = false), flow.state.value)
        val prompt = assertNotNull(flow.claimLockPrompt())
        assertNull(flow.claimLockPrompt())
        assertEquals(BackupFlowState.ConfirmLock(opened = true), flow.state.value)
        assertTrue(target.backup.unlocked.isEmpty())

        flow.lockAnswered(prompt + 1, LockAnswer.CONFIRMED)
        assertEquals(BackupFlowState.ConfirmLock(opened = true), flow.state.value)
        flow.lockAnswered(prompt, LockAnswer.CONFIRMED)
        val contents = flow.await<BackupFlowState.Contents>()
        assertEquals(plan, contents.plan)
        assertEquals("auto.gnbk", contents.fileName)
        assertTrue(key.isZeroed())
        flow.restore()
        val restored = flow.await<BackupFlowState.Restored>()
        assertFalse(restored.offerKey)
        assertEquals(listOf(plan to 0), recovering.prepared.toList())
    }

    @Test
    fun withoutAScreenLockTheAutomaticBackupOpensAtOnce() = runBlocking<Unit> {
        automaticArchive("auto.gnbk")
        val flow = Recovering(locked = false) { auto("auto.gnbk", recoveryKey.toCharArray()) }.flow
        flow.startAutomaticRestore(plan)
        assertEquals(plan, flow.await<BackupFlowState.Contents>().plan)
    }

    @Test
    fun aCanceledLockEndsTheRunAndOneAndroidCouldNotAskForFails() = runBlocking<Unit> {
        var asked = 0
        val flow = Recovering(locked = true) { asked++; null }.flow
        flow.startAutomaticRestore(plan)
        flow.lockAnswered(assertNotNull(flow.claimLockPrompt()), LockAnswer.CANCELED)
        assertEquals(BackupFlowState.Idle, flow.state.value)

        flow.startAutomaticRestore(plan)
        flow.lockAnswered(assertNotNull(flow.claimLockPrompt()), LockAnswer.FAILED)
        assertEquals(BackupFlowState.Failed(BackupFailure.LockFailed, filePlan = plan), flow.state.value)
        assertEquals(0, asked)
    }

    @Test
    fun aLockStateThatCannotBeReadAsksForTheLock() = runBlocking<Unit> {
        val flow = BackupFlow(
            target.backup, docs, target.runs, requestDrain = {}, clock = { now }, zone = { TimeZone.UTC },
            automaticBackup = { error("not before the lock") }, screenLocked = { throw IllegalStateException("unreadable") },
        )
        flow.startAutomaticRestore(plan)
        assertEquals(BackupFlowState.ConfirmLock(opened = false), flow.state.value)
    }

    @Test
    fun aPromptThatNeverAnsweredGivesWayToANewStart() = runBlocking<Unit> {
        val flow = Recovering(locked = true) { null }.flow
        flow.startAutomaticRestore(plan)
        flow.claimLockPrompt()
        flow.startRestore()
        assertEquals(BackupFlowState.PickFile(BackupPurpose.RESTORE, opened = false), flow.state.value)
    }

    @Test
    fun anAutomaticBackupThatCannotBeOpenedOffersAFileWithTheSamePlan() = runBlocking<Unit> {
        seed()
        savedArchive("p.gnbk")
        automaticArchive("auto.gnbk")
        var found: AutomaticRestore<String>? = null
        val flow = Recovering(locked = false) { found }.flow
        flow.startAutomaticRestore(plan)
        assertEquals(BackupFlowState.Failed(BackupFailure.NoAutomaticBackup, filePlan = plan), flow.await<BackupFlowState.Failed>())

        flow.close()
        val wrongKey = "z".repeat(64).toCharArray()
        found = auto("auto.gnbk", wrongKey)
        flow.startAutomaticRestore(plan)
        val failed = flow.await<BackupFlowState.Failed>()
        assertEquals(BackupFlowState.Failed(BackupFailure.NotOpened(BackupProblem.WRONG_PASSWORD_OR_DAMAGED), filePlan = plan), failed)
        assertTrue(wrongKey.isZeroed())

        flow.pickFileInstead()
        assertEquals(BackupFlowState.PickFile(BackupPurpose.RESTORE, opened = false), flow.state.value)
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.RESTORE, "p.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(password.toCharArray())
        assertEquals(plan, flow.await<BackupFlowState.Contents>().plan)
        flow.close()
        target.assertNoStaging()
    }

    @Test
    fun aPlanEndsWithItsRunAndAKeyIsWipedWhenTheBackupCannotBeStaged() = runBlocking<Unit> {
        seed()
        savedArchive("p.gnbk")
        val missingKey = recoveryKey.toCharArray()
        val flow = Recovering(locked = false) { auto("not there", missingKey) }.flow
        flow.startAutomaticRestore(plan)
        assertEquals(BackupFlowState.Failed(BackupFailure.FileUnreadable, filePlan = plan), flow.await<BackupFlowState.Failed>())
        assertTrue(missingKey.isZeroed())
        flow.close()

        flow.fileChosen(BackupPurpose.RESTORE, "p.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(password.toCharArray())
        assertNull(flow.await<BackupFlowState.Contents>().plan)
        flow.close()
    }

    @Test
    fun anAutomaticBackupThatIsNotTheFileWrittenIsRefusedAsDamaged() = runBlocking<Unit> {
        automaticArchive("auto.gnbk")
        val key = recoveryKey.toCharArray()
        val flow = Recovering(locked = false) { AutomaticRestore("auto.gnbk", "auto.gnbk", key, "00".repeat(32)) }.flow
        flow.startAutomaticRestore(plan)
        assertEquals(BackupFlowState.Failed(BackupFailure.NotOpened(BackupProblem.DAMAGED), filePlan = plan), flow.await<BackupFlowState.Failed>())
        assertTrue(key.isZeroed())
        assertTrue(target.backup.unlocked.isEmpty())
        flow.close()
        target.assertNoStaging()
    }

    @Test
    fun aKeyStoreFailureInThePlanStopsTheRestoreBeforeAnythingIsAdded() = runBlocking<Unit> {
        seed()
        savedArchive("p.gnbk")
        val flow = Recovering(locked = true, step = { throw KeyStoreFailureException() }) { null }.flow
        flow.startRestore(plan)
        flow.claimPicker()
        flow.fileChosen(BackupPurpose.RESTORE, "p.gnbk")
        flow.await<BackupFlowState.EnterPassword>()
        flow.open(password.toCharArray())
        flow.await<BackupFlowState.Contents>()
        flow.restore()
        assertEquals(BackupFailure.RestoreStopped(RestoreStop.KEY_STORE), flow.await<BackupFlowState.Failed>().failure)
        assertTrue(target.notes.readMetadata(strict = true).isEmpty())
    }
}
