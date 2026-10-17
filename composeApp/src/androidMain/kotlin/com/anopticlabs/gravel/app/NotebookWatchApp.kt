package com.anopticlabs.gravel.app

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.MAX_RECORDING_SAMPLES
import com.anopticlabs.gravel.notebook.capture.MIN_RECORDING_MILLIS
import com.anopticlabs.gravel.notebook.capture.pcm16Wav
import com.anopticlabs.gravel.notebook.pipeline.CaptureIngest
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.pipeline.CaptureText
import com.anopticlabs.gravel.notebook.pipeline.IngestResult
import com.anopticlabs.gravel.notebook.storage.DeviceStateFile
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.ui.NotebookTab
import com.anopticlabs.gravel.ui.WatchAppStatus
import com.anopticlabs.gravel.watchapp.WATCH_SPEEX_FRAME_SAMPLES
import com.anopticlabs.gravel.watchapp.WATCH_SPEEX_SAMPLE_RATE
import com.anopticlabs.gravel.watchapp.WatchAppCaptures
import com.anopticlabs.gravel.watchapp.decodeWatchSpeex
import io.rebble.libpebblecommon.connection.LibPebble3
import io.rebble.libpebblecommon.services.appmessage.AppMessageDictionary
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

// The contract with the gravel-watchapp repository: its UUID and its message keys.
val NOTEBOOK_WATCH_APP_UUID: Uuid = Uuid.parse("d09c758e-a9ac-4ce7-be23-84c6c6caef2b")

/** The key of the watch app's cancel, sent when BACK ends a recording or the wait for its answer. */
const val WATCH_CANCEL_KEY = 1

/** The key of the watch app's ping, whose value Gravel does not read; acknowledged like every message, otherwise ignored. */
const val WATCH_PING_KEY = 2

/** A Notebook watch app release: its version label, its PBW's SHA-256 (lowercase hex) and size, and its release file's address. */
data class WatchAppRelease(val version: String, val sha256: String, val sizeBytes: Long, val url: String)

/** The releases Gravel has shipped, oldest first: Gravel installs the last one. */
val SHIPPED_WATCH_APP_RELEASES: List<WatchAppRelease> = emptyList()

/**
 * [installed]: the locker has an entry for the watch app; [sha256]: the digest of that entry's cached PBW, null when
 * it cannot be read; [marked]: that copy is the one the user marked as trusted ([watchAppTrustFile]).
 */
fun watchAppStatus(
    installed: Boolean,
    sha256: String?,
    marked: Boolean,
    releases: List<WatchAppRelease> = SHIPPED_WATCH_APP_RELEASES,
): WatchAppStatus = when {
    !installed -> WatchAppStatus.NOT_INSTALLED
    sha256 == null -> WatchAppStatus.UNVERIFIED
    sha256 == releases.lastOrNull()?.sha256 -> WatchAppStatus.INSTALLED
    releases.any { it.sha256 == sha256 } -> WatchAppStatus.UPDATE_AVAILABLE
    marked -> WatchAppStatus.USER_TRUSTED
    else -> WatchAppStatus.UNVERIFIED
}

/**
 * How long after a recording ends a cancel from the watch still drops it: above the delays measured on a Time 2 (7 to
 * 24 ms), below a fast SELECT then BACK (84 ms), which keeps the recording.
 */
val WATCH_CANCEL_WAIT: Duration = 50.milliseconds

const val MAX_WATCH_RECORDINGS_QUEUED = 50
const val MAX_WATCH_RECORDINGS_PER_HOUR = 60

/** Encoded audio over this is refused: several times what the watch's 15 s recording cap produces. An empty frame counts as one byte. */
const val MAX_WATCH_RECORDING_BYTES = 256 * 1024

/** How long a stored recording's answer waits on the lookup of what it waits for, before it says "Saved". */
private val WAIT_LOOKUP_LIMIT = 2.seconds

/** What the watch shows, verbatim. */
internal object WatchAnswers {
    const val SAVED = "Saved"
    const val SAVED_WAITING_FOR_WIFI = "Saved. Waiting for Wi-Fi"
    const val SAVED_CHECK_GRAVEL = "Saved. Check Gravel"
    const val NOT_SAVED = "Couldn't save. Try again."
    const val NOT_TRUSTED = "Not saved. Unverified watch app."
    const val NOTEBOOK_OFF = "Notebook is off in Gravel"
    const val FULL = "Notebook is full. Open Gravel."
    const val TOO_MANY = "Too many recordings. Try later."
    const val NOTHING = "Didn't catch that"
}

/** Whether the watch app copy with [sha256] may save recordings: a release Gravel shipped, or the copy the user [marked] as trusted. */
fun trustsWatchApp(sha256: String?, marked: (String) -> Boolean, releases: List<WatchAppRelease> = SHIPPED_WATCH_APP_RELEASES): Boolean =
    sha256 != null && (releases.any { it.sha256 == sha256 } || marked(sha256))

/** Whether the Notebook takes watch recordings: its tab is on, its setup guide finished, and this phone can process recordings. */
suspend fun notebookTakesWatchRecordings(shown: Boolean, guideDone: suspend () -> Boolean, recordingSupported: suspend () -> Boolean): Boolean =
    shown && guideDone() && recordingSupported()

/** What a stored recording waits for: a missing text model holds every recording, whatever [speech] says. */
suspend fun storedRecordingWait(textReady: suspend () -> Boolean, speech: suspend () -> RecordingWait): RecordingWait =
    if (textReady()) speech() else RecordingWait.SETUP

/**
 * Gravel: recordings from the Notebook watch app go into the Notebook as watch recordings, and the watch is told
 * what happened. [installedSha256] is the digest of the copy the phone serves the watch, [notebookOn] whether the
 * Notebook takes watch recordings, [watchRecordingsQueued] how many it holds, [store] queues a WAV dated at its
 * start, [waitFor] what a stored watch recording waits for; [marks] times the hourly limit.
 */
class NotebookWatchApp(
    private val installedSha256: suspend () -> String?,
    private val trusted: suspend (String?) -> Boolean,
    private val notebookOn: suspend () -> Boolean,
    private val watchRecordingsQueued: suspend () -> Int,
    private val decode: suspend (VoiceEncoderInfo.Speex, List<UByteArray>) -> ByteArray,
    private val store: suspend (wav: ByteArray, capturedAtMillis: Long) -> IngestResult,
    private val waitFor: suspend () -> RecordingWait,
    private val clock: () -> Long = System::currentTimeMillis,
    private val marks: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val cancelWait: Duration = WATCH_CANCEL_WAIT,
    lookupContext: CoroutineContext = Dispatchers.IO,
) : WatchAppCaptures {
    override val appUuid = NOTEBOOK_WATCH_APP_UUID

    private class Recording {
        val canceled = CompletableDeferred<Unit>()

        @Volatile
        var endedAt: ComparableTimeMark? = null
    }

    // One slot: Gravel connects one watch (WatchConfig.multipleConnectedWatchesSupported), which runs one recording at
    // a time. A cancel completes the slot's recording, which only its wait reads.
    private val current = AtomicReference<Recording?>(null)

    private val storedInLastHour = ArrayDeque<ComparableTimeMark>()

    // Detached, so a lookup blocked on the disk cannot hold the answer past its limit.
    private val lookups = CoroutineScope(SupervisorJob() + lookupContext)

    override suspend fun capture(encoderInfo: VoiceEncoderInfo, frames: Flow<UByteArray>): String {
        val startedAt = clock()
        val recording = Recording()
        current.set(recording)
        try {
            val digestAtStart = installedDigest()
            val audio = ArrayList<UByteArray>()
            var bytes = 0
            frames.collect { frame ->
                bytes += maxOf(frame.size, 1)
                if (bytes <= MAX_WATCH_RECORDING_BYTES) audio += frame
            }
            recording.endedAt = marks.markNow()
            // The audio is all here, so a dropped connection or a superseding session no longer loses it.
            return withContext(NonCancellable) { afterRecording(encoderInfo, audio, bytes, digestAtStart, startedAt, recording) }
        } finally {
            current.compareAndSet(recording, null)
        }
    }

    private suspend fun afterRecording(
        encoderInfo: VoiceEncoderInfo,
        audio: List<UByteArray>,
        bytes: Int,
        digestAtStart: String?,
        startedAt: Long,
        recording: Recording,
    ): String {
        val stored = try {
            refusal(encoderInfo, audio, bytes, digestAtStart)?.let { return it }
            if (canceledInTime(recording)) return WatchAnswers.NOTHING
            val speex = encoderInfo as VoiceEncoderInfo.Speex
            val wav = withContext(Dispatchers.Default) { pcm16Wav(decode(speex, audio), WATCH_SPEEX_SAMPLE_RATE) }
            store(wav, startedAt)
        } catch (e: Exception) {
            logger.w(e) { "a watch recording was not saved" }
            return WatchAnswers.NOT_SAVED
        }
        if (stored == IngestResult.LOST) return WatchAnswers.NOT_SAVED
        synchronized(storedInLastHour) { storedInLastHour.addLast(marks.markNow()) }
        if (stored == IngestResult.KEPT) return WatchAnswers.SAVED
        // Stored: from here on the answer says so, whatever the lookup does.
        val lookup = lookups.async { waitFor() }
        val wait = try {
            withTimeoutOrNull(WAIT_LOOKUP_LIMIT) { lookup.await() }
        } catch (e: Exception) {
            logger.w(e) { "what a watch recording waits for is unknown" }
            null
        }
        return when (wait) {
            RecordingWait.WIFI -> WatchAnswers.SAVED_WAITING_FOR_WIFI
            RecordingWait.SETUP -> WatchAnswers.SAVED_CHECK_GRAVEL
            RecordingWait.NONE, null -> WatchAnswers.SAVED
        }
    }

    // The wait runs from the end of the recording, so checks that take a while do not lengthen it.
    private suspend fun canceledInTime(recording: Recording): Boolean {
        if (recording.canceled.isCompleted) return true
        val left = cancelWait - checkNotNull(recording.endedAt).elapsedNow()
        return left.isPositive() && withTimeoutOrNull(left) { recording.canceled.await() } != null
    }

    // The digest at the start and at the end must match, so a copy swapped on the phone mid-recording is not trusted.
    private suspend fun refusal(encoderInfo: VoiceEncoderInfo, audio: List<UByteArray>, bytes: Int, digestAtStart: String?): String? {
        if (bytes > MAX_WATCH_RECORDING_BYTES) return WatchAnswers.NOT_SAVED
        val speex = encoderInfo as? VoiceEncoderInfo.Speex ?: return WatchAnswers.NOT_SAVED
        val digestAtEnd = installedDigest()
        if (digestAtStart != digestAtEnd || !trusted(digestAtEnd)) {
            logger.w { "a recording from a Notebook watch app copy Gravel does not trust was refused" }
            return WatchAnswers.NOT_TRUSTED
        }
        if (!notebookOn()) return WatchAnswers.NOTEBOOK_OFF
        val samples = audio.size.toLong() * WATCH_SPEEX_FRAME_SAMPLES
        if (speex.sampleRate != WATCH_SPEEX_SAMPLE_RATE.toLong() || speex.frameSize != WATCH_SPEEX_FRAME_SAMPLES || samples > MAX_RECORDING_SAMPLES) {
            logger.w { "a watch recording was refused: ${audio.size} frames, declared ${speex.sampleRate} Hz and ${speex.frameSize} samples a frame" }
            return WatchAnswers.NOT_SAVED
        }
        if (samples * 1000 / WATCH_SPEEX_SAMPLE_RATE < MIN_RECORDING_MILLIS) return WatchAnswers.NOTHING
        if (watchRecordingsQueued() >= MAX_WATCH_RECORDINGS_QUEUED) return WatchAnswers.FULL
        if (storedInPastHour() >= MAX_WATCH_RECORDINGS_PER_HOUR) return WatchAnswers.TOO_MANY
        return null
    }

    private suspend fun installedDigest(): String? = try {
        installedSha256()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "the Notebook watch app's digest could not be read" }
        null
    }

    override suspend fun onMessage(data: AppMessageDictionary) {
        if (WATCH_CANCEL_KEY !in data) return
        val recording = current.get()
        if (recording == null) {
            logger.i { "watch cancel with no recording in progress; ignored" }
            return
        }
        val sinceEnd = recording.endedAt?.elapsedNow()
        val late = sinceEnd != null && sinceEnd > cancelWait
        // The delay is logged so a device run can measure it for WATCH_CANCEL_WAIT.
        logger.i { "watch cancel ${sinceEnd?.let { "$it after the recording ended" } ?: "before the recording ended"}${if (late) "; too late, ignored" else ""}" }
        if (!late) recording.canceled.complete(Unit)
    }

    private fun storedInPastHour(): Int = synchronized(storedInLastHour) {
        while (storedInLastHour.isNotEmpty() && storedInLastHour.first().elapsedNow() >= 1.hours) storedInLastHour.removeFirst()
        storedInLastHour.size
    }

    private companion object {
        val logger = Logger.withTag("NotebookWatchApp")
    }
}

/** The digest of the watch app copy the user marked as trusted, kept in [dir], the no-backup directory. A SHA-256 in hex is 64 characters. */
internal fun watchAppTrustFile(dir: java.io.File) = DeviceStateFile(dir, "notebook_watch_app_trust", maxChars = 64)

val notebookWatchAppModule = module {
    single<WatchAppCaptures> {
        // Resolved per recording: LibPebble3 is created with this handler, and the Notebook starts after it.
        val context = androidContext()
        NotebookWatchApp(
            installedSha256 = { get<LibPebble3>().cachedPbwSha256(NOTEBOOK_WATCH_APP_UUID) },
            trusted = { sha256 -> withContext(Dispatchers.IO) { trustsWatchApp(sha256, { watchAppTrustFile(context.noBackupFilesDir).read() == it }) } },
            notebookOn = {
                val tab = get<NotebookTab>()
                notebookTakesWatchRecordings(tab.shown.value, { tab.setupGuide.first { it.read }.done }, tab::recordingSupported)
            },
            watchRecordingsQueued = {
                val queue = get<QueueStore>()
                withContext(Dispatchers.IO) { queue.readQueue().count { it.fromWatch } } + queue.retainedWatchCount()
            },
            decode = { speex, frames -> decodeWatchSpeex(speex, frames) },
            store = { wav, at -> get<CaptureIngest>().ingest(wav, "wav", capturedAtMillis = at, fromWatch = true) },
            waitFor = {
                storedRecordingWait(
                    textReady = {
                        try {
                            get<CaptureText>().checkReady()
                            true
                        } catch (e: CaptureProcessingException) {
                            false
                        }
                    },
                    speech = { withContext(Dispatchers.IO) { get<NotebookSpeech>().watchRecordingWait() } },
                )
            },
        )
    }
}
