package io.rebble.libpebblecommon.connection.endpointmanager.audio

import io.rebble.libpebblecommon.packets.Result
import io.rebble.libpebblecommon.packets.SessionType
import io.rebble.libpebblecommon.services.VoiceService
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.TranscriptionWord
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Pins the dictation session rules against the firmware contract: the
 * deadline is reported one margin before the watch's own clock and is
 * measured from the end of the recording, a decode that misses it keeps
 * running and its late result goes nowhere, a recording the watch never
 * ends is abandoned at the bound, and a new setup request supersedes the
 * session in flight: its decode is cancelled and nothing more is sent for
 * it. Effects are recorded as strings; the provider's
 * results are completed by the test so timing is explicit under the
 * virtual clock.
 */
class VoiceSessionCoordinatorTest {
    private val appA = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val speex = VoiceEncoderInfo.Speex(
        sampleRate = 16_000, version = "1.2", bitRate = 16_800, bitstreamVersion = 4, frameSize = 320,
    )
    private val words = listOf(TranscriptionWord("take", 0.9f), TranscriptionWord("out", 0.9f))

    private class Harness(scope: TestScope) {
        val setupRequests = MutableSharedFlow<VoiceService.SessionSetupRequest>()
        val frames = mutableMapOf<UShort, MutableSharedFlow<UByteArray?>>()
        val log = mutableListOf<String>()

        /** Sessions the coordinator reported as ended, with the result it ended them on. */
        val ended = mutableListOf<String>()

        /** Provider calls in order; each holds the deferred the test completes. */
        val calls = mutableListOf<Pair<Int, CompletableDeferred<TranscriptionResult>>>()

        /** Indices into [calls] whose wait for a result was cancelled from above. */
        val cancelled = mutableListOf<Int>()
        var serve = true

        /** Thrown by the provider once it has read the recording, when set. */
        var failure: Exception? = null

        private val provider = object : TranscriptionProvider {
            override suspend fun canServeSession(): Boolean = serve
            override suspend fun transcribe(
                encoderInfo: VoiceEncoderInfo,
                audioFrames: Flow<UByteArray>,
                isNotificationReply: Boolean,
            ): TranscriptionResult {
                val received = audioFrames.toList()
                failure?.let { throw it }
                val deferred = CompletableDeferred<TranscriptionResult>()
                calls += received.size to deferred
                val index = calls.lastIndex
                try {
                    return deferred.await()
                } catch (e: CancellationException) {
                    cancelled += index
                    throw e
                }
            }
        }

        val coordinator = VoiceSessionCoordinator(
            scope = scope.backgroundScope,
            setupRequests = setupRequests,
            framesFor = { id -> framesFlow(id) },
            sendSetupResult = { type, result, appInitiated -> log += "setup:$type:$result:app=$appInitiated" },
            sendDictationResult = { id, result, _ -> log += "result:$id:${describe(result)}" },
            provider = provider,
            onSessionEnded = { request, result -> ended += "${request.sessionId}:${describe(result)}" },
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

        /** Live collectors on a session's frame flow: one while the recording is open, none after. */
        fun collectors(id: UShort) = sharedFor(id).subscriptionCount.value
    }

    private fun request(id: Int, app: Uuid = appA, encoderInfo: VoiceEncoderInfo? = speex) =
        VoiceService.SessionSetupRequest(appUuid = app, sessionId = id, sessionType = SessionType.Dictation, encoderInfo = encoderInfo)

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
        assertEquals(listOf("1:success(take out)"), h.ended)
    }

    @Test
    fun deadlineIsMeasuredFromTheEndOfTheRecording() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        // Ten seconds of recording before the stop packet.
        h.frame(1u); advanceTimeBy(5.seconds)
        h.frame(1u); advanceTimeBy(5.seconds)
        h.endRecording(1u); runCurrent()
        assertEquals(2, h.calls.single().first)

        // A clock started at the setup would have fired at 14 s; the
        // recording ended at 10 s, so nothing may be sent before 24 s.
        advanceTimeBy(13.seconds); runCurrent()
        assertEquals(1, h.log.size, "nothing is sent before the deadline")
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(listOf("setup:Dictation:Success:app=true", "result:1:failed"), h.log)
        assertEquals(listOf("1:failed"), h.ended)
        assertTrue(h.cancelled.isEmpty(), "the decode must keep running after the deadline")

        h.calls.single().second.complete(TranscriptionResult.Success(words))
        runCurrent()
        assertEquals(2, h.log.size, "a late result is not sent to the lost session")
    }

    @Test
    fun aResultAfterTheSetupClockButInsideTheRecordingClockIsDelivered() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        // The recording uses the whole cap, then the decode takes two seconds.
        h.frame(1u); advanceTimeBy(15.seconds)
        h.endRecording(1u); runCurrent()
        advanceTimeBy(2.seconds)
        h.calls.single().second.complete(TranscriptionResult.Success(words))
        runCurrent()
        assertEquals(listOf("setup:Dictation:Success:app=true", "result:1:success(take out)"), h.log)
    }

    @Test
    fun theRetryCancelsTheLostSessionsDecodeAndTranscribesOnItsOwn() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.frame(1u); h.endRecording(1u)
        runCurrent()
        advanceTimeBy(15.seconds); runCurrent()
        assertEquals("result:1:failed", h.log.last())
        assertEquals(listOf("1:failed"), h.ended)

        // The watch's automatic retry, five seconds later, same app: the lost
        // decode is cancelled with the session it belonged to, and ends once.
        advanceTimeBy(5.seconds)
        h.setupRequests.emit(request(2))
        runCurrent()
        assertEquals(listOf(0), h.cancelled, "the lost decode is cancelled by the retry's setup")
        assertEquals(listOf("1:failed"), h.ended, "a session already reported lost is not ended twice")
        assertEquals("setup:Dictation:Success:app=true", h.log.last())

        h.frame(2u); h.frame(2u); h.endRecording(2u); runCurrent()
        assertEquals(2, h.calls.size, "the retry is transcribed on its own")
        assertEquals(2, h.calls[1].first, "the retry's own frames reach its decode")
        h.calls[1].second.complete(TranscriptionResult.Success(listOf(TranscriptionWord("again", 0.9f))))
        runCurrent()
        assertEquals("result:2:success(again)", h.log.last())
    }

    @Test
    fun aNewSetupRequestSupersedesADecodeStillInsideItsDeadline() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.endRecording(1u); runCurrent()
        advanceTimeBy(2.seconds)

        // The watch starts a new session while the first is still decoding.
        h.setupRequests.emit(request(2))
        runCurrent()
        assertEquals(listOf(0), h.cancelled, "the first decode is cancelled by the second setup")
        assertEquals(listOf("1:failed"), h.ended, "the superseded session ends as failed")
        assertTrue(h.log.none { it.startsWith("result:1:") }, "nothing is sent for a session the watch left")

        h.endRecording(2u); runCurrent()
        h.calls[1].second.complete(TranscriptionResult.Success(words))
        runCurrent()
        assertEquals("result:2:success(take out)", h.log.last())
    }

    @Test
    fun aNewSetupRequestSupersedesARecordingStillOpen() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.frame(1u)
        assertEquals(1, h.collectors(1u))

        h.setupRequests.emit(request(2))
        runCurrent()
        assertEquals(0, h.collectors(1u), "the abandoned recording's collector is gone")
        assertTrue(h.calls.isEmpty(), "no decode ever started for it")
        assertEquals(listOf("1:failed"), h.ended)
        assertTrue(h.log.none { it.startsWith("result:1:") })
    }

    @Test
    fun aCompletedSessionIsNotDisturbedByTheNextSetup() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.endRecording(1u); runCurrent()
        h.calls[0].second.complete(TranscriptionResult.Success(words))
        runCurrent()
        assertEquals("result:1:success(take out)", h.log.last())

        h.setupRequests.emit(request(2))
        runCurrent()
        assertTrue(h.cancelled.isEmpty())
        assertEquals(listOf("1:success(take out)"), h.ended)
    }

    @Test
    fun aRecordingTheWatchNeverEndsIsAbandonedAtTheBound() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.frame(1u)
        runCurrent()
        assertEquals(1, h.collectors(1u))

        advanceTimeBy(VoiceSessionCoordinator.RECORDING_BOUND - 1.seconds); runCurrent()
        assertEquals(1, h.collectors(1u), "the recording is still open one second before the bound")
        assertTrue(h.ended.isEmpty())
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(listOf("1:failed"), h.ended)
        assertEquals(0, h.collectors(1u), "the frame collector is released")
        assertEquals(listOf("setup:Dictation:Success:app=true"), h.log, "nothing is sent to a session the watch gave up on")
        assertTrue(h.calls.isEmpty(), "the provider never got a recording to decode")

        // The next session runs as if nothing happened.
        h.setupRequests.emit(request(2))
        runCurrent()
        h.frame(2u); h.endRecording(2u); runCurrent()
        h.calls.single().second.complete(TranscriptionResult.Success(words))
        runCurrent()
        assertEquals("result:2:success(take out)", h.log.last())
    }

    @Test
    fun aTranscriptPastTheProtocolBoundsIsDeliveredAsAnError() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1))
        runCurrent()
        h.frame(1u); h.endRecording(1u); runCurrent()
        h.calls.single().second.complete(TranscriptionResult.Success(List(300) { TranscriptionWord("a", 0.9f) }))
        runCurrent()
        assertEquals("result:1:error", h.log.last())
        assertEquals(listOf("1:error"), h.ended)
    }

    @Test
    fun aProviderFailureIsDeliveredAsAnError() = runTest {
        val h = harness()
        h.failure = IllegalStateException("codec")
        h.setupRequests.emit(request(1))
        runCurrent()
        h.frame(1u); h.endRecording(1u); runCurrent()
        assertEquals(listOf("setup:Dictation:Success:app=true", "result:1:error"), h.log)
        assertEquals(listOf("1:error"), h.ended)
    }

    @Test
    fun setupWithoutEncoderInfoIsRefused() = runTest {
        val h = harness()
        h.setupRequests.emit(request(1, encoderInfo = null))
        runCurrent()
        assertEquals(listOf("setup:Dictation:FailInvalidMessage:app=true"), h.log)
        assertTrue(h.calls.isEmpty())
        assertTrue(h.ended.isEmpty())
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
