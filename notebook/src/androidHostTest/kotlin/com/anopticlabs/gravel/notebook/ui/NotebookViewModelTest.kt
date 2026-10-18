package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.capture.MAX_QUEUE_ATTEMPTS
import com.anopticlabs.gravel.notebook.capture.ProcessingPause
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.pipeline.BuiltCapture
import com.anopticlabs.gravel.notebook.pipeline.CaptureBackend
import com.anopticlabs.gravel.notebook.pipeline.CaptureDrain
import com.anopticlabs.gravel.notebook.pipeline.SpeechRoute
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.pipeline.CapturePublisher
import com.anopticlabs.gravel.notebook.pipeline.CaptureRequest
import com.anopticlabs.gravel.notebook.pipeline.CaptureRows
import com.anopticlabs.gravel.notebook.pipeline.FailedCaptureActions
import com.anopticlabs.gravel.notebook.pipeline.ProcessingControls
import com.anopticlabs.gravel.notebook.pipeline.ProcessingPauseFile
import com.anopticlabs.gravel.notebook.pipeline.CaptureSettings
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import com.anopticlabs.gravel.notebook.sources.RecorderHarness
import com.anopticlabs.gravel.notebook.sources.RecorderState
import com.anopticlabs.gravel.notebook.sources.TypedNoteResult
import com.anopticlabs.gravel.notebook.sources.TypedNotes
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.KeyReset
import com.anopticlabs.gravel.notebook.storage.NotebookRecovery
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import com.anopticlabs.gravel.notebook.storage.EncryptedFiles
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import coredevices.util.PermissionResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NotebookViewModelTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val queue = QueueStore(root, crypto)
    private val notes = NoteStore(root, crypto)
    private val reminders = ReminderStore(root, crypto)
    private val completions = VoiceCompletionStore(root, crypto)
    private val now = 1_790_089_200_000L
    private val publisher = CapturePublisher(
        notes, reminders, completions,
        ReminderAlarms(reminders, ReminderEventStore(root, crypto), FakeScheduler(), FakeNotifier(), FakeSettings(), ReminderRefreshSignal(), { now }, { TimeZone.UTC }),
        FakeNotifier(), ReminderRefreshSignal(), { 30 }, { true }, { now }, { "id" },
    )
    private val rows = CaptureRows()
    private val backend = object : CaptureBackend {
        override suspend fun checkReady() = throw CaptureProcessingException(CaptureFailureKind.HOLD, "not set up")
        override suspend fun process(request: CaptureRequest, onStage: (CaptureStage) -> Unit): BuiltCapture = error("unused")
    }
    private val navigator = NotebookNavigator()
    private val drain = CaptureDrain(queue, notes, backend, publisher, rows, { CaptureSettings() })
    @Volatile
    private var drainRequests = 0
    private val recording = RecorderHarness(chunks = 20)
    private val recovery = NotebookRecovery(root, notes, queue, reminders, ReminderEventStore(root, crypto), completions, resetKey = { KeyReset.DELETED }, keyAbsent = { false }, afterReset = {})
    private val controls = ProcessingControls(ProcessingPauseFile(root), { true }, { true }, { true }, { _, _ -> }, { drainRequests++ }, { SpeechRoute.PHONE })
    @Volatile
    private var retryRequests = 0
    @Volatile
    private var processingId: String? = null
    private val viewModel = NotebookViewModel(
        rows, drain, NotebookStatusReader(queue, publisher, recovery), navigator, recording.recorder, TypedNotes(notes), completions.revision,
        { drainRequests++ }, StorageRecoveryRunner(recovery, restoreFile = {}, restoreAutomatic = {}),
        controls, FailedCaptureActions(queue, processing = { it == processingId }) { retryRequests++ },
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun thePauseButtonsReachTheControls() = runBlocking<Unit> {
        viewModel.pauseLong()
        withTimeout(5.seconds) { while (viewModel.pause.value != ProcessingPause.LONG) delay(10) }
        viewModel.pauseAll()
        withTimeout(5.seconds) { while (viewModel.pause.value != ProcessingPause.ALL) delay(10) }
        viewModel.resume()
        withTimeout(5.seconds) { while (drainRequests != 1) delay(10) }
        assertEquals(ProcessingPause.NONE, viewModel.pause.value)
        viewModel.processNow(NotebookStatus(waitingForPower = setOf("long")))
        withTimeout(5.seconds) { while (drainRequests != 2) delay(10) }
        assertEquals(setOf("long"), controls.rules().approved)
    }

    @Test
    fun theFailedListRetriesAndDeletesWhatItIsAskedToAndClosesOnceNothingIsFlagged() = runBlocking<Unit> {
        suspend fun flagged(id: String, at: Long) =
            queue.enqueue(QueuedCapture(id, "$id.wav", at, MAX_QUEUE_ATTEMPTS, true, failure = "no_speech"), byteArrayOf(1))
        flagged("quiet", now)
        flagged("other", now - 1)
        flagged("gone", now - 2)
        File(root, "${QueueStore.QUEUE_DIR}/gone${QueueStore.BLOB_EXTENSION}").delete()
        rows.queued("gone", now - 2)
        rows.settle("gone", CaptureQueueItem.Status.FAILED, null, "No speech was heard")
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        suspend fun listed(vararg ids: String) = withTimeout(5.seconds) { while (viewModel.status.value?.failed?.map { it.id } != ids.toList()) delay(10) }
        listed("quiet", "other", "gone")
        viewModel.openFailed()

        viewModel.retryFailed("quiet")
        listed("other", "gone")
        assertEquals(1, retryRequests)

        val gone = viewModel.status.value!!.failed.last()
        assertFalse(gone.retryable)
        viewModel.askDelete(gone)
        viewModel.cancelDelete()
        assertNull(viewModel.deleteAsk)

        viewModel.retryAllFailed()
        listed("gone")
        assertEquals(2, retryRequests)
        assertEquals(listOf(0, 0, MAX_QUEUE_ATTEMPTS), queue.readQueue().map { it.attempts })

        viewModel.retryAllFailed()
        viewModel.askDelete(gone)
        viewModel.confirmDelete()
        withTimeout(5.seconds) { while (viewModel.failedOpen) delay(10) }
        assertEquals(2, retryRequests)
        assertEquals(listOf("quiet", "other"), queue.readQueue().map { it.id })
        assertNull(rows.items.value.firstOrNull { it.id == "gone" })
        watching.cancelAndJoin()
    }

    @Test
    fun aReadThatMayPassOrADeleteThatDoesNotFinishKeepsTheListOpen() = runBlocking<Unit> {
        queue.enqueue(QueuedCapture("last", "last.wav", now, MAX_QUEUE_ATTEMPTS, true, failure = "no_speech"), byteArrayOf(1))
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        withTimeout(5.seconds) { while (viewModel.status.value?.failed?.size != 1) delay(10) }
        viewModel.openFailed()
        val last = viewModel.status.value!!.failed.single()

        crypto.failOpenWithTransient = true
        viewModel.refresh()
        withTimeout(5.seconds) { while (viewModel.status.value?.trouble != StorageTrouble.Unreadable) delay(10) }
        assertTrue(viewModel.failedOpen)
        assertNull(viewModel.failedNotice)
        crypto.failOpenWithTransient = false
        viewModel.refresh()
        withTimeout(5.seconds) { while (viewModel.status.value?.failed?.size != 1) delay(10) }

        processingId = "last"
        viewModel.askDelete(last)
        viewModel.confirmDelete()
        withTimeout(5.seconds) { while (viewModel.failedNotice != FailedNotice.NOT_DELETED) delay(10) }
        processingId = null

        crypto.failOpenWithTransient = true
        viewModel.askDelete(last)
        viewModel.confirmDelete()
        withTimeout(5.seconds) { while (viewModel.failedNotice != FailedNotice.FAILED) delay(10) }
        crypto.failOpenWithTransient = false

        viewModel.openFailed()
        assertNull(viewModel.failedNotice)
        assertEquals(listOf("last"), queue.readQueue().map { it.id })
        watching.cancelAndJoin()
    }

    @Test
    fun aNotificationLinkOpensTheRemindersSectionAndIsLeftForIt() = runBlocking<Unit> {
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        navigator.open(NotebookLink.Completions)
        withTimeout(5.seconds) { while (viewModel.section != NotebookSection.REMINDERS) delay(10) }
        assertEquals(NotebookLink.Completions, navigator.pending.value)
        watching.cancelAndJoin()
    }

    @Test
    fun aCalendarLinkOpensTheCalendarSectionAndIsLeftForIt() = runBlocking<Unit> {
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        navigator.open(NotebookLink.Calendar(1_790_089_200_000L))
        withTimeout(5.seconds) { while (viewModel.section != NotebookSection.CALENDAR) delay(10) }
        assertEquals(NotebookLink.Calendar(1_790_089_200_000L), navigator.pending.value)
        watching.cancelAndJoin()
    }

    @Test
    fun aCalendarLinkOpensTheCalendarFromTheRemindersSection() = runBlocking<Unit> {
        viewModel.select(NotebookSection.REMINDERS)
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        navigator.open(NotebookLink.Calendar(1_790_089_200_000L))
        withTimeout(5.seconds) { while (viewModel.section != NotebookSection.CALENDAR) delay(10) }
        watching.cancelAndJoin()
    }

    @Test
    fun aCompletionsLinkLeavesTheCalendarShown() = runBlocking<Unit> {
        viewModel.select(NotebookSection.CALENDAR)
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        navigator.open(NotebookLink.Completions)
        delay(300)
        assertEquals(NotebookSection.CALENDAR, viewModel.section)
        watching.cancelAndJoin()
    }

    @Test
    fun theCalendarLinkOpensCalendarAndTheOthersOpenRemindersOnlyFromNotes() {
        for (current in NotebookSection.entries) {
            assertEquals(NotebookSection.CALENDAR, sectionFor(NotebookLink.Calendar(0L), current))
        }
        val others = listOf(
            NotebookLink.Reminders,
            NotebookLink.Occurrence(ReminderEventKey(1, 0L), snooze = true),
            NotebookLink.Edit(1),
            NotebookLink.Completions,
        )
        for (link in others) {
            assertEquals(NotebookSection.REMINDERS, sectionFor(link, NotebookSection.NOTES), "$link")
            assertEquals(NotebookSection.REMINDERS, sectionFor(link, NotebookSection.REMINDERS), "$link")
            assertEquals(NotebookSection.CALENDAR, sectionFor(link, NotebookSection.CALENDAR), "$link")
        }
    }

    @Test
    fun theScreenStartingAsksForADrain() = runBlocking<Unit> {
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        withTimeout(5.seconds) { while (drainRequests == 0) delay(10) }
        watching.cancelAndJoin()
        assertEquals(1, drainRequests)
    }

    @Test
    fun theStatusCountsRecordingsKeptForALaterAttempt() = runBlocking<Unit> {
        File(root, "${QueueStore.PENDING_DIR}/a~clip.m4a").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1))
        File(root, "${QueueStore.PENDING_DIR}/not-a-capture").writeBytes(byteArrayOf(1))
        File(root, "${QueueStore.PENDING_DIR}/b~empty.m4a").writeBytes(ByteArray(0))
        assertEquals(1, NotebookStatusReader(queue, publisher, recovery).read(emptySet()).retained)
    }

    @Test
    fun aDrainPassThatAddsNoRowsStillRereadsTheStatus() = runBlocking<Unit> {
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        withTimeout(5.seconds) { while (viewModel.status.value == null) delay(10) }
        queue.enqueue("a", byteArrayOf(1), "a.m4a", now)
        drain.drain()
        withTimeout(5.seconds) { while (viewModel.status.value?.waiting != 1) delay(10) }
        watching.cancelAndJoin()
    }

    @Test
    fun aClaimTakenOutsideADrainLeavesTheCount() = runBlocking<Unit> {
        completions.save(VoiceCompletion("c1", "fed the dog", now))
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        withTimeout(5.seconds) { while (viewModel.status.value?.completionsToReview != 1) delay(10) }
        completions.take("c1")
        withTimeout(5.seconds) { while (viewModel.status.value?.completionsToReview != 0) delay(10) }
        watching.cancelAndJoin()
    }

    @Test
    fun theStatusKeyChangesWithSettlesAndBatchSizeButNotStages() {
        val queued = CaptureQueueItem("a", now, CaptureQueueItem.Status.QUEUED)
        val transcribing = queued.copy(status = CaptureQueueItem.Status.PROCESSING, stage = CaptureStage.TRANSCRIBING)
        val thinking = transcribing.copy(stage = CaptureStage.THINKING)
        val saved = queued.copy(status = CaptureQueueItem.Status.NOTE_CREATED, kind = CaptureQueueItem.Kind.NOTE, resultLabel = "milk")
        assertEquals(statusKey(listOf(queued)), statusKey(listOf(transcribing)))
        assertEquals(statusKey(listOf(transcribing)), statusKey(listOf(thinking)))
        assertNotEquals(statusKey(listOf(thinking)), statusKey(listOf(saved)))
        assertNotEquals(statusKey(listOf(saved)), statusKey(emptyList()))
        assertNotEquals(statusKey(listOf(transcribing)), statusKey(listOf(transcribing, queued.copy(id = "b"))))
    }

    private suspend fun stopRecording() {
        recording.awaitReads(25)
        viewModel.stopRecording()
        recording.awaitIdle()
    }

    @Test
    fun aGrantedMicrophoneIsNotAskedForAgain() = runBlocking<Unit> {
        var asked = 0
        viewModel.record(hasMic = { true }, requestMic = { asked++; PermissionResult.Granted })
        assertEquals(0, asked)
        assertIs<RecorderState.Recording>(viewModel.recorderState.value)
        stopRecording()
        assertEquals(1, recording.ingested.size)
    }

    @Test
    fun aMissingMicrophonePermissionIsAskedForAndRecordsOnceGranted() = runBlocking<Unit> {
        var asked = 0
        viewModel.record(hasMic = { false }, requestMic = { asked++; PermissionResult.Granted })
        assertEquals(1, asked)
        assertNull(viewModel.micProblem)
        assertIs<RecorderState.Recording>(viewModel.recorderState.value)
        stopRecording()
    }

    @Test
    fun aRefusedMicrophoneRecordsNothingAndSaysWhy() = runBlocking<Unit> {
        viewModel.record(hasMic = { false }, requestMic = { PermissionResult.Rejected })
        assertEquals(MicProblem.DENIED, viewModel.micProblem)
        viewModel.record(hasMic = { false }, requestMic = { PermissionResult.RejectedForever })
        assertEquals(MicProblem.DENIED_FOR_GOOD, viewModel.micProblem)
        viewModel.record(hasMic = { false }, requestMic = null)
        assertEquals(MicProblem.NO_WINDOW, viewModel.micProblem)
        assertEquals(RecorderState.Idle, viewModel.recorderState.value)
        assertTrue(recording.events.isEmpty())
    }

    @Test
    fun aTypedNoteTheStoreCannotTakeReportsNoResultSoTheEditorKeepsIt() = runBlocking<Unit> {
        crypto.failSealWhenAadContains = "notes"
        assertNull(viewModel.saveTypedNote("Title", "body", "unclassified"))
        crypto.failSealWhenAadContains = null
        assertIs<TypedNoteResult.Saved>(viewModel.saveTypedNote("Title", "body", "unclassified"))
    }

    @Test
    fun aRecordTapWhileThePermissionIsBeingAskedForIsIgnored() = runBlocking<Unit> {
        val answer = CompletableDeferred<PermissionResult>()
        val asked = AtomicInteger(0)
        val first = launch(Dispatchers.Default) { viewModel.record(hasMic = { false }, requestMic = { asked.incrementAndGet(); answer.await() }) }
        withTimeout(5.seconds) { while (asked.get() == 0) delay(5) }
        viewModel.record(hasMic = { false }, requestMic = { asked.incrementAndGet(); PermissionResult.Granted })
        assertEquals(1, asked.get())
        answer.complete(PermissionResult.Rejected)
        first.join()
        assertEquals(MicProblem.DENIED, viewModel.micProblem)
    }

    @Test
    fun theDraftLivesUntilItIsClosed() {
        viewModel.openDraft()
        val draft = viewModel.draft!!
        draft.title = "kept"
        viewModel.openDraft()
        assertEquals("kept", viewModel.draft!!.title)
        viewModel.closeDraft()
        assertNull(viewModel.draft)
    }

    @Test
    fun aStorageCardActionReadsTheStatusAgain() = runBlocking<Unit> {
        File(root, VoiceCompletionStore.FILE_NAME).writeBytes(ByteArray(96) { 7 })
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        val trouble = withTimeout(5.seconds) { viewModel.status.first { it?.trouble != null } }!!.trouble
        viewModel.startFresh(assertIs<StorageTrouble.Damaged>(trouble))
        withTimeout(5.seconds) { viewModel.status.first { it != null && it.trouble == null } }
        watching.cancelAndJoin()
    }

    @Test
    fun aFailureLineGoesOnceAStatusReadFindsTheTroubleGone() = runBlocking<Unit> {
        val damaged = File(root, VoiceCompletionStore.FILE_NAME).apply { writeBytes(ByteArray(96) { 7 }) }
        val blocked = File(root, EncryptedFiles.QUARANTINE_DIR).apply { writeText("not a directory") }
        val watching = launch(Dispatchers.Default) { viewModel.watch() }
        val trouble = withTimeout(5.seconds) { viewModel.status.first { it?.trouble != null } }!!.trouble
        viewModel.startFresh(assertIs<StorageTrouble.Damaged>(trouble))
        withTimeout(5.seconds) { viewModel.recoveryState.first { it.failed == RecoveryFailure.NOT_FINISHED } }

        damaged.delete()
        blocked.delete()
        viewModel.refresh()
        withTimeout(5.seconds) { viewModel.recoveryState.first { it.failed == null } }
        watching.cancelAndJoin()
    }
}
