package com.anopticlabs.gravel.app

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.MIN_RECORDING_MILLIS
import com.anopticlabs.gravel.notebook.capture.RecordingBuffer
import com.anopticlabs.gravel.notebook.pipeline.CAPTURE_SAMPLE_RATE
import com.anopticlabs.gravel.notebook.pipeline.IngestResult
import com.anopticlabs.gravel.notebook.storage.DeviceStateFile
import com.anopticlabs.gravel.ring.protocol.RingRecording
import com.anopticlabs.gravel.ring.sync.RingRecordingSink
import com.anopticlabs.gravel.ring.sync.RingSyncStore
import coredevices.resampler.Resampler
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

/** Ring imports wait while this many ring recordings are queued or kept. */
const val MAX_RING_RECORDINGS_QUEUED = 50

/** Ring imports wait once this many ring recordings were stored in the past hour. */
const val MAX_RING_RECORDINGS_PER_HOUR = 60

/** A ring time before this (2025-01-01 UTC) is taken as one from a ring clock the phone never set. */
private const val RING_TIME_FLOOR_MILLIS = 1_735_689_600_000L

/**
 * Takes the ring's finished recordings into the Notebook as 16 kHz WAV, dated by the ring's clock, or by the
 * phone's when the ring gives no time, one before [RING_TIME_FLOOR_MILLIS] or one ahead of the phone.
 * Recordings under [MIN_RECORDING_MILLIS] are dropped as taps. While the Notebook does not take recordings, at
 * either limit, or when the Notebook could not keep a recording, it answers false, so the recordings stay on
 * the ring for a later sync. The hourly count spans syncs only when one instance serves every sync in the process.
 */
class NotebookRingSink(
    private val notebookOn: suspend () -> Boolean,
    private val ringRecordingsQueued: suspend () -> Int,
    private val ingest: suspend (wav: ByteArray, capturedAtMillis: Long) -> IngestResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val marks: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : RingRecordingSink {
    private val storedInLastHour = ArrayDeque<ComparableTimeMark>()

    override suspend fun store(recording: RingRecording, firstIndex: Int): Boolean =
        store(recording.capturedAtUnixSeconds, recording.sampleRate, recording.samples, firstIndex)

    internal suspend fun store(capturedAtUnixSeconds: Long?, sampleRate: Int, samples: ShortArray, firstIndex: Int): Boolean {
        val millis = samples.size * 1000L / sampleRate
        if (!notebookOn()) {
            logger.i { "recording at collection $firstIndex left on the ring: the Notebook is off" }
            return false
        }
        if (millis < MIN_RECORDING_MILLIS) {
            logger.i { "recording at collection $firstIndex dropped: $millis ms" }
            return true
        }
        if (ringRecordingsQueued() >= MAX_RING_RECORDINGS_QUEUED || storedInPastHour() >= MAX_RING_RECORDINGS_PER_HOUR) {
            logger.i { "recording at collection $firstIndex left on the ring: too many ring recordings waiting" }
            return false
        }
        val now = clock()
        val capturedAt = capturedAtUnixSeconds?.times(1000)?.takeIf { it in RING_TIME_FLOOR_MILLIS..now } ?: now
        val wav = withContext(Dispatchers.Default) { ringWav(sampleRate, samples) }
        val result = ingest(wav, capturedAt)
        logger.i { "recording at collection $firstIndex, $millis ms: $result" }
        if (result == IngestResult.LOST) return false
        synchronized(storedInLastHour) { storedInLastHour.addLast(marks.markNow()) }
        return true
    }

    private fun storedInPastHour(): Int = synchronized(storedInLastHour) {
        while (storedInLastHour.isNotEmpty() && storedInLastHour.first().elapsedNow() >= 1.hours) storedInLastHour.removeFirst()
        storedInLastHour.size
    }

    private companion object {
        val logger = Logger.withTag("NotebookRingSink")
    }
}

/** [samples] at [sampleRate] Hz as a mono WAV at the Notebook's rate, which the drain's decoder reads whatever rate the ring gave. */
internal fun ringWav(sampleRate: Int, samples: ShortArray): ByteArray {
    val resampled = if (sampleRate == CAPTURE_SAMPLE_RATE) samples else Resampler(sampleRate, CAPTURE_SAMPLE_RATE).process(samples)
    return RecordingBuffer(resampled.size).apply { append(resampled, resampled.size) }.toWav()
}

/**
 * A ring's sync position in the no-backup directory, kept per ring under its Bluetooth address, so pairing the
 * same ring again resumes where its last sync ended. A saved position that does not read back as one throws,
 * so the sync stops rather than importing the ring again; a count that does not reads as none, which costs one
 * extra sync. A value that cannot be saved throws, ending the sync.
 */
class RingSyncFiles private constructor(private val dir: File, key: String) : RingSyncStore {
    private val nextName = "ring_${key}_next"
    private val next = DeviceStateFile(dir, nextName, maxChars = 5)
    private val count = DeviceStateFile(dir, "ring_${key}_count", maxChars = 3)

    override suspend fun nextIndex(): Int? = withContext(Dispatchers.IO) {
        val saved = next.read()
        if (saved == null && !File(dir, nextName).exists()) return@withContext null
        saved?.toIntOrNull()?.takeIf { it in 0..MAX_INDEX } ?: throw IOException("the ring's saved position is unreadable")
    }

    override suspend fun setNextIndex(index: Int) = write(next, index, MAX_INDEX)

    override suspend fun lastCount(): Int? = withContext(Dispatchers.IO) { count.read()?.toIntOrNull()?.takeIf { it in 0..MAX_COUNT } }

    override suspend fun setLastCount(count: Int) = write(this.count, count, MAX_COUNT)

    private suspend fun write(file: DeviceStateFile, value: Int, max: Int) {
        require(value in 0..max) { "$value outside 0..$max" }
        if (!withContext(Dispatchers.IO) { file.write(value.toString()) }) throw IOException("the ring's sync position was not saved")
    }

    companion object {
        private const val MAX_INDEX = 0xFFFF
        private const val MAX_COUNT = 0xFF
        private val ADDRESS = Regex("^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$")

        /** The position of the ring at [address] (upper-case hex, colon-separated), kept in [dir]. */
        fun of(dir: File, address: String): RingSyncFiles {
            require(ADDRESS.matches(address)) { "not a Bluetooth address" }
            return RingSyncFiles(dir, address.replace(":", ""))
        }
    }
}
