package com.anopticlabs.gravel.notebook.sources

import com.anopticlabs.gravel.notebook.pipeline.IngestResult
import com.anopticlabs.gravel.notebook.pipeline.RecordingKeepAlive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * Hands out [chunks] full reads of a counting sample pattern per capture, then [after] on every
 * later read: 0 (nothing yet, as a quiet microphone between buffers) or a negative error.
 */
class FakeMicrophone(
    private val chunks: Int = Int.MAX_VALUE,
    private val after: Int = 0,
    private val events: MutableList<String>? = null,
    private val failStart: Exception? = null,
) : Microphone {
    val reads = AtomicInteger(0)
    val samplesGiven = AtomicInteger(0)

    @Volatile var closed = false

    override fun start(): MicCapture {
        events?.add("mic start")
        failStart?.let { throw it }
        val sessionReads = AtomicInteger(0)
        return object : MicCapture {
            override fun read(into: ShortArray): Int {
                reads.incrementAndGet()
                if (sessionReads.getAndIncrement() >= chunks) {
                    Thread.sleep(1)
                    return after
                }
                val base = samplesGiven.getAndAdd(into.size)
                for (i in into.indices) into[i] = ((base + i) % 30_000).toShort()
                return into.size
            }

            override fun close() {
                closed = true
                events?.add("mic close")
            }
        }
    }
}

class FakeRecordingKeepAlive(private val events: MutableList<String> = CopyOnWriteArrayList()) : RecordingKeepAlive {
    @Volatile var held = false
    @Volatile var failHold = false
    @Volatile var failRelease = false

    val log: List<String> get() = events

    override fun holdRecording() {
        held = true
        events += "hold"
        if (failHold) error("hold refused")
    }

    override fun releaseRecording() {
        held = false
        events += "release"
        if (failRelease) error("release refused")
    }
}

/** A recorder on a real background scope, with what its ingest was given kept for the test. */
class RecorderHarness(
    chunks: Int = Int.MAX_VALUE,
    after: Int = 0,
    failStart: Exception? = null,
    private val result: IngestResult = IngestResult.QUEUED,
    private val preload: suspend () -> Unit = {},
    private val onIngest: suspend (FinishedRecording) -> IngestResult = { result },
    @Volatile var processing: Boolean = false,
) {
    val events: MutableList<String> = CopyOnWriteArrayList()
    val microphone = FakeMicrophone(chunks, after, events, failStart)
    val keepAlive = FakeRecordingKeepAlive(events)
    val recordings = CopyOnWriteArrayList<FinishedRecording>()
    val ingested: List<ByteArray> get() = recordings.map { it.wav }
    private val clockCalls = AtomicInteger(0)

    @Volatile var heldDuringIngest: Boolean? = null

    val recorder = PhoneRecorder(
        microphone = microphone,
        ingest = { recording ->
            heldDuringIngest = keepAlive.held
            events += "ingest"
            recordings += recording
            onIngest(recording)
        },
        keepAlive = keepAlive,
        preload = {
            events += "preload"
            preload()
        },
        processing = { processing },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        clock = { START_MILLIS + 60_000L * clockCalls.getAndIncrement() },
        zone = { TimeZone.of("Asia/Tokyo") },
    )

    suspend fun awaitIdle() = withTimeout(10.seconds) {
        while (recorder.state.value != RecorderState.Idle || "release" !in events) delay(5)
    }

    suspend fun awaitReads(n: Int) = withTimeout(10.seconds) {
        while (microphone.reads.get() < n) delay(2)
    }

    companion object {
        /** What the recorder's clock reads first; each later read is a minute on. */
        const val START_MILLIS = 1_790_000_000_000L
    }
}
