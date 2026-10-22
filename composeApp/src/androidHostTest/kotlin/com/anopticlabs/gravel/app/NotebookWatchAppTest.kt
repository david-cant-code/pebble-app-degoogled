package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.notebook.capture.MAX_RECORDING_SAMPLES
import com.anopticlabs.gravel.notebook.capture.wavDurationMillis
import com.anopticlabs.gravel.notebook.pipeline.IngestResult
import com.anopticlabs.gravel.ui.WatchAppStatus
import com.anopticlabs.gravel.watchapp.WATCH_SPEEX_FRAME_SAMPLES
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class NotebookWatchAppTest {
    // 20 ms frames: 50 make a second.
    private val speex = VoiceEncoderInfo.Speex(sampleRate = 16_000, version = "1.2", bitRate = 16_800, bitstreamVersion = 4, frameSize = 320)
    private val second = 50

    private var digests = ArrayDeque<String?>()
    private var installed: String? = "digest"
    private var trustedDigest: String? = "digest"
    private var on = true
    private var queued = 0
    private var ingest = IngestResult.QUEUED
    private var wait: suspend () -> RecordingWait = { RecordingWait.NONE }
    private var decodeGate: CompletableDeferred<Unit>? = null
    private var decodeFails = false
    private var now = 1_790_000_000_000L
    private val hours = TestTimeSource()
    private var source: TimeSource.WithComparableMarks = hours
    private var endDigestDelay = Duration.ZERO
    private var digestCalls = 0
    private val order = mutableListOf<String>()

    // Unconfined runs a lookup at once, so the virtual clock of runTest cannot pass its limit first.
    private var lookupContext: CoroutineContext = Dispatchers.Unconfined
    private val stored = mutableListOf<Pair<Long, Long?>>()

    @Volatile
    private var decodes = 0

    private val app by lazy { NotebookWatchApp(
        installedSha256 = {
            order += "digest"
            if (++digestCalls % 2 == 0) delay(endDigestDelay)
            if (digests.isEmpty()) installed else digests.removeFirst()
        },
        trusted = { it != null && it == trustedDigest },
        notebookOn = { on },
        watchRecordingsQueued = { queued },
        decode = { _, frames ->
            decodes++
            decodeGate?.await()
            if (decodeFails) error("Speex frame not decoded")
            ByteArray(frames.size * 640)
        },
        store = { wav, at -> synchronized(stored) { stored += at to wavDurationMillis(wav) }; ingest },
        waitFor = { wait() },
        clock = { now },
        marks = source,
        lookupContext = lookupContext,
    ) }

    // The wall clock moves on while the recording runs, so the stored date shows when it was read.
    private fun frames(count: Int) = flow<UByteArray> {
        order += "audio"
        now += 5_000
        repeat(count) { emit(UByteArray(40)) }
    }

    private suspend fun record(frameCount: Int = 2 * second) = app.capture(speex, frames(frameCount))

    @Test
    fun aRecordingIsStoredDatedAtItsStartAndTheAnswerSaysWhatItWaitsFor() = runTest {
        val start = now
        assertEquals("Saved", record())
        assertEquals(listOf<Pair<Long, Long?>>(start to 2_000L), stored)
        wait = { RecordingWait.WIFI }
        assertEquals("Saved. Waiting for Wi-Fi", record())
        wait = { RecordingWait.SETUP }
        assertEquals("Saved. Check Gravel", record())
        ingest = IngestResult.KEPT
        assertEquals("Saved", record(), "a recording kept outside the queue is saved")
        ingest = IngestResult.LOST
        assertEquals("Couldn't save. Try again.", record())
    }

    @Test
    fun theCopyIsCheckedBeforeTheAudioAndAgainAfterIt() = runTest {
        record()
        assertEquals(listOf("digest", "audio", "digest"), order)
    }

    @Test
    fun aStoredRecordingSaysSavedWhenItsWaitCannotBeLookedUp() = runTest {
        wait = { error("lookup failed") }
        assertEquals("Saved", record())
        wait = { awaitCancellation() }
        assertEquals("Saved", record(), "a lookup that does not answer in time")
        assertEquals(2, stored.size)
    }

    @Test
    fun aLookupBlockedOnTheDiskDoesNotHoldTheAnswer() = runTest {
        lookupContext = Dispatchers.IO
        wait = { Thread.sleep(3_000); RecordingWait.WIFI }
        assertEquals("Saved", record())
    }

    @Test
    fun aRecordingThatCannotBeDecodedIsNotSaved() = runTest {
        decodeFails = true
        assertEquals("Couldn't save. Try again.", record())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun aDeclaredRateOrFrameSizeOtherThanTheDecodersIsRefusedAndNothingIsDecodedOrStored() = runTest {
        val declared = listOf(0, 160, 319, 321, 65_535).map { speex.copy(frameSize = it) } + listOf(0L, 8_000L, 48_000L).map { speex.copy(sampleRate = it) }
        for (format in declared) assertEquals("Couldn't save. Try again.", app.capture(format, frames(2 * second)), format.toString())
        assertEquals(0, decodes)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun aRecordingThatWouldDecodePastTheLengthLimitIsRefusedUndecoded() = runTest {
        // One-byte frames keep the audio far under its byte cap; the bound counts frames.
        val most = MAX_RECORDING_SAMPLES / WATCH_SPEEX_FRAME_SAMPLES
        val oneByteFrames = { count: Int -> flow<UByteArray> { repeat(count) { emit(UByteArray(1)) } } }
        assertEquals("Couldn't save. Try again.", app.capture(speex, oneByteFrames(most + 1)))
        assertEquals(0, decodes)
        assertEquals("Saved", app.capture(speex, oneByteFrames(most)))
    }

    @Test
    fun aCopyGravelDoesNotTrustIsRefusedAndNothingIsDecodedOrStored() = runTest {
        trustedDigest = "another"
        assertEquals("Not saved. Unverified watch app.", record())
        installed = null
        assertEquals("Not saved. Unverified watch app.", record(), "no cached copy is not a trusted one")
        trustedDigest = "digest"
        digests = ArrayDeque(listOf("other", "digest"))
        assertEquals("Not saved. Unverified watch app.", record(), "the copy changed during the recording")
        digests = ArrayDeque(listOf("digest", "other"))
        assertEquals("Not saved. Unverified watch app.", record(), "the copy changed during the recording")
        assertTrue(stored.isEmpty())
        assertEquals(0, decodes)
    }

    @Test
    fun aNotebookThatDoesNotTakeWatchRecordingsStoresNothing() = runTest {
        on = false
        assertEquals("Notebook is off in Gravel", record())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun theNotebookTakesWatchRecordingsOnlyWhenOnSetUpAndAbleToRecord() = runTest {
        assertTrue(notebookTakesWatchRecordings(true, { true }, { true }))
        assertFalse(notebookTakesWatchRecordings(false, { true }, { true }))
        assertFalse(notebookTakesWatchRecordings(true, { false }, { true }), "setup guide not finished")
        assertFalse(notebookTakesWatchRecordings(true, { true }, { false }), "this phone cannot process recordings")
    }

    @Test
    fun aMissingTextModelMakesAStoredRecordingWaitForSetup() = runTest {
        assertEquals(RecordingWait.SETUP, storedRecordingWait({ false }) { RecordingWait.WIFI })
        assertEquals(RecordingWait.WIFI, storedRecordingWait({ true }) { RecordingWait.WIFI })
    }

    @Test
    fun underASecondIsNotCaughtAndASecondIsStored() = runTest {
        assertEquals("Didn't catch that", record(second - 1))
        assertTrue(stored.isEmpty())
        assertEquals("Saved", record(second))
    }

    @Test
    fun aFullQueueRefusesNewWatchRecordings() = runTest {
        queued = MAX_WATCH_RECORDINGS_QUEUED
        assertEquals("Notebook is full. Open Gravel.", record())
        queued = MAX_WATCH_RECORDINGS_QUEUED - 1
        assertEquals("Saved", record())
    }

    @Test
    fun recordingsPastTheHourlyLimitAreRefusedUntilTheHourHasPassed() = runTest {
        repeat(MAX_WATCH_RECORDINGS_PER_HOUR - 1) { assertEquals("Saved", record()) }
        ingest = IngestResult.LOST
        assertEquals("Couldn't save. Try again.", record())
        ingest = IngestResult.QUEUED
        assertEquals("Saved", record(), "a lost recording does not count")
        assertEquals("Too many recordings. Try later.", record())
        now -= 24.hours.inWholeMilliseconds
        hours += 1.hours - 1.milliseconds
        assertEquals("Too many recordings. Try later.", record(), "the hour is not read from the wall clock")
        hours += 1.milliseconds
        assertEquals("Saved", record())
    }

    @Test
    fun audioPastTheCapIsRefusedUnstored() = runTest {
        assertEquals("Couldn't save. Try again.", app.capture(speex, flow<UByteArray> { repeat(4) { emit(UByteArray(MAX_WATCH_RECORDING_BYTES / 3)) } }))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun anEmptyFrameCountsOneByteTowardTheCap() = runTest {
        val bigThenEmpty = { empty: Int ->
            flow<UByteArray> {
                emit(UByteArray(MAX_WATCH_RECORDING_BYTES - 2 * second))
                repeat(empty) { emit(UByteArray(0)) }
            }
        }
        assertEquals("Couldn't save. Try again.", app.capture(speex, bigThenEmpty(2 * second + 1)))
        assertEquals(0, decodes)
        assertEquals("Saved", app.capture(speex, bigThenEmpty(2 * second)))
    }

    private class Live(val audio: Channel<UByteArray>, val answer: Deferred<String>)

    private fun TestScope.live(): Live {
        source = testScheduler.timeSource
        val audio = Channel<UByteArray>(Channel.UNLIMITED)
        val answer = backgroundScope.async { app.capture(speex, audio.consumeAsFlow()) }
        repeat(2 * second) { audio.trySend(UByteArray(40)) }
        runCurrent()
        return Live(audio, answer)
    }

    @Test
    fun aCancelWhileRecordingStoresNothing() = runTest {
        val r = live()
        app.onMessage(mapOf(WATCH_CANCEL_KEY to 1uL))
        r.audio.close()
        runCurrent()
        assertTrue(r.answer.isCompleted, "a canceled recording needs no wait")
        assertEquals("Didn't catch that", r.answer.await())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun aCancelInsideTheWaitStoresNothing() = runTest {
        val r = live()
        r.audio.close()
        runCurrent()
        advanceTimeBy(WATCH_CANCEL_WAIT - 1.milliseconds)
        runCurrent()
        assertEquals(0, decodes, "nothing is decoded before the wait ends")
        app.onMessage(mapOf(WATCH_CANCEL_KEY to 1uL))
        assertEquals("Didn't catch that", r.answer.await())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun aCancelAfterTheWaitIsIgnoredAndTheRecordingIsStored() = runTest {
        decodeGate = CompletableDeferred()
        val r = live()
        r.audio.close()
        advanceTimeBy(WATCH_CANCEL_WAIT + 1.milliseconds)
        runCurrent()
        repeat(2_000) { if (decodes == 0) Thread.sleep(5) }
        assertEquals(1, decodes)
        app.onMessage(mapOf(WATCH_CANCEL_KEY to 1uL))
        decodeGate!!.complete(Unit)
        assertEquals("Saved", r.answer.await())
        assertEquals(1, stored.size)
    }

    @Test
    fun theWaitRunsFromTheEndOfTheRecordingNotFromTheChecks() = runTest {
        endDigestDelay = 100.milliseconds
        val r = live()
        r.audio.close()
        advanceTimeBy(100.milliseconds + WATCH_CANCEL_WAIT / 2)
        runCurrent()
        app.onMessage(mapOf(WATCH_CANCEL_KEY to 1uL))
        assertEquals("Saved", r.answer.await(), "a cancel past the wait from the end is ignored, however long the checks took")
    }

    @Test
    fun checksLongerThanTheWaitAddNoWaitAfterThem() = runTest {
        endDigestDelay = 100.milliseconds
        val r = live()
        r.audio.close()
        advanceTimeBy(100.milliseconds + 1.milliseconds)
        runCurrent()
        repeat(400) { if (decodes == 0) Thread.sleep(5) }
        assertEquals(1, decodes, "the decode starts as the checks end")
        assertEquals("Saved", r.answer.await())
    }

    @Test
    fun aCancelPastTheWaitIsIgnoredEvenWhileTheChecksRun() = runTest {
        endDigestDelay = 100.milliseconds
        val r = live()
        r.audio.close()
        advanceTimeBy(WATCH_CANCEL_WAIT + 25.milliseconds)
        runCurrent()
        app.onMessage(mapOf(WATCH_CANCEL_KEY to 1uL))
        assertEquals("Saved", r.answer.await())
    }

    @Test
    fun aCancelInsideTheWaitDiscardsEvenWhileTheChecksRun() = runTest {
        endDigestDelay = 100.milliseconds
        val r = live()
        r.audio.close()
        advanceTimeBy(WATCH_CANCEL_WAIT / 2)
        runCurrent()
        app.onMessage(mapOf(WATCH_CANCEL_KEY to 1uL))
        assertEquals("Didn't catch that", r.answer.await())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun aPingCancelsNothing() = runTest {
        val r = live()
        app.onMessage(mapOf(WATCH_PING_KEY to 1uL))
        r.audio.close()
        advanceTimeBy(WATCH_CANCEL_WAIT + 1.milliseconds)
        assertEquals("Saved", r.answer.await())
    }

    @Test
    fun aRecordingWhoseSessionIsCanceledAfterItsAudioEndedIsStillStored() = runTest {
        val r = live()
        r.audio.close()
        runCurrent()
        r.answer.cancel()
        r.answer.join()
        assertEquals(1, stored.size)
    }

    @Test
    fun shippedCopiesAndCopiesMarkedAsTrustedAreTrusted() {
        assertFalse(trustsWatchApp(null, { true }))
        assertFalse(trustsWatchApp("abc", { false }))
        assertTrue(trustsWatchApp("abc", { it == "abc" }))
        assertFalse(trustsWatchApp("abc", { it == "abd" }))
        val shipped = listOf(release("0.1", "old"), release("0.2", "new"))
        assertTrue(trustsWatchApp("old", { false }, shipped))
        assertTrue(trustsWatchApp("new", { false }, shipped))
        assertFalse(trustsWatchApp("abc", { false }, shipped))
        assertFalse(trustsWatchApp(null, { true }, shipped))
    }

    @Test
    fun theStatusFollowsTheDigestOfTheCopyInTheLocker() {
        val shipped = listOf(release("0.1", "old"), release("0.2", "new"))
        assertEquals(WatchAppStatus.NOT_INSTALLED, watchAppStatus(installed = false, sha256 = null, marked = false, shipped))
        assertEquals(WatchAppStatus.NOT_INSTALLED, watchAppStatus(installed = false, sha256 = "new", marked = true, shipped))
        assertEquals(WatchAppStatus.INSTALLED, watchAppStatus(installed = true, sha256 = "new", marked = false, shipped))
        assertEquals(WatchAppStatus.INSTALLED, watchAppStatus(installed = true, sha256 = "new", marked = true, shipped))
        assertEquals(WatchAppStatus.UPDATE_AVAILABLE, watchAppStatus(installed = true, sha256 = "old", marked = false, shipped))
        assertEquals(WatchAppStatus.UPDATE_AVAILABLE, watchAppStatus(installed = true, sha256 = "old", marked = true, shipped))
        assertEquals(WatchAppStatus.USER_TRUSTED, watchAppStatus(installed = true, sha256 = "abc", marked = true, shipped))
        assertEquals(WatchAppStatus.USER_TRUSTED, watchAppStatus(installed = true, sha256 = "abc", marked = true, emptyList()))
        assertEquals(WatchAppStatus.UNVERIFIED, watchAppStatus(installed = true, sha256 = "abc", marked = false, shipped))
        assertEquals(WatchAppStatus.UNVERIFIED, watchAppStatus(installed = true, sha256 = null, marked = false, shipped))
        assertEquals(WatchAppStatus.UNVERIFIED, watchAppStatus(installed = true, sha256 = null, marked = true, emptyList()))
        assertEquals(WatchAppStatus.UNVERIFIED, watchAppStatus(installed = true, sha256 = "new", marked = false, emptyList()))
    }

    @Test
    fun aCopyIsShownAsTrustedExactlyWhenItsRecordingsAreTaken() {
        val shipped = listOf(release("0.1", "old"), release("0.2", "new"))
        val shownAsTrusted = setOf(WatchAppStatus.INSTALLED, WatchAppStatus.UPDATE_AVAILABLE, WatchAppStatus.USER_TRUSTED)
        for (sha256 in listOf(null, "old", "new", "abc")) {
            for (mark in listOf(null, "abc", "new", "zzz")) {
                val status = watchAppStatus(installed = true, sha256, marked = sha256 != null && sha256 == mark, shipped)
                assertEquals(trustsWatchApp(sha256, { it == mark }, shipped), status in shownAsTrusted, "$sha256 with $mark marked")
            }
        }
    }

    @Test
    fun theTrustFileKeepsAWholeDigestUntilItIsCleared() {
        val digest = "0123456789abcdef".repeat(4)
        val trust = watchAppTrustFile(createTempDirectory("trust").toFile())
        assertTrue(trust.clear(), "nothing saved yet")
        assertTrue(trust.write(digest))
        assertEquals(digest, trust.read())
        assertTrue(trust.clear())
        assertEquals(null, trust.read())
    }

    private fun release(version: String, sha256: String) = WatchAppRelease(version, sha256, sizeBytes = 1, url = "https://example.invalid/$version")
}
