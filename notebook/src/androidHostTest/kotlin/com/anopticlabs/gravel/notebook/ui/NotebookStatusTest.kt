package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.capture.FailedCapture
import com.anopticlabs.gravel.notebook.capture.MAX_QUEUE_ATTEMPTS
import com.anopticlabs.gravel.notebook.capture.ProcessingPause
import com.anopticlabs.gravel.notebook.capture.ProcessingRules
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.pipeline.CapturePublisher
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import com.anopticlabs.gravel.notebook.storage.CorruptFileException
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.KeyUnavailableException
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.NotebookPart
import com.anopticlabs.gravel.notebook.storage.KeyReset
import com.anopticlabs.gravel.notebook.storage.NotebookRecovery
import com.anopticlabs.gravel.notebook.storage.PersistedFormatException
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import com.anopticlabs.gravel.notebook.storage.TransientCryptoException
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookStatusTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val queue = QueueStore(root, crypto)
    private val notes = NoteStore(root, crypto)
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val completions = VoiceCompletionStore(root, crypto)
    private val notifier = FakeNotifier()
    private val now = 1_790_089_200_000L

    private val publisher = CapturePublisher(
        notes, reminders, completions,
        ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), ReminderRefreshSignal(), { now }, { TimeZone.UTC }),
        notifier, ReminderRefreshSignal(), { 30 }, { true }, { now }, { "id" },
    )
    private val recovery = NotebookRecovery(root, notes, queue, reminders, events, completions, resetKey = { KeyReset.DELETED }, keyAbsent = { false }, afterReset = {})
    private var automaticAt: Long? = null
    private val reader = NotebookStatusReader(queue, publisher, recovery, automaticBackupAt = { automaticAt })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun enqueue(id: String, attempts: Int = 0, flagged: Boolean = false, durationMillis: Long? = null, at: Long = now, failure: String? = null) {
        queue.enqueue(QueuedCapture(id, "$id.m4a", at, attempts, flagged, durationMillis = durationMillis, failure = failure), byteArrayOf(1))
    }

    private val spent = FailedCapture("spent", now, null, CaptureFailureReason.TRIES_RAN_OUT)

    @Test
    fun emptyStoresReadAsNothingToShowAndClearTheCompletionsNudge() = runBlocking<Unit> {
        notifier.completionsCount = 2
        assertEquals(NotebookStatus(), reader.read(emptySet()))
        assertNull(notifier.completionsCount)
    }

    @Test
    fun capturesCountAsWaitingOrFlaggedAndInFlightOnesAsNeither() = runBlocking<Unit> {
        enqueue("waiting")
        enqueue("retried", attempts = MAX_QUEUE_ATTEMPTS - 1)
        enqueue("spent", attempts = MAX_QUEUE_ATTEMPTS)
        enqueue("flagged", attempts = 1, flagged = true, durationMillis = 4_000, at = now + 1, failure = "no_speech")
        enqueue("running")
        // A capture on its last attempt is flagged while it runs, and is not listed.
        enqueue("last", attempts = MAX_QUEUE_ATTEMPTS)
        enqueue("gone", attempts = MAX_QUEUE_ATTEMPTS, at = now - 1)
        File(root, "${QueueStore.QUEUE_DIR}/gone${QueueStore.BLOB_EXTENSION}").delete()
        val flagged = FailedCapture("flagged", now + 1, 4_000, CaptureFailureReason.NO_SPEECH)
        val gone = FailedCapture("gone", now - 1, null, CaptureFailureReason.DAMAGED, retryable = false)
        // Captures of unknown length count as long; the failed list is newest first.
        assertEquals(NotebookStatus(waiting = 2, failed = listOf(flagged, spent, gone), longToRun = true), reader.read(setOf("running", "last")))
    }

    @Test
    fun theChargerRuleCountsTheLongCapturesItHoldsAndNothingElse() = runBlocking<Unit> {
        enqueue("long")
        enqueue("short", durationMillis = 5_000)
        enqueue("spent", attempts = MAX_QUEUE_ATTEMPTS)
        val unplugged = ProcessingRules(longWaitsForPower = true, pluggedIn = false)
        fun read(rules: ProcessingRules, inFlight: Set<String> = emptySet()) =
            runBlocking { NotebookStatusReader(queue, publisher, recovery, rules = { rules }).read(inFlight) }
        // The waiting line leaves out what the plugged-in line counts.
        assertEquals(NotebookStatus(waiting = 1, failed = listOf(spent), waitingForPower = setOf("long")), read(unplugged))
        // A long capture still running after the phone is unplugged is neither, and Pause long can still stop it.
        assertEquals(NotebookStatus(waiting = 1, failed = listOf(spent), longToRun = true), read(unplugged, inFlight = setOf("long")))
        assertEquals(NotebookStatus(waiting = 2, failed = listOf(spent), longToRun = true), read(unplugged.copy(approved = setOf("long"))))
        assertEquals(NotebookStatus(waiting = 2, failed = listOf(spent), longToRun = true), read(unplugged.copy(pluggedIn = true)))
        // A pause holds the long capture instead; it is not counted as waiting for power.
        assertEquals(NotebookStatus(waiting = 2, failed = listOf(spent)), read(unplugged.copy(pause = ProcessingPause.LONG)))
    }

    @Test
    fun parkedCompletionsAreCountedAndKeepTheirNudge() = runBlocking<Unit> {
        completions.save(VoiceCompletion("c1", "i took the bins out", now))
        notifier.completionsCount = 1
        assertEquals(NotebookStatus(completionsToReview = 1), reader.read(emptySet()))
        assertEquals(1, notifier.completionsCount)
    }

    @Test
    fun aMissingKeyIsReportedAsSuchWithNoAutomaticBackupToRestore() = runBlocking<Unit> {
        enqueue("a")
        automaticAt = now - 1_000
        crypto.failOpenWithKeyUnavailable = true
        assertEquals(NotebookStatus(trouble = StorageTrouble.KeyGone), reader.read(emptySet()))
        File(root, "${QueueStore.PENDING_DIR}/b~b.m4a").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertEquals(NotebookStatus(retained = 1, trouble = StorageTrouble.KeyGone), reader.read(emptySet()))
    }

    @Test
    fun aDamagedStoreFileIsReportedWithItsPartAndTheAutomaticBackupARestoreWouldUse() = runBlocking<Unit> {
        enqueue("a")
        File(root, QueueStore.QUEUE_META_FILE).writeBytes(ByteArray(64) { 7 })
        automaticAt = now - 1_000
        assertEquals(NotebookStatus(trouble = StorageTrouble.Damaged(setOf(NotebookPart.RECORDINGS)), automaticBackupAt = now - 1_000), reader.read(emptySet()))
    }

    @Test
    fun aDamagedReminderHistoryReachesTheCard() = runBlocking<Unit> {
        File(root, ReminderEventStore.FILE_NAME).writeBytes(ByteArray(64) { 7 })
        assertEquals(NotebookStatus(trouble = StorageTrouble.Damaged(setOf(NotebookPart.REMINDER_HISTORY))), reader.read(emptySet()))
    }

    @Test
    fun aTransientFailureIsReportedAsUnreadableAndLeavesTheNudge() = runBlocking<Unit> {
        enqueue("a")
        notifier.completionsCount = 1
        crypto.failOpenWithTransient = true
        assertEquals(NotebookStatus(trouble = StorageTrouble.Unreadable), reader.read(emptySet()))
        assertEquals(1, notifier.completionsCount)
    }

    @Test
    fun eachFailureMapsToItsProblem() {
        assertEquals(StorageProblem.KEY_MISSING, storageProblemOf(IllegalStateException("wrapped", KeyUnavailableException("gone"))))
        assertEquals(StorageProblem.DAMAGED, storageProblemOf(CorruptFileException("bad", RuntimeException())))
        assertEquals(StorageProblem.DAMAGED, storageProblemOf(IllegalStateException("wrapped", PersistedFormatException("queue_meta", RuntimeException()))))
        assertEquals(StorageProblem.UNREADABLE, storageProblemOf(TransientCryptoException("busy", RuntimeException())))
        assertEquals(StorageProblem.UNREADABLE, storageProblemOf(IOException("disk")))
    }

    @Test
    fun onlySettledRowsWithSomethingToOpenLeadToASection() {
        fun item(status: CaptureQueueItem.Status) = CaptureQueueItem("a", now, status)
        assertEquals(NotebookSection.NOTES, sectionFor(item(CaptureQueueItem.Status.NOTE_CREATED)))
        assertEquals(NotebookSection.REMINDERS, sectionFor(item(CaptureQueueItem.Status.REMINDER_SET)))
        listOf(CaptureQueueItem.Status.QUEUED, CaptureQueueItem.Status.PROCESSING, CaptureQueueItem.Status.FAILED)
            .forEach { assertNull(sectionFor(item(it)), it.name) }
    }
}

class SecureHoldsTest {

    private val secure = mutableMapOf<String, Boolean>()
    private val waiting = mutableListOf<Pair<Int, () -> Unit>>()
    private val holds = SecureHolds<String>(
        isSecure = { secure[it] == true },
        setSecure = { window, on -> secure[window] = on },
        afterFrames = { frames, action -> waiting += frames to action },
    )

    /** Runs one frame: every action whose wait ends now runs, the rest wait one frame less. */
    private fun frame() {
        val due = waiting.filter { it.first <= 1 }.map { it.second }
        val rest = waiting.filter { it.first > 1 }.map { it.first - 1 to it.second }
        waiting.clear()
        waiting += rest
        due.forEach { it() }
    }

    @Test
    fun theFlagOutlastsTheLastHoldByTheHoldFrames() {
        holds.acquire("w")
        holds.acquire("w")
        holds.release("w")
        repeat(NotebookWindowSecurity.HOLD_FRAMES + 1) { frame() }
        assertEquals(true, secure["w"])
        holds.release("w")
        repeat(NotebookWindowSecurity.HOLD_FRAMES - 1) { frame() }
        assertEquals(true, secure["w"])
        frame()
        assertEquals(false, secure["w"])
    }

    @Test
    fun aHoldDuringTheWaitKeepsTheFlagUntilItsOwnWaitEnds() {
        holds.acquire("w")
        holds.release("w")
        frame()
        holds.acquire("w")
        repeat(NotebookWindowSecurity.HOLD_FRAMES) { frame() }
        assertEquals(true, secure["w"])
        holds.release("w")
        frame()
        assertEquals(true, secure["w"])
        repeat(NotebookWindowSecurity.HOLD_FRAMES) { frame() }
        assertEquals(false, secure["w"])
    }

    @Test
    fun aWindowThatWasAlreadySecureStaysSecure() {
        secure["w"] = true
        holds.acquire("w")
        holds.release("w")
        repeat(NotebookWindowSecurity.HOLD_FRAMES) { frame() }
        assertEquals(true, secure["w"])
    }

    @Test
    fun aReleaseWithoutAHoldChangesNothing() {
        secure["w"] = true
        holds.release("w")
        assertTrue(waiting.isEmpty())
        assertEquals(true, secure["w"])
    }

    @Test
    fun windowsAreHeldApart() {
        holds.acquire("a")
        holds.acquire("b")
        holds.release("a")
        repeat(NotebookWindowSecurity.HOLD_FRAMES) { frame() }
        assertEquals(false, secure["a"])
        assertEquals(true, secure["b"])
    }
}
