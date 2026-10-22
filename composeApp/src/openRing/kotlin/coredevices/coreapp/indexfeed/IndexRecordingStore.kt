package coredevices.coreapp.indexfeed

import co.touchlab.kermit.Logger
import coredevices.libindex.database.repository.RingIterationStore
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface IndexRecordingStore {
    val recordings: StateFlow<List<IndexRecording>>

    suspend fun ensureLoaded()

    suspend fun accept(incoming: IncomingRecording): AcceptResult

    suspend fun updateTranscript(id: String, state: TranscriptState, transcript: String?, detail: String?)

    suspend fun delete(id: String)

    suspend fun get(id: String): IndexRecording?
}

/**
 * Recordings keyed by ring, iteration, and collection index.
 * [markTransfersAsPreviousIndexIteration] advances the iteration, matching
 * the transfer table libindex updates when a re-pair resets that counter.
 */
class FileIndexRecordingStore(
    private val directory: Path,
    private val io: CoroutineContext = Dispatchers.IO,
) : IndexRecordingStore, RingIterationStore {
    private val log = Logger.withTag("IndexRecordings")
    private val mutex = Mutex()
    private val items = linkedMapOf<String, IndexRecording>()
    private var iteration = 0
    private var loaded = false
    private val _recordings = MutableStateFlow<List<IndexRecording>>(emptyList())
    override val recordings: StateFlow<List<IndexRecording>> = _recordings

    override suspend fun ensureLoaded() {
        withContext(io) {
            mutex.withLock { loadLocked() }
        }
    }

    override suspend fun accept(incoming: IncomingRecording): AcceptResult = withContext(io) {
        mutex.withLock {
            loadLocked()
            val id = recordingId(incoming.ringId, iteration, incoming.collectionIndex)
            items[id]?.let { return@withContext AcceptResult.Duplicate(it) }
            val fileName = "$id.wav".replace("|", "-")
            val path = Path(directory, fileName)
            SystemFileSystem.sink(path).buffered().use { sink ->
                sink.write(incoming.wav)
            }
            val recording = IndexRecording(
                id = id,
                ringId = normalizeRingId(incoming.ringId),
                iteration = iteration,
                collectionIndex = incoming.collectionIndex,
                recordedAtEpochSeconds = incoming.recordedAtEpochSeconds,
                durationMillis = incoming.durationMillis,
                buttonSequence = incoming.buttonSequence?.takeIf { it.isNotBlank() },
                sampleRate = incoming.sampleRate,
                audioFileName = fileName,
                audioPath = path.toString(),
                transcript = null,
                state = TranscriptState.Pending,
                detail = null,
            )
            items[id] = recording
            publishLocked()
            writeLocked()
            AcceptResult.Inserted(recording)
        }
    }

    override suspend fun updateTranscript(
        id: String,
        state: TranscriptState,
        transcript: String?,
        detail: String?,
    ) {
        withContext(io) {
            mutex.withLock {
                loadLocked()
                val current = items[id] ?: return@withLock
                items[id] = current.copy(state = state, transcript = transcript, detail = detail)
                publishLocked()
                writeLocked()
            }
        }
    }

    override suspend fun delete(id: String) {
        withContext(io) {
            mutex.withLock {
                loadLocked()
                val current = items.remove(id) ?: return@withLock
                val path = Path(current.audioPath)
                if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
                publishLocked()
                writeLocked()
            }
        }
    }

    override suspend fun get(id: String): IndexRecording? = withContext(io) {
        mutex.withLock {
            loadLocked()
            items[id]
        }
    }

    override suspend fun markTransfersAsPreviousIndexIteration() {
        withContext(io) {
            mutex.withLock {
                loadLocked()
                iteration += 1
                writeLocked()
            }
        }
    }

    private fun loadLocked() {
        if (loaded) return
        SystemFileSystem.createDirectories(directory)
        val catalog = Path(directory, CATALOG)
        if (SystemFileSystem.exists(catalog)) {
            val parsed = runCatching {
                catalogJson.decodeFromString<CatalogFile>(
                    SystemFileSystem.source(catalog).buffered().use { it.readString() },
                )
            }.getOrElse {
                log.w(it) { "index catalog did not parse; starting empty" }
                CatalogFile()
            }
            iteration = parsed.iteration
            parsed.recordings.forEach { dto ->
                val recording = dto.toRecording(directory)
                items[recording.id] = recording
            }
        }
        loaded = true
        publishLocked()
    }

    private fun publishLocked() {
        _recordings.value = items.values.sortedWith(
            compareByDescending<IndexRecording> { it.recordedAtEpochSeconds }
                .thenByDescending { it.iteration }
                .thenByDescending { it.collectionIndex },
        )
    }

    private fun writeLocked() {
        val catalog = Path(directory, CATALOG)
        val tmp = Path(directory, "$CATALOG.tmp")
        val body = catalogJson.encodeToString(
            CatalogFile(
                iteration = iteration,
                recordings = items.values.map { it.toDto() },
            ),
        )
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(body) }
        if (SystemFileSystem.exists(catalog)) SystemFileSystem.delete(catalog)
        SystemFileSystem.atomicMove(tmp, catalog)
    }

    private companion object {
        const val CATALOG = "catalog.json"
    }
}

internal fun readFileBytes(path: String): ByteArray? {
    val file = Path(path)
    if (!SystemFileSystem.exists(file)) return null
    return SystemFileSystem.source(file).buffered().use { it.readByteArray() }
}

internal fun readWavPcm(path: String): ByteArray? {
    val file = Path(path)
    if (!SystemFileSystem.exists(file)) return null
    val bytes = SystemFileSystem.source(file).buffered().use { it.readByteArray() }
    if (bytes.size <= 44) return null
    val isWav = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()
    return if (isWav) bytes.copyOfRange(44, bytes.size) else bytes
}

@Serializable
private data class CatalogFile(
    val iteration: Int = 0,
    val recordings: List<RecordingDto> = emptyList(),
)

@Serializable
private data class RecordingDto(
    val id: String,
    val ringId: String,
    val iteration: Int,
    val collectionIndex: Int,
    val recordedAtEpochSeconds: Long,
    val durationMillis: Long,
    val buttonSequence: String? = null,
    val sampleRate: Int,
    val audioFileName: String,
    val transcript: String? = null,
    val state: TranscriptState = TranscriptState.Pending,
    val detail: String? = null,
)

private fun RecordingDto.toRecording(directory: Path) = IndexRecording(
    id = id,
    ringId = ringId,
    iteration = iteration,
    collectionIndex = collectionIndex,
    recordedAtEpochSeconds = recordedAtEpochSeconds,
    durationMillis = durationMillis,
    buttonSequence = buttonSequence,
    sampleRate = sampleRate,
    audioFileName = audioFileName,
    audioPath = Path(directory, audioFileName).toString(),
    transcript = transcript,
    state = state,
    detail = detail,
)

private fun IndexRecording.toDto() = RecordingDto(
    id = id,
    ringId = ringId,
    iteration = iteration,
    collectionIndex = collectionIndex,
    recordedAtEpochSeconds = recordedAtEpochSeconds,
    durationMillis = durationMillis,
    buttonSequence = buttonSequence,
    sampleRate = sampleRate,
    audioFileName = audioFileName,
    transcript = transcript,
    state = state,
    detail = detail,
)

private val catalogJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}
