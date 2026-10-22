package coredevices.coreapp.indexfeed

import coredevices.util.transcription.TranscriptionException
import coredevices.util.transcription.TranscriptionService
import coredevices.util.transcription.TranscriptionSessionStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class IndexTranscript {
    data class Text(val text: String) : IndexTranscript()
    data object Unavailable : IndexTranscript()
    data class Failed(val reason: String) : IndexTranscript()
}

interface IndexTranscriber {
    suspend fun transcribe(pcm: ByteArray, sampleRate: Int): IndexTranscript
}

/**
 * Routes through the speech service the rest of the app already uses:
 * on-device whisper unless Settings selects the self-hosted server.
 * A missing model stays on the recording as not transcribed.
 */
class HybridIndexTranscriber(
    private val service: TranscriptionService,
) : IndexTranscriber {
    override suspend fun transcribe(pcm: ByteArray, sampleRate: Int): IndexTranscript {
        if (!service.isAvailable()) return IndexTranscript.Unavailable
        return try {
            val status = service.transcribe(
                audioStreamFrames = flowOf(pcm),
                sampleRate = sampleRate,
            ).filterIsInstance<TranscriptionSessionStatus.Transcription>().first()
            IndexTranscript.Text(status.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptionException.TranscriptionRequiresDownload) {
            IndexTranscript.Unavailable
        } catch (e: TranscriptionException.TranscriptionServiceUnavailable) {
            IndexTranscript.Unavailable
        } catch (e: TranscriptionException.NoSpeechDetected) {
            IndexTranscript.Text("")
        } catch (e: Exception) {
            IndexTranscript.Failed(e.message ?: "Transcription failed")
        }
    }
}

class IndexTranscriptionPipeline(
    private val store: IndexRecordingStore,
    private val transcriber: IndexTranscriber,
    private val scope: CoroutineScope,
) {
    private val gate = Mutex()
    private val running = mutableSetOf<String>()

    fun enqueue(id: String, force: Boolean = false) {
        scope.launch {
            val claimed = gate.withLock {
                if (id in running) false else {
                    running += id
                    true
                }
            }
            if (!claimed) return@launch
            try {
                transcribe(id, force)
            } finally {
                gate.withLock { running -= id }
            }
        }
    }

    fun retry(id: String) {
        enqueue(id, force = true)
    }

    suspend fun resumePending() {
        store.ensureLoaded()
        store.recordings.value
            .filter { it.state == TranscriptState.Pending }
            .forEach { enqueue(it.id) }
    }

    private suspend fun transcribe(id: String, force: Boolean) {
        val current = store.get(id) ?: return
        if (!force && current.state == TranscriptState.Done) return
        store.updateTranscript(id, TranscriptState.Pending, transcript = null, detail = null)
        val pcm = readWavPcm(current.audioPath)
        if (pcm == null || pcm.isEmpty()) {
            store.updateTranscript(id, TranscriptState.NotTranscribed, null, "missing-audio")
            return
        }
        val result = try {
            transcriber.transcribe(pcm, current.sampleRate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IndexTranscript.Failed(e.message ?: "Transcription failed")
        }
        when (result) {
            is IndexTranscript.Text -> store.updateTranscript(
                id,
                TranscriptState.Done,
                result.text,
                if (result.text.isBlank()) "no-speech" else null,
            )
            IndexTranscript.Unavailable ->
                store.updateTranscript(id, TranscriptState.NotTranscribed, null, "unavailable")
            is IndexTranscript.Failed ->
                store.updateTranscript(id, TranscriptState.NotTranscribed, null, "failed")
        }
    }
}
