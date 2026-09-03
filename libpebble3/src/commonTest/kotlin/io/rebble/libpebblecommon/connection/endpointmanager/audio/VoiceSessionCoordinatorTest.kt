package io.rebble.libpebblecommon.connection.endpointmanager.audio

import io.rebble.libpebblecommon.packets.Result
import io.rebble.libpebblecommon.packets.SessionType
import io.rebble.libpebblecommon.services.VoiceService
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.TranscriptionWord
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Pins the dictation session rules against the firmware contract: the
 * deadline is reported one margin before the watch's own clock, a decode
 * that misses it keeps running and its transcript replays into the
 * watch's automatic retry (same app and type, inside the window, once),
 * anything else runs as a normal session, and a new setup request never
 * cancels a session in flight. Effects are recorded as strings; the
 * provider's results are completed by the test so timing is explicit
 * under the virtual clock.
 */
class VoiceSessionCoordinatorTest {
    private val appA = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val appB = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
    private val speex = VoiceEncoderInfo.Speex(
        sampleRate = 16_000, version = "1.2", bitRate = 16_800, bitstreamVersion = 4, frameSize = 320,
    )
    private val words = listOf(TranscriptionWord("take", 0.9f), TranscriptionWord("out", 0.9f))

    private class Harness(scope: TestScope) {
        val setupRequests = MutableSharedFlow<VoiceService.SessionSetupRequest>()
        val frames = mutableMapOf<UShort, MutableSharedFlow<UByteArray?>>()
        val log = mutableListOf<String>()

        /** Provider calls in order; each holds the deferred the test completes. */
        val calls = mutableListOf<Pair<Int, CompletableDeferred<TranscriptionResult>>>()
        var serve = true

        private val provider = object : TranscriptionProvider {
            override suspend fun canServeSession(): Boolean = serve
            override suspend fun transcribe(
                encoderInfo: VoiceEncoderInfo,
                audioFrames: Flow<UByteArray>,
                isNotificationReply: Boolean,
            ): TranscriptionResult {
                val received = audioFrames.toList()
                val deferred = CompletableDeferred<TranscriptionResult>()
                calls += received.size to deferred
                return deferred.await()
            }
        }

        val coordinator = VoiceSessionCoordinator(
            scope = scope.backgroundScope,
            setupRequests = setupRequests,
            framesFor = { id -> framesFlow(id) },
            sendSetupResult = { type, result, appInitiated -> log += "setup:$type:$result:app=$appInitiated" },
            sendDictationResult = { id, result, _ -> log += "result:$id:${describe(result)}" },
            sendAudioStop = { id -> log += "stop:$id" },
            provider = provider,
            timeSource = scope.testScheduler.timeSource,
        )

        private fun describe(result: TranscriptionResult) = when (result) {
            is TranscriptionResult.Success -> "success(${result.words.joinToString(" ") { it.word }})"
            is TranscriptionResult.Failed -> "failed"
            is TranscriptionResult.Error -> "error"
            is TranscriptionResult.ConnectionError -> "connection-error"
            is TranscriptionResult.Disabled -> "disabled"
        }

        private fun sharedFor(id: UShort) = frames.getOrPut(id) { MutableSharedFlow(extraBufferCapacity = 64) }

        // A null element ends the recording, standing in for the stop packet
        // that terminates the real per-session frame flow.
        private fun framesFlow(id: UShort): Flow<UByteArray> =
            sharedFor(id).takeWhile { it != null }.map { it!! }

        suspend fun frame(id: UShort) = sharedFor(id).emit(ubyteArrayOf(1u, 2u, 3u))
        suspend fun endRecording(id: UShort) = sharedFor(id).emit(null)
    }

    private fun request(id: Int, app: Uuid = appA, type: SessionType = SessionType.Dictation) =
        VoiceService.SessionSetupRequest(appUuid = app, sessionId = id, sessionType = type, encoderInfo = speex)

    private fun TestScope.harness(): Harness {
        val h = Harness(this)
        backgroundScope.launch { h.coordinator.run() }
        runCurrent()
        return h
    }

    @Test
    fun resultInsideTheDeadlineIsDeliveredAsIs() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        assertEquals(listOf("setup:Dictation:Success:app=true"), h.log)

        h.frame(1u); h.frame(1u); h.endRecording(1u)
        runCurrent()
        assertEquals(2, h.calls.single().first, "the provider must see every buffered frame")
        advanceTimeBy(3.seconds)
        h.calls.single().second.complete(TranscriptionResult.Success(words))
        runCurrent()
        assertEquals(listOf("setup:Dictation:Success:app=true", "result:1:success(take out)"), h.log)
    }

    @Test
    fun missedDeadlineReportsFailureOneMarginEarlyAndKeepsTheDecode() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.frame(1u); h.endRecording(1u)
        runCurrent()

        advanceTimeBy(13.seconds); runCurrent()
        assertEquals(1, h.log.size, "nothing is sent before the deadline")
        advanceTimeBy(1.seconds + 1.seconds); runCurrent()
        assertEquals(listOf("setup:Dictation:Success:app=true", "result:1:failed"), h.log)
        assertFalse(h.calls.single().second.isCancelled, "the decode must keep running after the deadline")

        h.calls.single().second.complete(TranscriptionResult.Success(words))
        runCurrent()
        assertEquals(2, h.log.size, "a late result is not sent to the lost session")
    }

    @Test
    fun retryReplaysTheLateTranscriptAndStopsTheRecording() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.frame(1u); h.endRecording(1u)
        runCurrent()
        advanceTimeBy(15.seconds); runCurrent()
        assertEquals("result:1:failed", h.log.last())

        // The watch's automatic retry, five seconds later, same app and type.
        advanceTimeBy(5.seconds)
        h.setupRequests.emit(request(2))
        runCurrent()
        assertEquals("setup:Dictation:Success:app=true", h.log.last())

        // The late decode finishes while the retry is recording.
        advanceTimeBy(2.seconds)
        h.calls.single().second.complete(TranscriptionResult.Success(words))
        runCurrent()
        assertEquals(
            listOf("stop:2", "result:2:success(take out)"),
            h.log.takeLast(2),
        )
        assertEquals(1, h.calls.size, "the retry's own audio is never transcribed")
    }

    @Test
    fun retryWaitsOutTheSettleTimeBeforeStoppingTheRecording() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.endRecording(1u)
        runCurrent()
        advanceTimeBy(15.seconds); runCurrent()
        // Late result already in hand when the retry arrives.
        h.calls.single().second.complete(TranscriptionResult.Success(words))
        runCurrent()

        h.setupRequests.emit(request(2))
        runCurrent()
        assertEquals("setup:Dictation:Success:app=true", h.log.last(), "no stop before the settle time")
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(listOf("stop:2", "result:2:success(take out)"), h.log.takeLast(2))
    }

    @Test
    fun retryFromAnotherAppRunsNormally() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1, app = appA))
        runCurrent()
        h.endRecording(1u); runCurrent()
        advanceTimeBy(15.seconds); runCurrent()
        h.calls[0].second.complete(TranscriptionResult.Success(words))
        runCurrent()

        h.setupRequests.emit(request(2, app = appB))
        runCurrent()
        h.frame(2u); h.endRecording(2u)
        runCurrent()
        assertEquals(2, h.calls.size, "a different app's session is transcribed on its own")
        h.calls[1].second.complete(TranscriptionResult.Success(listOf(TranscriptionWord("other", 0.9f))))
        runCurrent()
        assertEquals("result:2:success(other)", h.log.last())
        assertFalse(h.log.contains("stop:2"))
    }

    @Test
    fun expiredLateResultIsNotReplayed() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.endRecording(1u); runCurrent()
        advanceTimeBy(15.seconds); runCurrent()
        h.calls[0].second.complete(TranscriptionResult.Success(words))
        runCurrent()

        advanceTimeBy(61.seconds)
        h.setupRequests.emit(request(2))
        runCurrent()
        h.endRecording(2u); runCurrent()
        assertEquals(2, h.calls.size, "an expired transcript must not stand in for a new session")
    }

    @Test
    fun lateFailureLetsTheRetryTranscribeItsOwnAudio() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.endRecording(1u); runCurrent()
        advanceTimeBy(15.seconds); runCurrent()

        h.setupRequests.emit(request(2))
        runCurrent()
        h.frame(2u); h.frame(2u); h.frame(2u)
        runCurrent()
        h.calls[0].second.complete(TranscriptionResult.Error("engine failed"))
        runCurrent()
        h.endRecording(2u); runCurrent()
        assertEquals(2, h.calls.size)
        assertEquals(3, h.calls[1].first, "frames buffered while waiting reach the retry's own decode")
        assertFalse(h.log.contains("stop:2"))
    }

    @Test
    fun aNewSetupRequestNeverCancelsTheSessionInFlight() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.endRecording(1u); runCurrent()

        h.setupRequests.emit(request(2))
        runCurrent()
        assertFalse(h.calls[0].second.isCancelled)
        h.calls[0].second.complete(TranscriptionResult.Success(words))
        runCurrent()
        assertTrue(h.log.contains("result:1:success(take out)"))
    }

    @Test
    fun setupIsRefusedWhenTheProviderCannotServe() = runTest {
        val h = harness()
        h.serve = false
        h.setupRequests.emit(request(1))
        runCurrent()
        assertEquals(listOf("setup:Dictation:FailDisabled:app=true"), h.log)
        assertTrue(h.calls.isEmpty())
    }
}
