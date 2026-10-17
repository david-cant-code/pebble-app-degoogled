package coredevices.pebble.services

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.dictation.decodesOnThePhone
import com.anopticlabs.gravel.watchapp.WATCH_SPEEX_SAMPLE_RATE
import com.anopticlabs.gravel.watchapp.decodeWatchSpeex
import coredevices.util.CoreConfigFlow
import coredevices.util.models.CactusSTTMode
import coredevices.util.transcription.WhisperTranscriptionService
import coredevices.util.transcription.TranscriptionException
import io.ktor.utils.io.CancellationException
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.TranscriptionWord
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Mode-aware [TranscriptionProvider] dispatching between [HybridTranscription] (cloud/local via
 * whisper + WisprFlow) and [RebbleAsrTranscription] (Rebble's ASR). Owns fallback orchestration
 * for `RebbleFirst`/`RebbleFallback` modes (which buffer Speex frames so they can be replayed
 * across both backends).
 */
class STTRouter(
    private val hybrid: HybridTranscription,
    private val rebble: RebbleAsrTranscription,
    private val whisperService: WhisperTranscriptionService,
    private val coreConfigFlow: CoreConfigFlow,
) : TranscriptionProvider {
    companion object {
        private val logger = Logger.withTag("STTRouter")
        private val rebbleModes = setOf(
            CactusSTTMode.RebbleOnly,
            CactusSTTMode.RebbleFirst,
            CactusSTTMode.RebbleFallback,
        )
    }

    override suspend fun canServeSession(): Boolean {
        val mode = coreConfigFlow.value.sttConfig.mode
        return when (mode) {
            CactusSTTMode.RebbleOnly -> rebble.isAvailable()
            CactusSTTMode.RebbleFirst, CactusSTTMode.RebbleFallback ->
                rebble.isAvailable() || hybrid.canServeSession()
            else -> hybrid.canServeSession()
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    override suspend fun transcribe(
        encoderInfo: VoiceEncoderInfo,
        audioFrames: Flow<UByteArray>,
        isNotificationReply: Boolean
    ): TranscriptionResult {
        val mode = coreConfigFlow.value.sttConfig.mode
        if (mode !in rebbleModes) {
            return hybrid.transcribe(encoderInfo, audioFrames, isNotificationReply)
        }

        require(encoderInfo is VoiceEncoderInfo.Speex) {
            "Rebble routing only supports Speex encoding, got ${encoderInfo::class.simpleName}"
        }

        val frames: List<UByteArray> = audioFrames.toList()
        if (frames.isEmpty()) {
            return TranscriptionResult.Error("No audio frames received")
        }

        return when (mode) {
            CactusSTTMode.RebbleOnly -> rebble.transcribe(
                encoderInfo,
                flowOf(*frames.toTypedArray()),
                isNotificationReply,
            )
            CactusSTTMode.RebbleFirst -> {
                val rebbleResult = rebble.transcribe(
                    encoderInfo,
                    flowOf(*frames.toTypedArray()),
                    isNotificationReply,
                )
                if (rebbleResult is TranscriptionResult.Success && rebbleResult.words.isNotEmpty()) {
                    return rebbleResult
                }
                logger.w { "Rebble ASR returned $rebbleResult, falling back to local" }
                runLocalFromSpeex(encoderInfo, frames)
            }
            CactusSTTMode.RebbleFallback -> {
                val localResult = runLocalFromSpeex(encoderInfo, frames)
                if (localResult is TranscriptionResult.Success && localResult.words.isNotEmpty()) {
                    return localResult
                }
                logger.w { "Local STT returned $localResult, falling back to Rebble" }
                rebble.transcribe(
                    encoderInfo,
                    flowOf(*frames.toTypedArray()),
                    isNotificationReply,
                )
            }
            else -> TranscriptionResult.Error("Unhandled mode $mode")
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private suspend fun runLocalFromSpeex(
        encoderInfo: VoiceEncoderInfo.Speex,
        frames: List<UByteArray>,
    ): TranscriptionResult {
        if (!decodesOnThePhone(encoderInfo)) {
            logger.w { "Local STT refused a session declaring ${encoderInfo.sampleRate} Hz and ${encoderInfo.frameSize} samples a frame" }
            return TranscriptionResult.Error("Unsupported Speex frame size or sample rate")
        }
        val pcm = withContext(Dispatchers.IO) { decodeWatchSpeex(encoderInfo, frames) }
        return try {
            val text = whisperService.transcribeLocalForFallback(
                audio = pcm,
                sampleRate = WATCH_SPEEX_SAMPLE_RATE,
            )
            TranscriptionResult.Success(
                words = text.trim().split(" ").map { TranscriptionWord(it, 0.9f) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: TranscriptionException.NoSpeechDetected) {
            TranscriptionResult.Success(emptyList())
        } catch (_: TranscriptionException.TranscriptionRequiresDownload) {
            TranscriptionResult.Disabled
        } catch (e: Exception) {
            TranscriptionResult.Error("Local transcription failed: ${e.message}")
        }
    }
}
