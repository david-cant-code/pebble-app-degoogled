package coredevices.pebble.services

import co.touchlab.kermit.Logger
import coredevices.speex.SpeexCodec
import coredevices.speex.SpeexDecodeResult
import coredevices.util.CoreConfigFlow
import coredevices.util.transcription.HybridTranscriptionService
import coredevices.util.transcription.STTLanguage
import coredevices.util.transcription.TranscriptionException
import coredevices.util.transcription.TranscriptionSessionStatus
import coredevices.util.transcription.formatSessionDiagnostics
import io.ktor.utils.io.CancellationException
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.TranscriptionWord
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal expect fun tempTranscriptionDirectory(): Path
class HybridTranscription(
    private val service: HybridTranscriptionService,
    private val libPebbleLazy: Lazy<LibPebble>,
    private val coreConfigFlow: CoreConfigFlow,
): TranscriptionProvider {
    private val logger = Logger.withTag("HybridTranscription")

    private companion object {
        /**
         * Longer than any decode the catalog models take on a phone-class
         * CPU and than the session manager's replay window, so it never
         * cuts a decode the watch's retry could still use.
         */
        val SAFETY_BOUND = 60.seconds
    }

    override suspend fun canServeSession(): Boolean {
        service.earlyInit()
        return service.isAvailable()
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    override suspend fun transcribe(
        encoderInfo: VoiceEncoderInfo,
        audioFrames: Flow<UByteArray>,
        isNotificationReply: Boolean
    ): TranscriptionResult {
        require(encoderInfo is VoiceEncoderInfo.Speex) {
            "Local transcription only supports Speex encoding, got ${encoderInfo::class.simpleName}"
        }

        val speex = SpeexCodec(
            sampleRate = encoderInfo.sampleRate,
            bitRate = encoderInfo.bitRate,
            frameSize = encoderInfo.frameSize
        )
        val decodedBuffer = Buffer()
        val pcm = ByteArray(encoderInfo.frameSize * Short.SIZE_BYTES)
        withContext(Dispatchers.IO) {
            audioFrames.collect { frame ->
                val result =
                    speex.decodeFrame(frame.asByteArray(), pcm, hasHeaderByte = true)
                if (result != SpeexDecodeResult.Success) {
                    error("Failed to decode Speex frame: $result")
                }
                decodedBuffer.write(pcm)
            }
        }
        // The firmware's result clock starts when the recording ends, so the
        // session line measures from here; the engine line (in the whisper
        // service) covers only the decode itself.
        val audioSeconds = decodedBuffer.size / (encoderInfo.sampleRate.toDouble() * Short.SIZE_BYTES)
        val audioEnded = TimeSource.Monotonic.markNow()
        var outcome = "error"
        return try {
            val recentContacts = if (isNotificationReply) {
                libPebbleLazy.value.mostRecentNotificationParticipants(limit = 10).first().takeIf { it.isNotEmpty() }?.flatMap {
                    it.split(" ", limit = 2)
                }
            } else null
            // The watch's dictation deadline is owned by the session manager
            // in libpebble3, which reports the loss to the watch and keeps
            // this decode running for the watch's retry. This bound is only
            // the backstop against a decode that never returns.
            val result = withTimeout(SAFETY_BOUND) {
                service.transcribe(
                    audioStreamFrames = flow {
                        val totalBytes = decodedBuffer.size.toInt()
                        val chunkSize = encoderInfo.sampleRate * Short.SIZE_BYTES // 1 second of audio
                        var bytesRead = 0
                        while (bytesRead < totalBytes) {
                            val bytesToRead = minOf(chunkSize.toInt(), totalBytes - bytesRead)
                            val chunk = decodedBuffer.readByteArray(bytesToRead)
                            emit(chunk)
                            bytesRead += bytesToRead
                        }
                    }.flowOn(Dispatchers.IO),
                    language = STTLanguage.fromCodeOrAutomatic(coreConfigFlow.value.sttConfig.spokenLanguage),
                    contentContext = if (isNotificationReply) "Reply to Instant Message" else null,
                    dictionaryContext = recentContacts,
                    sampleRate = encoderInfo.sampleRate.toInt(),
                    encoding = coredevices.util.AudioEncoding.PCM_16BIT,
                ).filterIsInstance<TranscriptionSessionStatus.Transcription>().first()
            }
            val words = result.text.trim().split(" ").map {
                TranscriptionWord(
                    word = it,
                    confidence = 0.9f
                )
            }
            outcome = "ok:${words.size}words"
            TranscriptionResult.Success(words)
        } catch (_: TimeoutCancellationException) {
            // Generic failure on the watch; ConnectionError would render as
            // "No internet connection", which a local decode never is.
            outcome = "timeout"
            TranscriptionResult.Error("Transcription timed out")
        } catch (e: CancellationException) {
            outcome = "cancelled"
            throw e
        } catch (_: NoSuchElementException) {
            outcome = "no_result"
            TranscriptionResult.Error("No transcription result received")
        } catch (_: TranscriptionException.NoSpeechDetected) {
            outcome = "no_speech"
            TranscriptionResult.Success(emptyList())
        } catch (_: TranscriptionException.TranscriptionRequiresDownload) {
            outcome = "disabled"
            TranscriptionResult.Disabled
        } catch (e: TranscriptionException) {
            outcome = "error:${e::class.simpleName}"
            TranscriptionResult.Error("Transcription failed: ${e.message}")
        } finally {
            decodedBuffer.close()
            logger.i {
                formatSessionDiagnostics(
                    audioSeconds = audioSeconds,
                    sinceAudioEndMillis = audioEnded.elapsedNow().inWholeMilliseconds,
                    outcome = outcome,
                )
            }
        }
    }
}
