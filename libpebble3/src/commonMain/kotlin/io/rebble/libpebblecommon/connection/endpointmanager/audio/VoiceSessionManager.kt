package io.rebble.libpebblecommon.connection.endpointmanager.audio

import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.DictationResult
import io.rebble.libpebblecommon.packets.Result
import io.rebble.libpebblecommon.packets.Sentence
import io.rebble.libpebblecommon.packets.SessionSetupResult
import io.rebble.libpebblecommon.packets.SessionType
import io.rebble.libpebblecommon.packets.VoiceAttribute
import io.rebble.libpebblecommon.packets.VoiceAttributeType
import io.rebble.libpebblecommon.services.AudioStreamService
import io.rebble.libpebblecommon.services.VoiceService
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.TranscriptionWord
import io.rebble.libpebblecommon.voice.toProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Protocol adapter for watch dictation: decodes the frame stream and
 * encodes the setup and dictation result packets, and hands the session
 * logic itself to [VoiceSessionCoordinator] (the deadline, the bound on a
 * recording the watch never ends, and the rule that a new setup supersedes
 * the session in flight).
 */
class VoiceSessionManager(
    private val voiceService: VoiceService,
    private val audioStreamService: AudioStreamService,
    private val watchScope: ConnectionCoroutineScope,
    private val transcriptionProvider: TranscriptionProvider,
) {
    private val _currentSession = MutableStateFlow<CurrentSession?>(null)
    val currentSession = _currentSession.asStateFlow()

    data class CurrentSession (
        val request: VoiceService.SessionSetupRequest,
        val result: CompletableDeferred<TranscriptionResult>
    )

    private fun makeSetupResult(
        sessionType: SessionType,
        result: Result,
        appInitiated: Boolean
    ): SessionSetupResult {
        val setupResult = SessionSetupResult(sessionType, result)
        if (appInitiated) {
            setupResult.flags.set(1u) // Indicates app-initiated session
        }
        return setupResult
    }

    private fun makeDictationResult(
        sessionId: UShort,
        result: Result,
        words: Iterable<TranscriptionWord>?,
        appUuid: Uuid
    ): DictationResult {
        return DictationResult(
            sessionId,
            result,
            buildList {
                words?.let {
                    add(VoiceAttribute(
                        id = VoiceAttributeType.Transcription.value,
                        content = VoiceAttribute.Transcription(
                            sentences = listOf(
                                Sentence(words.map { it.toProtocol() })
                            )
                        )
                    ))
                }
                if (appUuid != Uuid.NIL) {
                    add(VoiceAttribute(
                        id = VoiceAttributeType.AppUuid.value,
                        content = VoiceAttribute.AppUuid().apply {
                            uuid.set(appUuid)
                        }
                    ))
                }
            }
        ).apply {
            if (appUuid != Uuid.NIL) {
                flags.set(1u) // Indicates app-initiated session
            }
        }
    }

    private val coordinator = VoiceSessionCoordinator(
        scope = watchScope,
        setupRequests = voiceService.sessionSetupRequests,
        framesFor = { sessionId ->
            audioStreamService.dataFlowForSession(sessionId)
                .transform { transfer ->
                    transfer.frames
                        .map { frame -> frame.data.get() }
                        .forEach { emit(it) }
                }
        },
        sendSetupResult = { sessionType, result, appInitiated ->
            voiceService.send(makeSetupResult(sessionType, result, appInitiated))
        },
        sendDictationResult = { sessionId, result, appUuid ->
            voiceService.send(
                makeDictationResult(
                    sessionId = sessionId,
                    result = result.toProtocol(),
                    words = (result as? TranscriptionResult.Success)?.words,
                    appUuid = appUuid,
                )
            )
        },
        provider = transcriptionProvider,
        onSessionStarted = { request ->
            _currentSession.value = CurrentSession(request, CompletableDeferred())
        },
        onSessionEnded = { request, result ->
            _currentSession.value?.let { current ->
                if (current.request.sessionId == request.sessionId) {
                    current.result.complete(result)
                    _currentSession.value = null
                }
            }
        },
    )

    fun init() {
        watchScope.launch { coordinator.run() }
    }
}
