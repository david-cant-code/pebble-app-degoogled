package coredevices.util.transcription

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.models.WhisperTier
import coredevices.whisper.isWhisperSupported
import coredevices.whisper.whisperBenchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * One result of the engine's model-free speed probe (`whisperBenchmark`
 * in :whisper): the median time of one synthetic encoder block and the
 * thread count it ran on, which is the count a dictation on this phone
 * gets at the same moment.
 */
data class SpeedScore(val nsPerBlock: Long, val threads: Int, val measuredAtEpochMs: Long)

/** How a model's estimated full-window decode compares with the watch's budget. */
enum class WindowFit { Fits, Marginal, Exceeds }

/**
 * Turns a [SpeedScore] into seconds for a full dictation window per
 * catalog model. The probe times one base-width encoder block; each
 * tier's decode is a fixed multiple of that block on a given CPU, so the
 * time the reference phone took to decode a full [WINDOW_SECONDS] window
 * of speech on each tier, divided by the reference phone's own score,
 * scales to another phone through its score. The estimate then carries
 * [BACKGROUND_MARGIN] for the app not being on screen: an off-screen
 * process lands in a smaller cpuset, and the on-screen to off-screen
 * slowdown measured 1.4x on one test phone and 2x to 4x on the other
 * depending on whether the app held a foreground service, which it does
 * while a watch is connected.
 *
 * Calibration procedure: run the instrumented speed calibration benchmark
 * on the reference phone with every tier installed and the app on screen;
 * it prints the probe score and the median full-window decode per tier.
 * Copy both here. Re-run whenever the probe graph ([PROBE_VERSION]), the
 * engine revision, or the thread policy changes, since each moves the
 * factors.
 */
object WhisperSpeedCalibration {
    /** The watch firmware's recording cap: the longest dictation the phone must decode. */
    const val WINDOW_SECONDS = 15.0

    /** Bump when the native probe graph changes; cached scores from an older probe are discarded. */
    const val PROBE_VERSION = 1

    /** Reference phone probe score (ns per block), app on screen, two engine threads (measured 2026-09-02). */
    const val REFERENCE_SCORE_NS = 92_931_000L

    /** Reference phone full-window decode per tier (seconds), app on screen, detector off. */
    private val referenceWindowSeconds: Map<WhisperTier, Double> = mapOf(
        WhisperTier.Tiny to 0.77,
        WhisperTier.Base to 1.41,
        WhisperTier.Small to 4.68,
    )

    /** The reference phone's full-window seconds for [tier], null when uncalibrated. */
    fun referenceWindowSeconds(tier: WhisperTier): Double? = referenceWindowSeconds[tier]?.takeIf { it > 0.0 }

    /** Factor applied to every estimate for the app not being on screen. */
    const val BACKGROUND_MARGIN = 2.0

    /** At or under this the window fits with room for the session's other steps. */
    const val FITS_LIMIT_SECONDS = 10.0

    /** At or under this it can still fit; the watch gives up at 15. */
    const val MARGINAL_LIMIT_SECONDS = 15.0

    /**
     * Estimated seconds to decode a full window on [modelId], or null when
     * there is no score, no calibration, or [modelId] is not a speech model.
     */
    fun estimateWindowSeconds(modelId: String, score: SpeedScore?): Double? {
        if (score == null || REFERENCE_SCORE_NS <= 0L) return null
        val tier = WhisperModelCatalog.byId(modelId)?.tier ?: return null
        val reference = referenceWindowSeconds(tier) ?: return null
        return reference * (score.nsPerBlock.toDouble() / REFERENCE_SCORE_NS) * BACKGROUND_MARGIN
    }

    fun classify(windowSeconds: Double): WindowFit = when {
        windowSeconds <= FITS_LIMIT_SECONDS -> WindowFit.Fits
        windowSeconds <= MARGINAL_LIMIT_SECONDS -> WindowFit.Marginal
        else -> WindowFit.Exceeds
    }

    /** [classify] over [estimateWindowSeconds]; null when there is no estimate. */
    fun fitOf(modelId: String, score: SpeedScore?): WindowFit? =
        estimateWindowSeconds(modelId, score)?.let(::classify)
}

/**
 * The model picker's row detail: the download size, and when a speed
 * score exists, what a full dictation window would cost on this phone in
 * the words the user will judge it by. Pure, so the copy is pinned by a
 * host test.
 */
fun modelRowText(sizeInMB: Int, estimatedWindowSeconds: Double?): String {
    if (estimatedWindowSeconds == null) return "$sizeInMB MB"
    val rounded = estimatedWindowSeconds.roundToInt()
    val about = if (rounded < 1) "under 1 s" else "about $rounded s"
    return when (WhisperSpeedCalibration.classify(estimatedWindowSeconds)) {
        WindowFit.Fits -> "$sizeInMB MB, $about for a 15 s recording"
        WindowFit.Marginal -> "$sizeInMB MB, $about for a 15 s recording, close to the watch's limit"
        WindowFit.Exceeds -> "$sizeInMB MB, too slow for the watch on this phone ($about for a 15 s recording)"
    }
}

/**
 * Runs the speed probe and remembers its score across launches. One
 * measurement per install (and per [WhisperSpeedCalibration.PROBE_VERSION]),
 * repeated only on demand from the model screen, since a second of
 * engine-grade CPU is not free on the phones the estimate matters for.
 * The probe shares no lock with the transcription service; a dictation
 * running at the same time makes both slower, which only ever pushes an
 * estimate toward "too slow".
 *
 * [threadCount] must be the count a dictation gets at the moment of the
 * call ([dictationThreadCount]), so the score reflects the same threading
 * as the decode; [probe] and [supported] are the engine binding, injected
 * so the caching stays under host tests.
 */
class DeviceSpeedEstimator(
    private val settings: Settings,
    private val threadCount: () -> Int,
    private val probe: (threads: Int) -> Long = { threads -> whisperBenchmark(threads) },
    private val supported: () -> Boolean = { isWhisperSupported() },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private companion object {
        val logger = Logger.withTag("DeviceSpeedEstimator")
        const val KEY_NS = "stt_speed_ns_per_block"
        const val KEY_THREADS = "stt_speed_threads"
        const val KEY_AT = "stt_speed_measured_at"
        const val KEY_VERSION = "stt_speed_probe_version"
    }

    private val _score = MutableStateFlow(load())

    /** The cached score; null until the probe has run on this install. */
    val score: StateFlow<SpeedScore?> = _score.asStateFlow()

    fun cached(): SpeedScore? = _score.value

    private fun load(): SpeedScore? {
        if (settings.getIntOrNull(KEY_VERSION) != WhisperSpeedCalibration.PROBE_VERSION) return null
        val ns = settings.getLongOrNull(KEY_NS) ?: return null
        val threads = settings.getIntOrNull(KEY_THREADS) ?: return null
        val at = settings.getLongOrNull(KEY_AT) ?: return null
        return SpeedScore(ns, threads, at)
    }

    /**
     * Runs the probe now and caches the result. Returns the previous
     * score unchanged when the engine is unsupported or the probe fails,
     * so a transient failure never erases a good measurement.
     */
    suspend fun measure(): SpeedScore? {
        if (!supported()) return _score.value
        val threads = threadCount()
        // The probe and the settings writes both belong off the main
        // thread: the writes hit disk, which the debug build's strict mode
        // rejects on the UI thread.
        val measured = withContext(Dispatchers.IO) {
            val ns = runCatching { probe(threads) }
                .onFailure { logger.w(it) { "Speed probe failed" } }
                .getOrNull()
            if (ns == null || ns <= 0L) return@withContext null
            val score = SpeedScore(nsPerBlock = ns, threads = threads, measuredAtEpochMs = now())
            settings.putLong(KEY_NS, score.nsPerBlock)
            settings.putInt(KEY_THREADS, score.threads)
            settings.putLong(KEY_AT, score.measuredAtEpochMs)
            settings.putInt(KEY_VERSION, WhisperSpeedCalibration.PROBE_VERSION)
            score
        } ?: return _score.value
        _score.value = measured
        logger.i { "Speed probe: ${measured.nsPerBlock / 1_000_000} ms per block on ${measured.threads} threads" }
        return measured
    }

    /** The cached score, measuring once when there is none. */
    suspend fun cachedOrMeasure(): SpeedScore? = cached() ?: measure()
}
