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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

/** Ring imports wait while this many ring recordings are queued or kept. */
const val MAX_RING_RECORDINGS_QUEUED = 50

/** Ring imports wait once this many ring recordings were stored in the past hour. */
const val MAX_RING_RECORDINGS_PER_HOUR = 60

/** Why the sink leaves recordings on the ring: the Notebook is off, one of the two limits, or the Notebook lost one. */
enum class RingWait { NOTEBOOK_OFF, TOO_MANY_WAITING, HOURLY_LIMIT, NOT_KEPT }

/** A ring time before this (2025-01-01 UTC) is taken as one from a ring clock the phone never set. */
private const val RING_TIME_FLOOR_MILLIS = 1_735_689_600_000L

/**
 * Takes the ring's finished recordings into the Notebook as 16 kHz WAV, dated by the ring's clock, or by the
 * phone's when the ring gives no time, one before [RING_TIME_FLOOR_MILLIS] or one ahead of the phone.
 * Recordings under [MIN_RECORDING_MILLIS] are dropped as taps. While the Notebook does not take recordings, at
 * either limit, or when the Notebook could not keep a recording, it answers false, so the recordings stay on
 * the ring for a later sync, with the reason in [lastWait]. The hourly count spans syncs only when one instance serves
 * every sync in the process.
 */
class NotebookRingSink(
    private val notebookOn: suspend () -> Boolean,
    private val ringRecordingsQueued: suspend () -> Int,
    private val ingest: suspend (wav: ByteArray, capturedAtMillis: Long) -> IngestResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val marks: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : RingRecordingSink {
    private val storedInLastHour = ArrayDeque<ComparableTimeMark>()
    private val importedCount = AtomicInteger()

    /** Why the last recording was left on the ring, or null once one was taken. */
    @Volatile
    var lastWait: RingWait? = null
        private set

    /** Recordings this instance passed to the Notebook. */
    val imported: Int get() = importedCount.get()

    override suspend fun store(recording: RingRecording, firstIndex: Int): Boolean =
        store(recording.capturedAtUnixSeconds, recording.sampleRate, recording.samples, firstIndex)

    internal suspend fun store(capturedAtUnixSeconds: Long?, sampleRate: Int, samples: ShortArray, firstIndex: Int): Boolean {
        val millis = samples.size * 1000L / sampleRate
        if (!notebookOn()) return leftOnRing(firstIndex, RingWait.NOTEBOOK_OFF)
        if (millis < MIN_RECORDING_MILLIS) {
            logger.i { "recording at collection $firstIndex dropped: $millis ms" }
            return true
        }
        limitReached()?.let { return leftOnRing(firstIndex, it) }
        val now = clock()
        val capturedAt = capturedAtUnixSeconds?.times(1000)?.takeIf { it in RING_TIME_FLOOR_MILLIS..now } ?: now
        val wav = withContext(Dispatchers.Default) { ringWav(sampleRate, samples) }
        val result = ingest(wav, capturedAt)
        logger.i { "recording at collection $firstIndex, $millis ms: $result" }
        if (result == IngestResult.LOST) return leftOnRing(firstIndex, RingWait.NOT_KEPT)
        synchronized(storedInLastHour) { storedInLastHour.addLast(marks.markNow()) }
        importedCount.incrementAndGet()
        lastWait = null
        return true
    }

    /** Why recordings would stay on the ring now, checked before a sync connects; null when the sink takes them. */
    suspend fun waitReason(): RingWait? = if (!notebookOn()) RingWait.NOTEBOOK_OFF else limitReached()

    private suspend fun limitReached(): RingWait? = when {
        ringRecordingsQueued() >= MAX_RING_RECORDINGS_QUEUED -> RingWait.TOO_MANY_WAITING
        storedInPastHour() >= MAX_RING_RECORDINGS_PER_HOUR -> RingWait.HOURLY_LIMIT
        else -> null
    }

    private fun leftOnRing(firstIndex: Int, wait: RingWait): Boolean {
        logger.i { "recording at collection $firstIndex left on the ring: $wait" }
        lastWait = wait
        return false
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

/** A ring's Bluetooth address as Android writes it: upper-case hex, colon-separated. */
internal val RING_ADDRESS = Regex("^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$")

/** The saved position exists but does not read back as one; syncing that ring stops until the user starts over. */
class RingPositionUnreadableException : IOException("the ring's saved position is unreadable")

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
        saved?.toIntOrNull()?.takeIf { it in 0..MAX_INDEX } ?: throw RingPositionUnreadableException()
    }

    /** Forgets the position and count, so the next sync imports everything still on the ring. */
    suspend fun startOver() = withContext(Dispatchers.IO) {
        if (!next.clear() || !count.clear()) throw IOException("the ring's sync position was not cleared")
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

        /** The position of the ring at [address] (upper-case hex, colon-separated), kept in [dir]. */
        fun of(dir: File, address: String): RingSyncFiles {
            require(RING_ADDRESS.matches(address)) { "not a Bluetooth address" }
            return RingSyncFiles(dir, address.replace(":", ""))
        }
    }
}
