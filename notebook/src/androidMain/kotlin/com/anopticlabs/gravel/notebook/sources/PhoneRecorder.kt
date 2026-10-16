package com.anopticlabs.gravel.notebook.sources

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.AppVisibility
import com.anopticlabs.gravel.notebook.capture.RecordingBuffer
import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.notebook.pipeline.CaptureIngest
import com.anopticlabs.gravel.notebook.pipeline.CaptureText
import com.anopticlabs.gravel.notebook.pipeline.IngestResult
import com.anopticlabs.gravel.notebook.pipeline.RecordingKeepAlive
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** A running microphone capture of mono PCM16 at the capture sample rate. */
interface MicCapture : AutoCloseable {
    /** Blocks until samples arrive; returns how many were read, or a negative value once the microphone is lost. */
    fun read(into: ShortArray): Int
}

fun interface Microphone {
    /** Throws when the microphone cannot be started, as without the permission or while it is taken. */
    fun start(): MicCapture
}

sealed interface RecorderState {
    data object Idle : RecorderState

    data class Recording(val seconds: Long) : RecorderState

    data object Saving : RecorderState
}

enum class RecorderNotice { TOO_SHORT, INTERRUPTED, LIMIT_REACHED, MIC_LOST, NOT_STARTED, NOT_SAVED }

/** A recording ready to be queued, with the time and zone its microphone started in. */
class FinishedRecording(val wav: ByteArray, val startedAtMillis: Long, val zone: TimeZone)

/**
 * Records the phone microphone into memory, one recording at a time, and hands a finished one to
 * the capture ingest. A recording ends on [stop], at the length limit, when the microphone is
 * lost, when the last resumed activity pauses or when no activity is left started; what was
 * recorded until then is handed to the ingest unless it is under a second. It is stamped with
 * the time and zone the microphone started in. The text model is loaded while the user speaks,
 * unless a capture run is under way.
 *
 * The recording keep-alive is held from [record] until the ingest returns: a task swiped out of
 * Recents takes its processes with it unless one runs a foreground service (android16-release,
 * `ActivityTaskSupervisor.killTaskProcessesIfPossible`), and a start from the background needs
 * an exemption (see `InferenceBoostService`), so it starts at the tap.
 */
class PhoneRecorder internal constructor(
    private val microphone: Microphone,
    private val ingest: suspend (FinishedRecording) -> IngestResult,
    private val keepAlive: RecordingKeepAlive,
    private val preload: suspend () -> Unit,
    private val processing: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
) {
    constructor(
        microphone: Microphone,
        ingest: CaptureIngest,
        keepAlive: RecordingKeepAlive,
        text: CaptureText,
        coordinator: CaptureCoordinator,
    ) : this(
        microphone = microphone,
        ingest = { ingest.ingest(it.wav, "wav", it.startedAtMillis, it.zone) },
        keepAlive = keepAlive,
        preload = {
            text.checkReady()
            text.prepare()
        },
        processing = { coordinator.working.value },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logger.w(e) { "recording failed" } }),
    )

    private val lock = Any()
    private val started = AtomicBoolean(false)

    @Volatile
    private var visibility: AppVisibility? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var stoppedByLeaving = false

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<RecorderNotice?>(null)

    /** What the last recording's end has to tell the user; cleared by the next [record]. */
    val notice: StateFlow<RecorderNotice?> = _notice.asStateFlow()

    fun start(visibility: AppVisibility) {
        if (!started.compareAndSet(false, true)) return
        this.visibility = visibility
        visibility.addResumedListener { resumed -> if (!resumed) stop(leaving = true) }
        // A recording accepted while no activity was resumed may see no pause before the app is left.
        visibility.addBackgroundedListener { stop(leaving = true) }
    }

    /** Starts a recording; false while one is under way or being saved, or with no activity started. */
    fun record(): Boolean {
        if (visibility?.isBackgrounded == true) return false
        synchronized(lock) {
            if (_state.value != RecorderState.Idle) return false
            stopRequested = false
            stoppedByLeaving = false
            _notice.value = null
            _state.value = RecorderState.Recording(0)
        }
        guarded("hold", keepAlive::holdRecording)
        scope.launch { run() }
        return true
    }

    /** Ends the recording under way, keeping what it has; does nothing otherwise. */
    fun stop() = stop(leaving = false)

    private fun stop(leaving: Boolean) {
        if (_state.value !is RecorderState.Recording || stopRequested) return
        stoppedByLeaving = leaving
        stopRequested = true
    }

    private suspend fun run() {
        try {
            val capture = try {
                microphone.start()
            } catch (e: Exception) {
                logger.w { "microphone not started: ${e::class.simpleName}" }
                _notice.value = RecorderNotice.NOT_STARTED
                return
            }
            val startedAt = clock()
            val startZone = zone()
            // The drain frees the text model when memory is short; a load during its run would undo that.
            if (!processing()) scope.launch { preloadText() }
            val buffer = RecordingBuffer()
            val lost = capture.use { readUntilDone(it, buffer) }
            _state.value = RecorderState.Saving
            _notice.value = when {
                buffer.isTooShort -> if (stoppedByLeaving) RecorderNotice.INTERRUPTED else RecorderNotice.TOO_SHORT
                save { FinishedRecording(buffer.toWav(), startedAt, startZone) } == IngestResult.LOST -> RecorderNotice.NOT_SAVED
                buffer.isFull -> RecorderNotice.LIMIT_REACHED
                lost -> RecorderNotice.MIC_LOST
                else -> null
            }
        } finally {
            guarded("release", keepAlive::releaseRecording)
            _state.value = RecorderState.Idle
        }
    }

    private suspend fun save(recording: () -> FinishedRecording): IngestResult = try {
        ingest(recording())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.w { "recording not saved: ${e::class.simpleName}" }
        IngestResult.LOST
    }

    private fun guarded(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            logger.w(e) { "keep-alive $what failed" }
        }
    }

    /** Reads until a stop, the limit or a lost microphone; true for the last. */
    private fun readUntilDone(capture: MicCapture, buffer: RecordingBuffer): Boolean {
        val chunk = ShortArray(CHUNK_SAMPLES)
        while (!stopRequested && !buffer.isFull) {
            val read = capture.read(chunk)
            if (read < 0) {
                logger.w { "microphone lost: $read" }
                return true
            }
            buffer.append(chunk, read)
            val seconds = buffer.millis / 1000
            if ((_state.value as? RecorderState.Recording)?.seconds != seconds) _state.value = RecorderState.Recording(seconds)
        }
        return false
    }

    private suspend fun preloadText() {
        try {
            preload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.i { "text model not preloaded: ${e::class.simpleName}" }
        }
    }

    private companion object {
        /** 100 ms at the capture rate, so a stop is seen within about that. */
        const val CHUNK_SAMPLES = 1_600
        val logger = Logger.withTag("PhoneRecorder")
    }
}
