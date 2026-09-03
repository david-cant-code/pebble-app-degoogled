package io.rebble.libpebblecommon.connection.endpointmanager.audio

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.packets.Result
import io.rebble.libpebblecommon.packets.SessionType
import io.rebble.libpebblecommon.services.VoiceService
import io.rebble.libpebblecommon.voice.PEBBLE_FW_TRANSCRIPTION_TIMEOUT
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * The watch dictation session state machine, with every effect injected
 * so it runs under a virtual clock in tests. [VoiceSessionManager] wires
 * it to the voice and audio protocol services.
 *
 * Firmware contract this is built against (PebbleOS `voice.c`): the watch
 * gives the phone [PEBBLE_FW_TRANSCRIPTION_TIMEOUT] from the end of the
 * recording to answer, drops any result that arrives later, and after its
 * error dialog starts a brand-new session on its own. The phone may end a
 * recording early by stopping the audio transfer, which starts the watch's
 * result clock.
 *
 * Three fork behaviours follow from that contract:
 *  - Sessions never cancel each other. The watch's automatic retry is a
 *    new setup request, and the decode of the session it replaces must
 *    be allowed to finish.
 *  - The deadline is owned here, measured from the end of the recording:
 *    a decode that overruns it is reported to the watch as a recognizer
 *    error one second before the watch would time out itself, and the
 *    decode runs on. A late success is kept for the retry.
 *  - A retry from the same app and session type inside [REPLAY_WINDOW]
 *    replays the late transcript: the setup is accepted, the late result
 *    awaited for at most [REPLAY_WAIT], the watch's recording stopped if
 *    it is still running, and the transcript delivered as the retry's
 *    result. Anything else falls through to a normal session.
 *
 * Frames are buffered from the moment the setup is accepted, because the
 * inbound packet flow has no replay and the provider only starts reading
 * once it is called.
 */
internal class VoiceSessionCoordinator(
    private val scope: CoroutineScope,
    private val setupRequests: Flow<VoiceService.SessionSetupRequest>,
    private val framesFor: (sessionId: UShort) -> Flow<UByteArray>,
    private val sendSetupResult: suspend (sessionType: SessionType, result: Result, appInitiated: Boolean) -> Unit,
    private val sendDictationResult: suspend (sessionId: UShort, result: TranscriptionResult, appUuid: Uuid) -> Unit,
    private val sendAudioStop: suspend (sessionId: UShort) -> Unit,
    private val provider: TranscriptionProvider,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val deadline: Duration = PEBBLE_FW_TRANSCRIPTION_TIMEOUT - DEADLINE_MARGIN,
    private val onSessionStarted: (VoiceService.SessionSetupRequest) -> Unit = {},
    private val onSessionEnded: (VoiceService.SessionSetupRequest, TranscriptionResult) -> Unit = { _, _ -> },
) {
    companion object {
        private val logger = Logger.withTag("VoiceSession")

        /** Sent before the watch's own clock runs out, so the phone reports the loss, not the watch. */
        val DEADLINE_MARGIN = 1.seconds

        /** How long a late transcript stays claimable by the watch's retry. */
        val REPLAY_WINDOW = 60.seconds

        /**
         * How long a retry session waits for the late result before running
         * as a normal session: the watch stops its own recording at 15 s,
         * and a stop request must land before that to keep the session
         * inside one result clock.
         */
        val REPLAY_WAIT = 13.seconds

        /**
         * Minimum age of the retry session before its recording is stopped
         * from the phone side; the firmware treats a session shorter than
         * 600 ms as one where speech was never attempted.
         */
        val REPLAY_SETTLE = 1.seconds
    }

    private class LostSession(
        val request: VoiceService.SessionSetupRequest,
        val result: Deferred<TranscriptionResult>,
        val lostAt: TimeMark,
    )

    private val lostMutex = Mutex()
    private var lost: LostSession? = null

    /** Collects setup requests for the life of [scope]; one job per session. */
    suspend fun run() {
        setupRequests.collect { request ->
            scope.launch { handle(request) }
        }
    }

    private fun isNotificationReply(request: VoiceService.SessionSetupRequest): Boolean =
        request.appUuid == Uuid.NIL || request.appUuid == SystemAppIDs.NOTIFICATIONS_APP_UUID

    private suspend fun handle(request: VoiceService.SessionSetupRequest) {
        logger.i { "New voice session started: $request" }
        val appInitiated = request.appUuid != Uuid.NIL
        val encoderInfo = request.encoderInfo
        if (encoderInfo == null) {
            logger.e { "Received voice session setup request without encoder info, cannot handle voice session." }
            sendSetupResult(request.sessionType, Result.FailInvalidMessage, appInitiated)
            return
        }
        if (!provider.canServeSession()) {
            logger.w { "Voice session requested, but speech recognition is disabled or not available" }
            sendSetupResult(request.sessionType, Result.FailDisabled, appInitiated)
            return
        }
        val sessionId = request.sessionId.toUShort()
        onSessionStarted(request)

        // Buffer the recording from before the watch is told to start; the
        // collector runs for the whole session and closes the channel at the
        // end of the transfer, which is the moment the watch's clock starts.
        val frames = Channel<UByteArray>(Channel.UNLIMITED)
        val audioEnded = CompletableDeferred<Unit>()
        val collector = scope.launch {
            try {
                framesFor(sessionId).collect { frames.send(it) }
            } finally {
                frames.close()
                audioEnded.complete(Unit)
            }
        }
        yield()
        sendSetupResult(request.sessionType, Result.Success, appInitiated)
        val setupSentAt = timeSource.markNow()

        val replay = claimReplay(request)
        if (replay != null) {
            logger.i { "Voice session ${request.sessionId} is a retry of a lost session; awaiting its late result" }
            val late = withTimeoutOrNull(REPLAY_WAIT) { replay.result.await() }
            if (late is TranscriptionResult.Success && late.words.isNotEmpty()) {
                val settle = REPLAY_SETTLE - setupSentAt.elapsedNow()
                if (settle > Duration.ZERO) delay(settle)
                if (!audioEnded.isCompleted) sendAudioStop(sessionId)
                logger.i { "Voice session ${request.sessionId} completed with the replayed late result, ${late.words.size} words" }
                sendDictationResult(sessionId, late, request.appUuid)
                collector.cancel()
                onSessionEnded(request, late)
                return
            }
            logger.w { "Late result not usable for the retry ($late); running the session normally" }
        }

        val transcription = scope.async {
            try {
                provider.transcribe(encoderInfo, frames.receiveAsFlow(), isNotificationReply(request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Error during transcription: ${e.message}" }
                TranscriptionResult.Error("Transcription error: ${e.message}")
            }
        }
        audioEnded.await()
        val result = withTimeoutOrNull(deadline) { transcription.await() }
        if (result != null) {
            logger.i { "Voice session ${request.sessionId} completed with result: ${describe(result)}" }
            sendDictationResult(sessionId, result, request.appUuid)
            onSessionEnded(request, result)
            return
        }

        // Lost: the watch is told now, one margin before its own clock runs
        // out, and the decode keeps running for the retry.
        logger.w { "Voice session ${request.sessionId} missed the ${deadline} deadline; reporting failure and keeping the decode" }
        sendDictationResult(sessionId, TranscriptionResult.Failed, request.appUuid)
        onSessionEnded(request, TranscriptionResult.Failed)
        lostMutex.withLock {
            lost = LostSession(request, transcription, timeSource.markNow())
        }
        val late = transcription.await()
        logger.i { "Lost voice session ${request.sessionId} finished late with ${describe(late)}" }
    }

    /**
     * The lost session a new request may replay, consumed on claim: same
     * app and session type, lost inside [REPLAY_WINDOW]. Anything older is
     * dropped here so a stale transcript can never reach an unrelated
     * session.
     */
    private suspend fun claimReplay(request: VoiceService.SessionSetupRequest): LostSession? = lostMutex.withLock {
        val candidate = lost ?: return@withLock null
        lost = null
        candidate.takeIf {
            it.request.appUuid == request.appUuid &&
                it.request.sessionType == request.sessionType &&
                it.lostAt.elapsedNow() <= REPLAY_WINDOW
        }
    }

    private fun describe(result: TranscriptionResult): String = when (result) {
        is TranscriptionResult.Success -> "Success, ${result.words.size} words"
        is TranscriptionResult.Error -> "Error, ${result.message}"
        is TranscriptionResult.Disabled -> "Disabled"
        is TranscriptionResult.Failed -> "Failed"
        is TranscriptionResult.ConnectionError -> "ConnectionError"
    }
}
