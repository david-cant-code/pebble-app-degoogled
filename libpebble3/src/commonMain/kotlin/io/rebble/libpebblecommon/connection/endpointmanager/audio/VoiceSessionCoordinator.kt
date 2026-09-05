package io.rebble.libpebblecommon.connection.endpointmanager.audio

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.packets.Result
import io.rebble.libpebblecommon.packets.SessionType
import io.rebble.libpebblecommon.services.VoiceService
import io.rebble.libpebblecommon.voice.DEADLINE_MARGIN
import io.rebble.libpebblecommon.voice.DICTATION_DEADLINE
import io.rebble.libpebblecommon.voice.PEBBLE_FW_RECORDING_CAP
import io.rebble.libpebblecommon.voice.PEBBLE_FW_TRANSCRIPTION_TIMEOUT
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.boundedForProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * The watch dictation session state machine, with every effect injected
 * so it runs under a virtual clock in tests. [VoiceSessionManager] wires
 * it to the voice and audio protocol services.
 *
 * Firmware contract this is built against (PebbleOS `voice.c`): the watch
 * records for at most [PEBBLE_FW_RECORDING_CAP], gives the phone
 * [PEBBLE_FW_TRANSCRIPTION_TIMEOUT] from the end of the recording to
 * answer, drops any result that arrives later, and after its error dialog
 * starts a brand-new session on its own.
 *
 * Three fork behaviours follow from that contract:
 *  - Sessions never cancel each other. The watch's automatic retry is a
 *    new setup request, and the decode of the session it replaces must
 *    be allowed to finish.
 *  - The deadline is owned here, measured from the end of the recording:
 *    a decode that overruns it is reported to the watch as a recognizer
 *    error [DEADLINE_MARGIN] before the watch would time out itself, and
 *    the decode runs on so the speed record still sees how long it really
 *    took. Its late result goes nowhere; the watch's retry runs as a
 *    fresh session.
 *  - A recording the watch has not ended [RECORDING_BOUND] after the
 *    setup is abandoned from the phone side, so a stop packet that never
 *    arrives cannot hold the session, its buffer and its provider call
 *    open for the life of the connection.
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
    private val provider: TranscriptionProvider,
    private val deadline: Duration = DICTATION_DEADLINE,
    private val onSessionStarted: (VoiceService.SessionSetupRequest) -> Unit = {},
    private val onSessionEnded: (VoiceService.SessionSetupRequest, TranscriptionResult) -> Unit = { _, _ -> },
) {
    companion object {
        private val logger = Logger.withTag("VoiceSession")

        /**
         * How long after the setup a recording may go on without its stop
         * packet before the session is abandoned: the watch's own clock
         * has run out by then even for a recording that used the whole
         * cap, so no answer can reach it any more.
         */
        val RECORDING_BOUND = PEBBLE_FW_RECORDING_CAP + PEBBLE_FW_TRANSCRIPTION_TIMEOUT
    }

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
        if (withTimeoutOrNull(RECORDING_BOUND) { audioEnded.await() } == null) {
            // Nothing is sent: the watch gave up on this session before the
            // bound, and the provider has not started a decode yet, since it
            // reads the whole recording before it does.
            logger.w { "Voice session ${request.sessionId} recording not ended after $RECORDING_BOUND; abandoning it" }
            transcription.cancelAndJoin()
            collector.cancelAndJoin()
            onSessionEnded(request, TranscriptionResult.Failed)
            return
        }
        val result = withTimeoutOrNull(deadline) { transcription.await() }?.boundedForProtocol()
        if (result != null) {
            logger.i { "Voice session ${request.sessionId} completed with result: ${describe(result)}" }
            sendDictationResult(sessionId, result, request.appUuid)
            onSessionEnded(request, result)
            return
        }

        // Lost: the watch is told now, one margin before its own clock runs
        // out. The decode keeps running so the speed record sees its real
        // duration; its result is only logged.
        logger.w { "Voice session ${request.sessionId} missed the ${deadline} deadline; reporting failure and keeping the decode" }
        sendDictationResult(sessionId, TranscriptionResult.Failed, request.appUuid)
        onSessionEnded(request, TranscriptionResult.Failed)
        val late = transcription.await()
        logger.i { "Lost voice session ${request.sessionId} finished late with ${describe(late)}" }
    }

    private fun describe(result: TranscriptionResult): String = when (result) {
        is TranscriptionResult.Success -> "Success, ${result.words.size} words"
        is TranscriptionResult.Error -> "Error, ${result.message}"
        is TranscriptionResult.Disabled -> "Disabled"
        is TranscriptionResult.Failed -> "Failed"
        is TranscriptionResult.ConnectionError -> "ConnectionError"
    }
}
