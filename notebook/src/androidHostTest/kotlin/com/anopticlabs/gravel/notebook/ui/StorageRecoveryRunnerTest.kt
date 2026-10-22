package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.KeyReset
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.NotebookPart
import com.anopticlabs.gravel.notebook.storage.NotebookRecovery
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.RecoveryPlan
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageRecoveryRunnerTest {
    private val root = Files.createTempDirectory("notebook-runner").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val hold = CompletableDeferred<Unit>()
    private var holding = false
    private var keyStoreWorks = true
    private val resets = AtomicInteger()
    private val recovery = NotebookRecovery(
        root, NoteStore(root, crypto), QueueStore(root, crypto), ReminderStore(root, crypto), ReminderEventStore(root, crypto),
        VoiceCompletionStore(root, crypto),
        resetKey = { force -> if (keyStoreWorks || force) KeyReset.DELETED else KeyReset.KEPT },
        keyAbsent = { false },
        afterReset = {
            resets.incrementAndGet()
            if (holding) hold.await()
        },
    )
    private val fromFile = mutableListOf<RecoveryPlan>()
    private val automatic = mutableListOf<RecoveryPlan>()
    private val job = Job()
    private val runner = StorageRecoveryRunner(recovery, restoreFile = { fromFile += it }, restoreAutomatic = { automatic += it }, CoroutineScope(job + Dispatchers.IO))
    private val reminders = StorageTrouble.Damaged(setOf(NotebookPart.REMINDERS))

    @AfterTest
    fun cleanUp() {
        job.cancel()
        root.deleteRecursively()
    }

    private fun corrupt(name: String) = File(root, name).writeBytes(ByteArray(96) { 7 })

    private suspend fun settled() = job.children.toList().joinAll()

    @Test
    fun aDamagedPartRestoresFromTheAutomaticBackupWhenThereIsOneAndAMissingKeyAlwaysFromAFile() {
        runner.restore(reminders, automaticBackupAt = 5L)
        runner.restore(reminders, automaticBackupAt = null)
        runner.restore(StorageTrouble.KeyGone, automaticBackupAt = 5L)
        runner.restore(StorageTrouble.Unreadable, automaticBackupAt = 5L)
        assertEquals(listOf<RecoveryPlan>(RecoveryPlan.StartFresh(reminders.parts)), automatic)
        assertEquals(listOf(RecoveryPlan.StartFresh(reminders.parts), RecoveryPlan.StartOver), fromFile)
    }

    @Test
    fun oneActionRunsAtATime() = runBlocking<Unit> {
        corrupt(ReminderStore.FILE_NAME)
        holding = true
        runner.startFresh(reminders)
        withTimeout(5_000) { while (resets.get() == 0) kotlinx.coroutines.delay(10) }
        assertTrue(runner.state.value.busy)
        corrupt(VoiceCompletionStore.FILE_NAME)
        runner.startFresh(StorageTrouble.Damaged(setOf(NotebookPart.COMPLETIONS)))
        hold.complete(Unit)
        settled()
        assertEquals(RecoveryRunState(runs = 1), runner.state.value)
        assertEquals(1, resets.get())
        assertEquals(StorageTrouble.Damaged(setOf(NotebookPart.COMPLETIONS)), recovery.diagnose())
    }

    @Test
    fun aFailureIsReportedForTheTroubleItWasForAndAKeyStoreFailureByName() = runBlocking<Unit> {
        crypto.failOpenWithTransient = true
        corrupt(ReminderStore.FILE_NAME)
        runner.startFresh(reminders)
        settled()
        assertEquals(RecoveryRunState(failed = RecoveryFailure.NOT_FINISHED, failedOn = reminders, runs = 1), runner.state.value)

        crypto.failOpenWithTransient = false
        crypto.failOpenWithKeyUnavailable = true
        keyStoreWorks = false
        runner.startOver()
        settled()
        assertEquals(RecoveryRunState(failed = RecoveryFailure.KEY_STORE, failedOn = StorageTrouble.KeyGone, runs = 2), runner.state.value)

        runner.startOver(force = true)
        settled()
        assertNull(runner.state.value.failed)
        assertFalse(runner.state.value.busy)
    }

    @Test
    fun aFailureLineGoesOnceAStatusReadNoLongerFindsItsKindOfTrouble() = runBlocking<Unit> {
        crypto.failOpenWithTransient = true
        corrupt(ReminderStore.FILE_NAME)
        runner.startFresh(reminders)
        settled()
        runner.seen(StorageTrouble.Damaged(setOf(NotebookPart.FOLDERS)))
        assertEquals(RecoveryFailure.NOT_FINISHED, runner.state.value.failed)
        runner.seen(null)
        assertEquals(RecoveryRunState(runs = 1), runner.state.value)

        runner.startFresh(reminders)
        settled()
        runner.seen(StorageTrouble.Unreadable)
        assertNull(runner.state.value.failedOn)
    }

    @Test
    fun aStartFreshListsWhatItEmptiesApartFromWhatItRebuildsAndBackupsLackCompletions() {
        val (lost, rebuilt) = lostAndRebuilt(setOf(NotebookPart.RECORDINGS, NotebookPart.FOLDER_TAGS, NotebookPart.NOTE_LIST, NotebookPart.REMINDERS))
        assertEquals(listOf(NotebookPart.FOLDER_TAGS, NotebookPart.REMINDERS), lost)
        assertEquals(listOf(NotebookPart.NOTE_LIST, NotebookPart.RECORDINGS), rebuilt)
        assertEquals(setOf(NotebookPart.COMPLETIONS), NotebookPart.entries.filterNot { it.inBackups }.toSet())
    }
}
