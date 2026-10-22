package coredevices.coreapp.indexfeed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class IndexFeedViewModel(
    private val store: IndexRecordingStore,
    private val pipeline: IndexTranscriptionPipeline,
    private val player: IndexAudioPlayer,
) {
    val recordings: StateFlow<List<IndexRecording>> = store.recordings
    val playingPath: StateFlow<String?> = player.playingPath

    suspend fun ensureLoaded() = store.ensureLoaded()

    suspend fun delete(id: String) {
        val path = store.get(id)?.audioPath
        if (path != null && player.playingPath.value == path) player.stop()
        store.delete(id)
    }

    fun retry(id: String) = pipeline.retry(id)

    fun togglePlayback(path: String) = player.toggle(path)

    suspend fun exportArchive(): ByteArray = withContext(Dispatchers.IO) {
        store.ensureLoaded()
        val rows = recordings.value.map { recording ->
            ExportRecording(
                ringId = recording.ringId,
                iteration = recording.iteration,
                collectionIndex = recording.collectionIndex,
                recordedAtEpochSeconds = recording.recordedAtEpochSeconds,
                durationMillis = recording.durationMillis,
                buttonSequence = recording.buttonSequence,
                state = recording.state,
                transcript = recording.transcript,
                audio = readFileBytes(recording.audioPath),
            )
        }
        exportRecordingsZip(rows)
    }
}

interface IndexAudioPlayer {
    val playingPath: StateFlow<String?>
    fun toggle(path: String)
    fun stop()
}
