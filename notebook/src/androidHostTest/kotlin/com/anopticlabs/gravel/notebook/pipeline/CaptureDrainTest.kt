package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.capture.MAX_QUEUE_ATTEMPTS
import com.anopticlabs.gravel.notebook.capture.MAX_SERVER_TRIES
import com.anopticlabs.gravel.notebook.capture.ProcessingPause
import com.anopticlabs.gravel.notebook.capture.ProcessingRules
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.failureReason
import com.anopticlabs.gravel.notebook.capture.isLong
import com.anopticlabs.gravel.notebook.notes.BuiltNote
import com.anopticlabs.gravel.notebook.reminders.ReminderProposal
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.ReminderTimeStatus
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CaptureDrainTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val queue = QueueStore(root, crypto)
    private val notes = NoteStore(root, crypto)
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val completions = VoiceCompletionStore(root, crypto)
    private val rows = CaptureRows()
    private val now = 1_790_089_200_000L
    private var ids = 0

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class FakeBackend : CaptureBackend {
        val requests = mutableListOf<CaptureRequest>()
        var notReady: Exception? = null
        var checks = 0

        // Consulted per capture; the default builds a note titled with the audio's text.
        var onProcess: suspend (CaptureRequest, (CaptureStage) -> Unit) -> BuiltCapture = { request, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            note(request.audio.decodeToString())
        }

        override suspend fun checkReady() {
            checks++
            notReady?.let { throw it }
        }

        override suspend fun process(request: CaptureRequest, onStage: (CaptureStage) -> Unit): BuiltCapture {
            requests += request
            return onProcess(request, onStage)
        }
    }

    private val backend = FakeBackend()

    private fun publisher() = CapturePublisher(
        notes, reminders, completions,
        ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), ReminderRefreshSignal(), { now }, { TimeZone.UTC }),
        FakeNotifier(), ReminderRefreshSignal(), { 30 }, { true }, { now }, { "id-${++ids}" },
    )

    private val settings = CaptureSettings(triggerEnabled = true, triggerWord = "done")

    private val drain = CaptureDrain(queue, notes, backend, publisher(), rows, { settings }, { TimeZone.UTC })

    @Volatile
    private var rules = ProcessingRules()

    private var onAdmitted: (QueuedCapture) -> Unit = {}

    private val ruled = CaptureDrain(queue, notes, backend, publisher(), rows, { settings }, { TimeZone.UTC }, rules = { rules }, admitted = { onAdmitted(it) })

    private suspend fun enqueueTimed(id: String, durationMillis: Long?, at: Long = now) {
        assertTrue(queue.enqueue(QueuedCapture(id, "$id.m4a", at, durationMillis = durationMillis), id.encodeToByteArray()))
    }

    private fun processed() = backend.requests.map { it.audio.decodeToString() }

    private suspend fun enqueue(id: String, text: String = id, at: Long = now) {
        assertTrue(queue.enqueue(id, text.encodeToByteArray(), "$id.m4a", at))
    }

    private fun entry(id: String) = queue.readQueue(strict = true).firstOrNull { it.id == id }

    private fun blob(id: String) = File(root, "${QueueStore.QUEUE_DIR}/$id${QueueStore.BLOB_EXTENSION}")

    private fun row(id: String) = rows.items.value.firstOrNull { it.id == id }

    private fun failWith(kind: CaptureFailureKind, message: String = "failed", remedy: CaptureRemedy? = null, network: Boolean = false) {
        backend.onProcess = { _, _ -> throw CaptureProcessingException(kind, message, remedy = remedy, waitsForNetwork = network) }
    }

    @Test
    fun aPublishedCaptureLeavesTheQueueWithItsRecording() = runBlocking<Unit> {
        enqueue("a", "milk")
        val report = drain.drain()
        assertEquals(DrainReport(published = 1), report)
        assertNull(entry("a"))
        assertFalse(blob("a").exists())
        val saved = notes.readMetadata(strict = true).keys.single()
        assertEquals(CaptureQueueItem(id = "a", createdAt = now, status = CaptureQueueItem.Status.NOTE_CREATED, kind = CaptureQueueItem.Kind.NOTE, resultLabel = "milk", noteId = saved), row("a"))
        assertEquals(0, rows.batchSubmitted.value)
    }

    @Test
    fun capturesAreProcessedInQueueOrderWithTheirRecordingTimeAndSettings() = runBlocking<Unit> {
        enqueue("first", at = now - 2_000)
        enqueue("second", at = now - 1_000)
        drain.drain()
        assertEquals(listOf("first", "second"), backend.requests.map { it.audio.decodeToString() })
        assertEquals(now - 2_000, backend.requests.first().capturedAt.toEpochMilliseconds())
        assertTrue(backend.requests.all { it.settings == settings && it.zone == TimeZone.UTC })
    }

    @Test
    fun aCaptureIsProcessedInTheZoneItWasRecordedInAndAnUnknownOneFallsBack() = runBlocking<Unit> {
        queue.enqueue(QueuedCapture("berlin", "b.wav", now, zoneId = "Europe/Berlin"), "b".encodeToByteArray())
        queue.enqueue(QueuedCapture("unknown", "u.wav", now, zoneId = "Not/AZone"), "u".encodeToByteArray())
        drain.drain()
        assertEquals(listOf(TimeZone.of("Europe/Berlin"), TimeZone.UTC), backend.requests.map { it.zone })
    }

    @Test
    fun onlyIndexedFoldersAndTheirTagsAreOffered() = runBlocking<Unit> {
        notes.ensureDefaultFolders()
        notes.createFolder("groceries", "milk, eggs")
        notes.updateFolders { it + "archive" }
        enqueue("a")
        drain.drain()
        val request = backend.requests.single()
        assertEquals(listOf("unclassified", "groceries"), request.folders)
        assertEquals(setOf("groceries"), request.folderTags.keys)
    }

    @Test
    fun aRetryCountsAnAttemptAndKeepsTheRecording() = runBlocking<Unit> {
        enqueue("a")
        failWith(CaptureFailureKind.RETRY)
        assertEquals(DrainReport(retried = 1), drain.drain())
        assertEquals(1, entry("a")!!.attempts)
        assertFalse(entry("a")!!.flagged)
        assertTrue(blob("a").exists())
        assertEquals(CaptureQueueItem.Status.FAILED, row("a")!!.status)
    }

    @Test
    fun theTenthRetryFlagsTheCaptureAndLaterDrainsSkipIt() = runBlocking<Unit> {
        enqueue("a")
        failWith(CaptureFailureKind.RETRY)
        repeat(MAX_QUEUE_ATTEMPTS) { drain.drain() }
        assertTrue(entry("a")!!.flagged)
        assertEquals(CaptureFailureReason.TRIES_RAN_OUT, entry("a")!!.failureReason)
        assertEquals(MAX_QUEUE_ATTEMPTS, backend.requests.size)
        drain.drain()
        assertEquals(MAX_QUEUE_ATTEMPTS, backend.requests.size)
    }

    @Test
    fun aFailureThatWouldRepeatFlagsAtOnceAndKeepsTheRecording() = runBlocking<Unit> {
        enqueue("a")
        enqueue("b")
        backend.onProcess = { request, _ ->
            if (request.audio.decodeToString() == "a") throw CaptureProcessingException(CaptureFailureKind.FLAG, "no speech was heard", reason = CaptureFailureReason.NO_SPEECH)
            note("b")
        }
        assertEquals(DrainReport(published = 1, flagged = 1), drain.drain())
        assertTrue(entry("a")!!.flagged)
        assertEquals(CaptureFailureReason.NO_SPEECH.code, entry("a")!!.failure)
        assertTrue(blob("a").exists())
        assertEquals("No speech was heard", row("a")!!.resultLabel)
        drain.drain()
        assertEquals(2, backend.requests.size)
    }

    /** A hold stops the drain with the rest untouched and nothing counted. */
    @Test
    fun aHoldStopsTheDrainAndCountsNothing() = runBlocking<Unit> {
        enqueue("a")
        enqueue("b")
        failWith(CaptureFailureKind.HOLD, "no text model is installed")
        assertEquals(DrainReport(held = CaptureHold("no text model is installed")), drain.drain())
        assertEquals(1, backend.requests.size)
        assertEquals(listOf(0, 0), queue.readQueue(strict = true).map { it.attempts })
        assertTrue(rows.items.value.isEmpty())
    }

    /** A wait leaves its capture queued with nothing counted, the rest of the pass going on, and names why. */
    @Test
    fun aWaitLeavesItsCaptureForALaterPassAndTheRestGoOn() = runBlocking<Unit> {
        enqueue("a", at = now)
        enqueue("b", at = now + 1)
        val wait = CaptureProcessingException(CaptureFailureKind.WAIT, "waiting for Wi-Fi", waitsForNetwork = true)
        backend.onProcess = { request, _ ->
            if (request.audio.decodeToString() == "a") throw wait
            note("b")
        }
        val hold = CaptureHold("waiting for Wi-Fi", waitsForNetwork = true, waitOnly = true)
        assertEquals(DrainReport(published = 1, waited = 1, held = hold), drain.drain())
        assertEquals(hold, drain.held.value)
        assertEquals(listOf("a", "b"), processed(), "a wait is not tried again in its pass")
        val a = assertNotNull(entry("a"))
        assertEquals(0, a.attempts)
        assertFalse(a.flagged)
        assertNull(row("a"))
        backend.onProcess = { request, _ -> note(request.audio.decodeToString()) }
        assertEquals(DrainReport(published = 1), drain.drain())
        assertNull(drain.held.value)
    }

    @Test
    fun aPassHoldWinsOverAnEarlierWait() = runBlocking<Unit> {
        enqueue("a", at = now)
        enqueue("b", at = now + 1)
        backend.onProcess = { request, _ ->
            if (request.audio.decodeToString() == "a") throw CaptureProcessingException(CaptureFailureKind.WAIT, "waiting for Wi-Fi")
            throw CaptureProcessingException(CaptureFailureKind.HOLD, "no text model is installed")
        }
        assertEquals(DrainReport(waited = 1, held = CaptureHold("no text model is installed")), drain.drain())
    }

    @Test
    fun aMarkFromTheSpeechStepIsStoredAndReachesTheNextAttempt() = runBlocking<Unit> {
        enqueue("a")
        backend.onProcess = { request, _ ->
            assertEquals("a", request.id)
            if (!request.phoneTried) {
                request.markPhoneTried()
                throw CaptureProcessingException(CaptureFailureKind.WAIT, "waiting for Wi-Fi")
            }
            note("a")
        }
        drain.drain()
        val marked = assertNotNull(entry("a"))
        assertTrue(marked.phoneTried)
        assertEquals(0, marked.attempts)
        assertEquals(DrainReport(published = 1), drain.drain())
        assertEquals(listOf(false, true), backend.requests.map { it.phoneTried })
    }

    @Test
    fun aServerTryFromTheSpeechStepIsStoredWithItsTimeAndReachesTheNextAttempt() = runBlocking<Unit> {
        enqueueTimed("a", 60_000L)
        backend.onProcess = { request, _ ->
            assertTrue(request.long)
            assertFalse(request.fromWatch)
            if (request.serverTries == 0) {
                assertTrue(request.markServerTry(now + 5))
                throw CaptureProcessingException(CaptureFailureKind.WAIT, "server unreachable")
            }
            note("a")
        }
        drain.drain()
        val marked = assertNotNull(entry("a"))
        assertEquals(1, marked.serverTries)
        assertEquals(now + 5, marked.serverTriedAtMillis)
        assertEquals(0, marked.attempts)
        assertEquals(DrainReport(published = 1), drain.drain())
        assertEquals(listOf<Long?>(null, now + 5), backend.requests.map { it.serverTriedAtMillis })
    }

    private suspend fun enqueueTriedLong(id: String) {
        assertTrue(queue.enqueue(QueuedCapture(id, "$id.m4a", now, durationMillis = 60_000L, serverTries = MAX_SERVER_TRIES - 1), id.encodeToByteArray()))
    }

    private fun lastTryReroutes() {
        backend.onProcess = { request, _ ->
            if (request.serverTries < MAX_SERVER_TRIES) {
                request.markServerTry(now)
                throw CaptureProcessingException(CaptureFailureKind.REROUTE, "server unreachable")
            }
            note(request.id)
        }
    }

    @Test
    fun aReroutedCaptureMeetsTheChargerRuleInTheSamePass() = runBlocking<Unit> {
        rules = ProcessingRules(longWaitsForPower = true, pluggedIn = false, speech = SpeechRoute.SERVER_THEN_PHONE)
        enqueueTriedLong("a")
        lastTryReroutes()
        assertEquals(DrainReport(), ruled.drain())
        assertEquals(1, backend.requests.size)
        assertEquals(1, ruled.waitingForPower.value)
        val waiting = assertNotNull(entry("a"))
        assertEquals(0, waiting.attempts)
        assertEquals(MAX_SERVER_TRIES, waiting.serverTries)
    }

    @Test
    fun aReroutedCaptureTheRulesLetStartRunsAgainInTheSamePass() = runBlocking<Unit> {
        rules = ProcessingRules(speech = SpeechRoute.SERVER_THEN_PHONE)
        enqueueTriedLong("a")
        lastTryReroutes()
        assertEquals(DrainReport(published = 1), ruled.drain())
        assertEquals(listOf(MAX_SERVER_TRIES - 1, MAX_SERVER_TRIES), backend.requests.map { it.serverTries })
        assertNull(entry("a"))
    }

    @Test
    fun aCaptureReroutedTwiceInAPassWaitsForTheNext() = runBlocking<Unit> {
        enqueue("a")
        failWith(CaptureFailureKind.REROUTE)
        assertEquals(DrainReport(waited = 1, held = CaptureHold("failed", waitOnly = true)), withTimeout(30.seconds) { drain.drain() })
        assertEquals(2, backend.requests.size)
        assertEquals(0, assertNotNull(entry("a")).attempts)
    }

    @Test
    fun aRerouteOfThePassesFirstCaptureDoesNotRepeatTheChecks() = runBlocking<Unit> {
        enqueue("a", at = now)
        enqueue("b", at = now + 1)
        var rerouted = false
        backend.onProcess = { request, _ ->
            if (!rerouted) {
                rerouted = true
                throw CaptureProcessingException(CaptureFailureKind.REROUTE, "server unreachable")
            }
            note(request.id)
        }
        assertEquals(DrainReport(published = 2), drain.drain())
        assertEquals(1, backend.checks)
    }

    @Test
    fun aWaitTheNetworkMayEndIsReportedOverALaterOneItMayNot() = runBlocking<Unit> {
        enqueue("a", at = now)
        enqueue("b", at = now + 1)
        val network = CaptureHold("waiting for Wi-Fi", waitsForNetwork = true, waitOnly = true)
        backend.onProcess = { request, _ ->
            if (request.id == "a") throw CaptureProcessingException(CaptureFailureKind.WAIT, "waiting for Wi-Fi", waitsForNetwork = true)
            throw CaptureProcessingException(CaptureFailureKind.WAIT, "too little free memory")
        }
        assertEquals(network, drain.drain()?.held)
        backend.onProcess = { request, _ ->
            if (request.id == "b") throw CaptureProcessingException(CaptureFailureKind.WAIT, "waiting for Wi-Fi", waitsForNetwork = true)
            throw CaptureProcessingException(CaptureFailureKind.WAIT, "too little free memory")
        }
        assertEquals(network, drain.drain()?.held)
    }

    @Test
    fun aRecordedServerTryAsksForTheNextOne() = runBlocking<Unit> {
        var scheduled = 0
        val trying = CaptureDrain(queue, notes, backend, publisher(), rows, { settings }, { TimeZone.UTC }, serverTried = { scheduled++ })
        enqueueTimed("a", 60_000L)
        backend.onProcess = { request, _ ->
            assertTrue(request.markServerTry(now))
            throw CaptureProcessingException(CaptureFailureKind.WAIT, "server unreachable")
        }
        trying.drain()
        assertEquals(1, scheduled)
    }

    @Test
    fun aRecordingFromTheWatchReachesTheSpeechStepAsShortAndFromTheWatch() = runBlocking<Unit> {
        assertTrue(queue.enqueue(QueuedCapture("w", "w.m4a", now, durationMillis = 60_000L, fromWatch = true), "w".encodeToByteArray()))
        drain.drain()
        val request = backend.requests.single()
        assertTrue(request.fromWatch)
        assertFalse(request.long)
    }

    @Test
    fun theNoticeIsReadAfterACanceledPassToo() = runBlocking<Unit> {
        val notice = CaptureNotice("the server failed a recording", CaptureRemedy.SERVER_CHECK)
        val noticing = CaptureDrain(queue, notes, backend, publisher(), rows, { settings }, { TimeZone.UTC }, notice = { notice })
        enqueue("a")
        val started = CompletableDeferred<Unit>()
        backend.onProcess = { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch(Dispatchers.Default) { noticing.drain() }
        withTimeout(5.seconds) { started.await() }
        job.cancelAndJoin()
        assertEquals(notice, noticing.notices.value)
    }

    @Test
    fun theNoticeIsReadAfterEachPass() = runBlocking<Unit> {
        var notice: CaptureNotice? = CaptureNotice("the server failed a recording", CaptureRemedy.SERVER_CHECK)
        val noticing = CaptureDrain(queue, notes, backend, publisher(), rows, { settings }, { TimeZone.UTC }, notice = { notice ?: error("unreadable") })
        noticing.drain()
        assertEquals(notice, noticing.notices.value)
        notice = null
        assertEquals(DrainReport(), noticing.drain())
        assertNull(noticing.notices.value)
    }

    @Test
    fun aHoldKeepsItsNetworkMark() = runBlocking<Unit> {
        enqueue("a")
        failWith(CaptureFailureKind.HOLD, "your server can't be reached", network = true)
        assertEquals(CaptureHold("your server can't be reached", waitsForNetwork = true), drain.drain()?.held)
    }

    @Test
    fun aReadinessHoldKeepsItsRemedyForTheScreen() = runBlocking<Unit> {
        enqueue("a")
        backend.notReady = CaptureProcessingException(
            CaptureFailureKind.HOLD, "no text model is installed", remedy = CaptureRemedy.TEXT_MODEL,
        )
        val hold = CaptureHold("no text model is installed", CaptureRemedy.TEXT_MODEL)
        assertEquals(DrainReport(held = hold), drain.drain())
        assertEquals(hold, drain.held.value)
    }

    @Test
    fun aHoldDuringACaptureKeepsItsRemedyForTheScreen() = runBlocking<Unit> {
        enqueue("a")
        failWith(CaptureFailureKind.HOLD, "the text model could not answer", CaptureRemedy.TEXT_MODEL)
        val hold = CaptureHold("the text model could not answer", CaptureRemedy.TEXT_MODEL)
        assertEquals(DrainReport(held = hold), drain.drain())
        assertEquals(hold, drain.held.value)
    }

    @Test
    fun theHeldReasonLastsUntilAPassIsNotHeld() = runBlocking<Unit> {
        enqueue("a")
        assertNull(drain.held.value)
        backend.notReady = CaptureProcessingException(CaptureFailureKind.HOLD, "on-device speech recognition is not set up")
        drain.drain()
        assertEquals(CaptureHold("on-device speech recognition is not set up"), drain.held.value)
        backend.notReady = null
        drain.drain()
        assertNull(drain.held.value)
    }

    @Test
    fun aPassCanceledPastItsChecksClearsTheHeldReason() = runBlocking<Unit> {
        enqueue("a")
        backend.notReady = CaptureProcessingException(CaptureFailureKind.HOLD, "no text model is installed")
        drain.drain()
        backend.notReady = null
        val started = CompletableDeferred<Unit>()
        backend.onProcess = { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch(Dispatchers.Default) { drain.drain() }
        withTimeout(5.seconds) { started.await() }
        job.cancelAndJoin()
        assertNull(drain.held.value)
    }

    @Test
    fun aDrainThatIsNotReadyTouchesNothing() = runBlocking<Unit> {
        enqueue("a")
        backend.notReady = CaptureProcessingException(CaptureFailureKind.HOLD, "on-device speech recognition is not set up")
        assertEquals(DrainReport(held = CaptureHold("on-device speech recognition is not set up")), drain.drain())
        assertTrue(backend.requests.isEmpty())
        assertTrue(rows.items.value.isEmpty())
        assertEquals(0, entry("a")!!.attempts)
    }

    @Test
    fun anEmptyQueueNeedsNoEngine() = runBlocking<Unit> {
        backend.notReady = CaptureProcessingException(CaptureFailureKind.HOLD, "no text model is installed")
        assertEquals(DrainReport(), drain.drain())
    }

    @Test
    fun anEntryWhoseRecordingIsGoneIsDropped() = runBlocking<Unit> {
        enqueue("a")
        blob("a").delete()
        assertEquals(DrainReport(dropped = 1), drain.drain())
        assertNull(entry("a"))
        assertTrue(backend.requests.isEmpty())
        assertNull(row("a"))
    }

    @Test
    fun aRecordingThatFailsToOpenIsFlaggedAndQuarantined() = runBlocking<Unit> {
        enqueue("a")
        blob("a").writeBytes(blob("a").readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertEquals(DrainReport(flagged = 1), drain.drain())
        assertTrue(entry("a")!!.flagged)
        assertEquals(CaptureFailureReason.DAMAGED, entry("a")!!.failureReason)
        assertFalse(blob("a").exists())
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun aStopDuringTheReadOfADamagedRecordingStillFlagsIt() = runBlocking<Unit> {
        enqueue("a")
        blob("a").writeBytes(blob("a").readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        crypto.beforeOpen = { location -> if (location.endsWith("a.bin")) assertTrue(drain.interrupt(false) { true }) }
        drain.drain()
        crypto.beforeOpen = null
        assertTrue(entry("a")!!.flagged)
        assertEquals(CaptureFailureReason.DAMAGED, entry("a")!!.failureReason)
        assertEquals(DrainReport(), drain.drain())
        assertNotNull(entry("a"))
    }

    @Test
    fun theCaptureBeingProcessedIsKnownByItsId() = runBlocking<Unit> {
        enqueue("a")
        var seen = false
        backend.onProcess = { _, _ ->
            seen = drain.isProcessing("a") && !drain.isProcessing("b")
            note("a")
        }
        drain.drain()
        assertTrue(seen)
        assertFalse(drain.isProcessing("a"))
    }

    @Test
    fun aStoreRefusalAndAFlagWithoutAReasonStoreWhatIsKnown() = runBlocking<Unit> {
        enqueue("refused")
        enqueue("bare")
        backend.onProcess = { request, _ ->
            when (request.audio.decodeToString()) {
                "refused" -> throw IllegalArgumentException("a note over its cap")
                else -> throw CaptureProcessingException(CaptureFailureKind.FLAG, "flagged")
            }
        }
        assertEquals(DrainReport(flagged = 2), drain.drain())
        assertEquals(CaptureFailureReason.NOT_SAVED, entry("refused")!!.failureReason)
        assertEquals(CaptureFailureReason.UNKNOWN, entry("bare")!!.failureReason)
    }

    @Test
    fun aFailedCaptureTriedAgainIsProcessedByTheNextDrain() = runBlocking<Unit> {
        enqueue("a", "milk")
        failWith(CaptureFailureKind.FLAG)
        assertEquals(DrainReport(flagged = 1), drain.drain())
        backend.onProcess = { request, _ -> note(request.audio.decodeToString()) }
        assertTrue(queue.retryFailed(setOf("a")))
        assertEquals(DrainReport(published = 1), drain.drain())
        assertNull(entry("a"))
    }

    @Test
    fun aBusyKeyStoreWhileReadingTheRecordingIsRetried() = runBlocking<Unit> {
        enqueue("a")
        crypto.failOpenWhenAadContains = "queue/a"
        assertEquals(DrainReport(retried = 1), drain.drain())
        crypto.failOpenWhenAadContains = null
        assertEquals(1, entry("a")!!.attempts)
    }

    @Test
    fun aMissingKeyHoldsTheDrain() = runBlocking<Unit> {
        enqueue("a")
        crypto.failOpenWithKeyUnavailable = true
        val report = drain.drain()
        crypto.failOpenWithKeyUnavailable = false
        assertNotNull(report!!.held)
        assertEquals(0, entry("a")!!.attempts)
        assertTrue(backend.requests.isEmpty())
    }

    /** The publish is what fails here, after the engines ran: the capture stays queued for another try. */
    @Test
    fun aStoreThatCannotKeepThePublishRetriesTheCapture() = runBlocking<Unit> {
        enqueue("a")
        crypto.failSealWhenAadContains = NoteStore.META_FILE
        assertEquals(DrainReport(retried = 1), drain.drain())
        crypto.failSealWhenAadContains = null
        assertTrue(blob("a").exists())
        assertEquals(1, entry("a")!!.attempts)
        assertEquals(DrainReport(published = 1), drain.drain())
    }

    @Test
    fun aCompletionLeavesNoRow() = runBlocking<Unit> {
        enqueue("a")
        backend.onProcess = { _, _ -> BuiltCapture.Completion("fed the cat") }
        drain.drain()
        assertNull(row("a"))
        assertEquals(1, completions.readAll(strict = true).size)
    }

    @Test
    fun aReminderRowCarriesItsReminder() = runBlocking<Unit> {
        enqueue("set")
        backend.onProcess = { _, _ -> BuiltCapture.Reminder(ReminderProposal("Call mom", null, ReminderTimeStatus.NO_TIME)) }
        drain.drain()
        val set = row("set")!!
        assertEquals(CaptureQueueItem.Status.REMINDER_SET, set.status)
        assertEquals(reminders.readAll(strict = true).single().id, set.reminderId)
    }

    @Test
    fun stagesReachTheCapturesRow() = runBlocking<Unit> {
        enqueue("a")
        val seen = mutableListOf<CaptureQueueItem?>()
        backend.onProcess = { request, onStage ->
            onStage(CaptureStage.THINKING)
            seen += row("a")
            note(request.audio.decodeToString())
        }
        drain.drain()
        assertEquals(CaptureQueueItem.Status.PROCESSING, seen.single()!!.status)
        assertEquals(CaptureStage.THINKING, seen.single()!!.stage)
    }

    /** A drain asked for while one runs is served by the running one, which picks up what was queued meanwhile. */
    @Test
    fun aDrainAskedForDuringOneMakesItGoRoundAgain() = runBlocking<Unit>(Dispatchers.Default) {
        enqueue("a")
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backend.onProcess = { request, _ ->
            if (request.audio.decodeToString() == "a") {
                inside.complete(Unit)
                release.await()
            }
            note(request.audio.decodeToString())
        }
        val first = async { drain.drain() }
        inside.await()
        enqueue("b")
        assertNull(drain.drain())
        release.complete(Unit)
        withTimeout(10.seconds) { first.await() }
        assertEquals(listOf("a", "b"), backend.requests.map { it.audio.decodeToString() })
        assertTrue(queue.readQueue(strict = true).isEmpty())
    }

    @Test
    fun aCanceledDrainLeavesTheCaptureQueuedAndUncounted() = runBlocking<Unit>(Dispatchers.Default) {
        enqueue("a")
        val inside = CompletableDeferred<Unit>()
        backend.onProcess = { _, _ ->
            inside.complete(Unit)
            CompletableDeferred<Unit>().await()
            error("unreachable")
        }
        val running = async { drain.drain() }
        inside.await()
        running.cancelAndJoin()
        assertEquals(0, entry("a")!!.attempts)
        assertTrue(blob("a").exists())
        assertTrue(rows.items.value.isEmpty())
        backend.onProcess = { request, _ -> note(request.audio.decodeToString()) }
        assertEquals(DrainReport(published = 1), drain.drain())
    }

    @Test
    fun failuresOutsideTheEnginesAreReadByKind() {
        assertEquals(CaptureFailureKind.HOLD, captureFailureKind(com.anopticlabs.gravel.notebook.storage.KeyUnavailableException("gone")))
        assertEquals(CaptureFailureKind.RETRY, captureFailureKind(com.anopticlabs.gravel.notebook.storage.TransientCryptoException("busy", RuntimeException())))
        assertEquals(CaptureFailureKind.HOLD, captureFailureKind(com.anopticlabs.gravel.notebook.storage.CorruptFileException("bad", RuntimeException())))
        assertEquals(CaptureFailureKind.HOLD, captureFailureKind(RuntimeException(com.anopticlabs.gravel.notebook.storage.PersistedFormatException("reminders", RuntimeException()))))
        assertEquals(CaptureFailureKind.FLAG, captureFailureKind(IllegalArgumentException("not a note name")))
        assertEquals(CaptureFailureKind.RETRY, captureFailureKind(java.io.IOException("disk")))
        assertEquals(CaptureFailureKind.FLAG, captureFailureKind(CaptureProcessingException(CaptureFailureKind.FLAG, "x")))
    }

    /** Counted before the engines run: a capture whose processing ends the drain, or the process, still moves toward its flag. */
    @Test
    fun anErrorThatEndsTheDrainStillCountsTheAttempt() = runBlocking<Unit> {
        enqueue("a")
        backend.onProcess = { _, _ -> throw OutOfMemoryError("test") }
        assertFailsWith<OutOfMemoryError> { drain.drain() }
        assertEquals(1, entry("a")!!.attempts)
    }

    @Test
    fun eachVerdictIsOnDiskBeforeTheNextCaptureStarts() = runBlocking<Unit> {
        enqueue("retry")
        enqueue("flag")
        enqueue("last")
        val seen = mutableListOf<Pair<Int, Boolean>>()
        backend.onProcess = { request, _ ->
            when (request.audio.decodeToString()) {
                "retry" -> throw CaptureProcessingException(CaptureFailureKind.RETRY, "busy")
                "flag" -> {
                    seen += entry("retry")!!.let { it.attempts to it.flagged }
                    throw CaptureProcessingException(CaptureFailureKind.FLAG, "no speech was heard")
                }
                else -> {
                    seen += entry("flag")!!.let { it.attempts to it.flagged }
                    note("last")
                }
            }
        }
        drain.drain()
        assertEquals(listOf(1 to false, MAX_QUEUE_ATTEMPTS to true), seen)
    }

    @Test
    fun theAttemptThatReachesTheCapIsReportedAsFlagged() = runBlocking<Unit> {
        enqueue("a")
        queue.update { current -> current.map { it.copy(attempts = MAX_QUEUE_ATTEMPTS - 1) } }
        failWith(CaptureFailureKind.RETRY)
        assertEquals(DrainReport(flagged = 1), drain.drain())
        assertTrue(entry("a")!!.flagged)
        assertEquals("Couldn't process this recording", row("a")!!.resultLabel)
    }

    /** A store file that will not open is found before any transcription is spent on it. */
    @Test
    fun aStoreFileThatFailsToOpenHoldsBeforeAnyEngineRuns() = runBlocking<Unit> {
        notes.createNote("unclassified", "x.md", "x")
        val index = File(root, NoteStore.META_FILE)
        index.writeBytes(index.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        notes.forgetCachedIndexForTest()
        enqueue("a")
        assertEquals(DrainReport(held = CaptureHold("the Notebook's storage could not be read")), drain.drain())
        assertTrue(backend.requests.isEmpty())
        assertEquals(0, entry("a")!!.attempts)
    }

    /** A folder list the key store cannot read right now must not send the note to `unclassified`. */
    @Test
    fun aFolderListThatCannotBeReadWritesNoNote() = runBlocking<Unit> {
        notes.ensureDefaultFolders()
        notes.createFolder("groceries", "milk")
        enqueue("a")
        crypto.failOpenWhenAadContains = NoteStore.FOLDER_TAGS_FILE
        val report = drain.drain()
        crypto.failOpenWhenAadContains = null
        assertNotNull(report!!.held)
        assertTrue(notes.readMetadata(strict = true).isEmpty())
    }

    @Test
    fun aNoteTheStoreRefusesIsFlaggedAtOnce() = runBlocking<Unit> {
        enqueue("a")
        backend.onProcess = { _, _ -> note("x".repeat(200)) }
        assertEquals(DrainReport(flagged = 1), drain.drain())
        assertTrue(entry("a")!!.flagged)
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun aRetriedCaptureShowsAsInFlightWhenDrainedAgain() = runBlocking<Unit> {
        enqueue("a")
        failWith(CaptureFailureKind.RETRY)
        drain.drain()
        assertEquals(CaptureQueueItem.Status.FAILED, row("a")!!.status)
        val seen = mutableListOf<CaptureQueueItem.Status?>()
        backend.onProcess = { request, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            seen += row("a")?.status
            note(request.audio.decodeToString())
        }
        drain.drain()
        assertEquals(listOf<CaptureQueueItem.Status?>(CaptureQueueItem.Status.PROCESSING), seen)
    }

    /** A drain asked for while a pass holds is served by another pass, which may find the cause gone. */
    @Test
    fun aHoldKeepsARequestMadeDuringThePass() = runBlocking<Unit> {
        enqueue("a")
        var first = true
        backend.onProcess = { request, _ ->
            if (first) {
                first = false
                assertNull(drain.drain(), "a nested request was not handed to the running drain")
                throw CaptureProcessingException(CaptureFailureKind.HOLD, "no text model is installed")
            }
            note(request.audio.decodeToString())
        }
        assertEquals(DrainReport(published = 1), drain.drain())
        assertTrue(queue.readQueue(strict = true).isEmpty())
    }

    /** Once stored, a result's capture leaves the queue even when the drain is canceled in between. */
    @Test
    fun aCancelDuringThePublishStillDequeues() = runBlocking<Unit>(Dispatchers.Default) {
        enqueue("a")
        lateinit var running: kotlinx.coroutines.Deferred<DrainReport?>
        val canceling = object : com.anopticlabs.gravel.notebook.alarms.ReminderNotifier by FakeNotifier() {
            override fun postSetNotice(item: com.anopticlabs.gravel.notebook.reminders.ReminderItem) = running.cancel()
        }
        val publisher = CapturePublisher(
            notes, reminders, completions,
            ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), ReminderRefreshSignal(), { now }, { TimeZone.UTC }),
            canceling, ReminderRefreshSignal(), { 30 }, { true }, { now }, { "id-${++ids}" },
        )
        backend.onProcess = { _, _ -> BuiltCapture.Reminder(ReminderProposal("Call mom", null, ReminderTimeStatus.NO_TIME)) }
        val cancelable = CaptureDrain(queue, notes, backend, publisher, rows, { CaptureSettings() }, { TimeZone.UTC })
        running = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { cancelable.drain() }
        running.start()
        running.join()
        assertEquals(1, reminders.readAll(strict = true).size)
        assertNull(entry("a"), "a published capture stayed queued")
    }

    private companion object {
        fun note(text: String) = BuiltCapture.Note(
            BuiltNote(
                title = text,
                tags = emptyList(),
                folder = "unclassified",
                filename = "${text}_09.22.26_1700.md",
                markdown = "---\ntitle: $text\n---\n$text\n",
                transcript = text,
                classificationFailed = false,
                date = "2026-09-22",
            ),
        )
    }

    @Test
    fun shortCapturesAreProcessedBeforeLongOnes() = runBlocking<Unit> {
        enqueueTimed("long", null, at = now)
        enqueueTimed("short", 5_000, at = now + 1)
        assertEquals(DrainReport(published = 2), ruled.drain())
        assertEquals(listOf("short", "long"), processed())
    }

    @Test
    fun aShortCaptureStopsALongOneMidTranscriptionWhichThenStartsOver() = runBlocking<Unit>(Dispatchers.Default) {
        enqueueTimed("long", null)
        val transcribing = CompletableDeferred<Unit>()
        var longRuns = 0
        backend.onProcess = { request, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            val text = request.audio.decodeToString()
            if (text == "long" && longRuns++ == 0) {
                transcribing.complete(Unit)
                awaitCancellation()
            }
            note(text)
        }
        val draining = async { ruled.drain() }
        withTimeout(5.seconds) { transcribing.await() }
        enqueueTimed("short", 5_000)
        assertTrue(ruled.interrupt(transcribingOnly = true) { it.isLong })
        withTimeout(5.seconds) { draining.await() }
        assertEquals(listOf("long", "short", "long"), processed())
        assertNull(entry("long"))
        assertNull(entry("short"))
    }

    @Test
    fun aStoppedCaptureStaysQueuedWithItsAttemptGivenBack() = runBlocking<Unit>(Dispatchers.Default) {
        enqueueTimed("long", null)
        enqueueTimed("other", null, at = now + 1)
        val transcribing = CompletableDeferred<Unit>()
        backend.onProcess = { _, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            transcribing.complete(Unit)
            awaitCancellation()
        }
        val draining = async { ruled.drain() }
        withTimeout(5.seconds) { transcribing.await() }
        rules = ProcessingRules(pause = ProcessingPause.ALL)
        assertTrue(ruled.interrupt(transcribingOnly = false) { true })
        withTimeout(5.seconds) { draining.await() }
        assertEquals(0, entry("long")?.attempts)
        assertEquals(0, entry("other")?.attempts)
        assertTrue(rows.items.value.none { it.inFlight })
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun aCapturePastItsTranscriptionIsSparedByAShortOneButNotByAPause() = runBlocking<Unit>(Dispatchers.Default) {
        enqueueTimed("long", null)
        val thinking = CompletableDeferred<Unit>()
        backend.onProcess = { _, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            onStage(CaptureStage.THINKING)
            thinking.complete(Unit)
            awaitCancellation()
        }
        val draining = async { ruled.drain() }
        withTimeout(5.seconds) { thinking.await() }
        assertFalse(ruled.interrupt(transcribingOnly = true) { true })
        assertFalse(ruled.interrupt(transcribingOnly = false) { !it.isLong })
        rules = ProcessingRules(pause = ProcessingPause.LONG)
        assertTrue(ruled.interrupt(transcribingOnly = false) { it.isLong })
        withTimeout(5.seconds) { draining.await() }
        assertEquals(0, entry("long")?.attempts)
    }

    @Test
    fun aStopThatLandsBeforeTheCaptureStartsKeepsItFromRunning() = runBlocking<Unit> {
        enqueueTimed("a", null)
        assertFalse(ruled.interrupt(transcribingOnly = false) { true }, "nothing started yet")
        val queueWrites = { crypto.sealed.count { QueueStore.QUEUE_META_FILE in it } }
        val before = queueWrites()
        onAdmitted = {
            // Time for a capture started eagerly to count its attempt; a lazy one has not begun.
            Thread.sleep(300)
            rules = ProcessingRules(pause = ProcessingPause.ALL)
            assertTrue(ruled.interrupt(transcribingOnly = true) { true })
        }
        assertEquals(DrainReport(), ruled.drain())
        assertTrue(backend.requests.isEmpty())
        assertEquals(before, queueWrites(), "the capture never touched the queue")
        assertEquals(0, entry("a")?.attempts)
        assertNull(row("a"))
    }

    @Test
    fun aShortCaptureQueuedBeforeThePickedLongOneStartsGoesFirst() = runBlocking<Unit> {
        enqueueTimed("long", null)
        var reads = 0
        val picking = CaptureDrain(queue, notes, backend, publisher(), rows, { settings }, { TimeZone.UTC }, rules = {
            // The first read orders the pass; the short capture arrives before the long one is registered.
            if (reads++ == 0) {
                runBlocking { enqueueTimed("short", 5_000) }
                assertFalse(drainForStop.interrupt(transcribingOnly = true) { it.isLong })
            }
            ProcessingRules()
        })
        drainForStop = picking
        assertEquals(DrainReport(published = 2), withTimeout(5.seconds) { picking.drain() })
        assertEquals(listOf("short", "long"), processed())
    }

    private lateinit var drainForStop: CaptureDrain

    @Test
    fun aCaptureWhoseFailureIsBeingHandledIsNotStopped() = runBlocking<Unit> {
        enqueueTimed("a", null)
        var failing = false
        var stopped: Boolean? = null
        crypto.beforeSeal = { location ->
            if (failing && QueueStore.QUEUE_META_FILE in location && stopped == null) stopped = ruled.interrupt(transcribingOnly = false) { true }
        }
        backend.onProcess = { _, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            failing = true
            throw CaptureProcessingException(CaptureFailureKind.FLAG, "no speech was heard")
        }
        assertEquals(DrainReport(flagged = 1), ruled.drain())
        assertEquals(false, stopped)
        crypto.beforeSeal = null
    }

    @Test
    fun aQueueUnreadableMidPassLeavesNoRowInFlight() = runBlocking<Unit> {
        enqueueTimed("a", 5_000, at = now)
        enqueueTimed("b", null, at = now + 1)
        backend.onProcess = { request, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            crypto.failOpenWhenAadContains = QueueStore.QUEUE_META_FILE
            note(request.audio.decodeToString())
        }
        assertEquals(CaptureHold("the capture queue could not be read"), ruled.drain()?.held)
        assertTrue(rows.items.value.none { it.inFlight })
        crypto.failOpenWhenAadContains = null
    }

    @Test
    fun aShortCaptureQueuedDuringAPassGoesBeforeTheLongOnesItHadOrdered() = runBlocking<Unit> {
        enqueueTimed("s1", 5_000, at = now)
        enqueueTimed("l1", null, at = now + 1)
        backend.onProcess = { request, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            val text = request.audio.decodeToString()
            if (text == "s1") enqueueTimed("s2", 5_000, at = now + 2)
            note(text)
        }
        assertEquals(DrainReport(published = 3), ruled.drain())
        assertEquals(listOf("s1", "s2", "l1"), processed())
    }

    @Test
    fun aRuleThatChangesBetweenThePickAndTheStartKeepsTheCaptureWaiting() = runBlocking<Unit> {
        var reads = 0
        val unplugged = ProcessingRules(longWaitsForPower = true, pluggedIn = false)
        val unplugging = CaptureDrain(queue, notes, backend, publisher(), rows, { settings }, { TimeZone.UTC }, rules = {
            if (reads++ == 0) ProcessingRules() else unplugged
        })
        enqueueTimed("long", null)
        assertEquals(DrainReport(), unplugging.drain())
        assertTrue(backend.requests.isEmpty())
        assertEquals(0, entry("long")?.attempts)
        assertEquals(1, unplugging.waitingForPower.value)
    }

    @Test
    fun aCaptureLetGoDuringAPassIsProcessedInThatPass() = runBlocking<Unit> {
        rules = ProcessingRules(pause = ProcessingPause.LONG)
        enqueueTimed("short", 5_000, at = now)
        enqueueTimed("long", null, at = now + 1)
        backend.onProcess = { request, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            rules = ProcessingRules()
            note(request.audio.decodeToString())
        }
        assertEquals(DrainReport(published = 2), ruled.drain())
        assertEquals(listOf("short", "long"), processed())
    }

    @Test
    fun aCaptureThatFailsTransientlyIsTriedOncePerPass() = runBlocking<Unit> {
        enqueueTimed("a", 5_000)
        failWith(CaptureFailureKind.RETRY)
        assertEquals(DrainReport(retried = 1), ruled.drain())
        assertEquals(1, entry("a")?.attempts)
    }

    @Test
    fun aStopThatLandsInTheLastStageLeavesTheCaptureUnpublished() = runBlocking<Unit> {
        enqueueTimed("a", null)
        backend.onProcess = { request, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            rules = ProcessingRules(pause = ProcessingPause.ALL)
            assertTrue(ruled.interrupt(transcribingOnly = false) { true })
            note(request.audio.decodeToString())
        }
        assertEquals(DrainReport(), ruled.drain())
        assertTrue(notes.readMetadata(strict = true).isEmpty())
        assertEquals(0, entry("a")?.attempts)
    }

    @Test
    fun aCaptureWhoseResultIsBeingStoredIsNotStopped() = runBlocking<Unit> {
        var stopped: Boolean? = null
        lateinit var storing: CaptureDrain
        val publisher = CapturePublisher(
            notes, reminders, completions,
            ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), ReminderRefreshSignal(), { now }, { TimeZone.UTC }),
            FakeNotifier(), ReminderRefreshSignal(), { 30 }, { true },
            { now.also { if (stopped == null) stopped = storing.interrupt(transcribingOnly = false) { true } } }, { "id-${++ids}" },
        )
        storing = CaptureDrain(queue, notes, backend, publisher, rows, { settings }, { TimeZone.UTC })
        enqueueTimed("a", null)
        backend.onProcess = { _, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            BuiltCapture.Reminder(ReminderProposal("call the plumber", null, ReminderTimeStatus.NO_TIME))
        }
        assertEquals(DrainReport(published = 1), storing.drain())
        assertEquals(false, stopped)
        assertNull(entry("a"))
    }

    @Test
    fun aHoldAfterAStopLeavesNoRowInFlight() = runBlocking<Unit>(Dispatchers.Default) {
        enqueueTimed("l1", null, at = now)
        enqueueTimed("l2", null, at = now + 1)
        val transcribing = CompletableDeferred<Unit>()
        backend.onProcess = { _, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            transcribing.complete(Unit)
            awaitCancellation()
        }
        val draining = async { ruled.drain() }
        withTimeout(5.seconds) { transcribing.await() }
        backend.notReady = CaptureProcessingException(CaptureFailureKind.HOLD, "not set up")
        assertTrue(ruled.interrupt(transcribingOnly = false) { true })
        assertEquals(CaptureHold("not set up"), withTimeout(5.seconds) { draining.await() }?.held)
        assertTrue(rows.items.value.none { it.inFlight })
        assertEquals(0, entry("l1")?.attempts)
    }

    @Test
    fun aCancellationThatIsNotAStopEndsTheDrain() = runBlocking<Unit> {
        enqueueTimed("a", null)
        backend.onProcess = { _, _ -> throw CancellationException("not a stop") }
        val ended = assertFailsWith<CancellationException> { withTimeout(5.seconds) { ruled.drain() } }
        assertEquals("not a stop", ended.message)
        assertEquals(0, entry("a")?.attempts)
        assertTrue(rows.items.value.none { it.inFlight })
    }

    @Test
    fun theRulesAreReadAgainBeforeEachCaptureAndTheRunningOneFinishes() = runBlocking<Unit> {
        enqueueTimed("first", null)
        enqueueTimed("second", null, at = now + 1)
        backend.onProcess = { request, onStage ->
            onStage(CaptureStage.TRANSCRIBING)
            rules = ProcessingRules(longWaitsForPower = true, pluggedIn = false)
            note(request.audio.decodeToString())
        }
        assertEquals(DrainReport(published = 1), ruled.drain())
        assertEquals(listOf("first"), processed())
        assertEquals(0, entry("second")?.attempts)
        assertNull(row("second"))
        assertEquals(1, ruled.waitingForPower.value)
    }

    @Test
    fun longCapturesTheChargerRuleHoldsAreCountedAndLeftQueued() = runBlocking<Unit> {
        rules = ProcessingRules(longWaitsForPower = true, pluggedIn = false)
        enqueueTimed("long", null)
        enqueueTimed("short", 5_000)
        assertEquals(DrainReport(published = 1), ruled.drain())
        assertEquals(listOf("short"), processed())
        assertEquals(1, ruled.waitingForPower.value)
        rules = rules.copy(pluggedIn = true)
        assertEquals(DrainReport(published = 1), ruled.drain())
        assertEquals(0, ruled.waitingForPower.value)
    }
}
