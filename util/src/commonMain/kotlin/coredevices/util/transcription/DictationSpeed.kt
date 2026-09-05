package coredevices.util.transcription

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.util.models.WhisperModelCatalog
import io.rebble.libpebblecommon.voice.DICTATION_DEADLINE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlin.time.DurationUnit

/**
 * A prompt to switch models because real dictations on [currentModelId]
 * decode too slowly for the watch's window; [targetModelId] is the next
 * cheaper catalog tier and [factor] the measured seconds of decode per
 * second of speech.
 */
data class SpeedNudge(val currentModelId: String, val targetModelId: String, val factor: Double)

/**
 * The decision behind the nudge, pure so it stays under host tests. The
 * measured decode factor (seconds of engine time per second of engine
 * input, smoothed across dictations) predicts what a full window of
 * speech would cost; when that prediction misses the deadline the session
 * coordinator enforces, and a cheaper tier exists, the user is offered it
 * once per model. The speed probe's estimate at selection is the
 * forecast; this is the record of what actually happened, so it also
 * catches phones where the probe was optimistic.
 */
object DictationSpeedPolicy {
    /** Seconds the phone has after the recording ends before the session coordinator reports failure to the watch. */
    val DEADLINE_SECONDS = DICTATION_DEADLINE.toDouble(DurationUnit.SECONDS)

    /** Weight of the newest dictation in the smoothed factor. */
    const val NEWEST_WEIGHT = 0.3

    /** Dictations with less speech than this say nothing reliable about speed. */
    const val MIN_SPEECH_SECONDS = 2.0

    /**
     * Audio-equivalent seconds of encoder work every call carries beyond
     * its speech: the shim sizes the encoder context at the audio's own
     * positions plus 64 (`audio_ctx` in `whisper_jni.cpp`), and a position
     * covers 20 ms of audio (whisper.cpp's 10 ms mel hop, then the
     * encoder's stride-two convolution, `whisper_build_graph_encoder`).
     * Rates are measured and extrapolated against speech plus this floor,
     * so a short reply's fixed cost does not read as a slow model.
     */
    const val ENCODER_FLOOR_SECONDS = 1.28

    /** The engine input a dictation of [speechSeconds] costs, floor included. */
    fun effectiveSeconds(speechSeconds: Double): Double = speechSeconds + ENCODER_FLOOR_SECONDS

    fun smoothedFactor(previous: Double?, sample: Double): Double =
        previous?.let { it + NEWEST_WEIGHT * (sample - it) } ?: sample

    fun predictedWindowSeconds(factor: Double): Double = factor * effectiveSeconds(WhisperSpeedCalibration.WINDOW_SECONDS)

    /**
     * The nudge for [modelId] at [factor], or null when a full window
     * still fits, the model is already the tiny floor, the model is not
     * a catalog entry, or the user declined for this model.
     */
    fun nudgeFor(modelId: String, factor: Double?, declined: Boolean): SpeedNudge? {
        if (factor == null || declined) return null
        if (predictedWindowSeconds(factor) <= DEADLINE_SECONDS) return null
        val model = WhisperModelCatalog.byId(modelId) ?: return null
        val target = WhisperModelCatalog.stepDown(model) ?: return null
        return SpeedNudge(currentModelId = modelId, targetModelId = target.id, factor = factor)
    }
}

/** The text the nudge dialog shows, in one place so a host test pins the facts it must state. */
data class SpeedNudgeCopy(val title: String, val body: String, val switchLabel: String, val keepLabel: String)

/**
 * States the consequence plainly (what the watch shows when the window is
 * missed) and where the choice can be changed later, so a user who keeps
 * the current model knows what they are accepting and how to come back.
 */
fun speedNudgeCopy(nudge: SpeedNudge): SpeedNudgeCopy {
    val current = WhisperModelCatalog.byId(nudge.currentModelId)
    val target = WhisperModelCatalog.byId(nudge.targetModelId)
    val currentName = current?.displayName ?: nudge.currentModelId
    val targetName = target?.displayName ?: nudge.targetModelId
    val predicted = DictationSpeedPolicy.predictedWindowSeconds(nudge.factor).roundToInt()
    return SpeedNudgeCopy(
        title = "Dictation is too slow for the watch",
        body = "$currentName needs about $predicted seconds for a full 15 second dictation on this " +
            "phone. The watch gives up after 15 seconds and shows \"Error occurred. Try again.\" " +
            "$targetName is faster, with somewhat lower accuracy. Either way, the model can be " +
            "changed later under Settings > Speech Recognition > Manage Offline Models.",
        switchLabel = "Switch to $targetName",
        keepLabel = "Keep $currentName",
    )
}

/**
 * Keeps the smoothed decode factor per model across launches and raises
 * the nudge when it predicts a missed window. Fed by the transcription
 * service after every successful dictation; read by the nudge dialog.
 * Declining is remembered per model, so one model nags at most once, and
 * a later, slower tier gets its own nudge from its own factor. While the
 * dialog is switching to the offered model the pending nudge is held as
 * it is, so a dictation finishing mid-download neither replaces nor
 * withdraws the offer the download belongs to.
 */
class DictationSpeedTracker(private val settings: Settings) {
    private companion object {
        val logger = Logger.withTag("DictationSpeedTracker")
        const val FACTOR_PREFIX = "stt_decode_factor_"
        const val DECLINED_PREFIX = "stt_speed_nudge_declined_"
    }

    private val _nudge = MutableStateFlow<SpeedNudge?>(null)

    /** The nudge to show, null when there is none pending. */
    val nudge: StateFlow<SpeedNudge?> = _nudge.asStateFlow()

    @Volatile
    private var switching = false

    fun factorFor(modelId: String): Double? = settings.getDoubleOrNull(FACTOR_PREFIX + modelId)

    fun isDeclined(modelId: String): Boolean = settings.getBoolean(DECLINED_PREFIX + modelId, false)

    /**
     * Records that [speechSeconds] of engine input took [decodeMillis] on
     * [modelId] and re-evaluates the nudge, unless a switch is in
     * progress, in which case only the factor is updated. Inputs shorter
     * than [DictationSpeedPolicy.MIN_SPEECH_SECONDS] are ignored.
     */
    fun recordDecode(modelId: String, speechSeconds: Double, decodeMillis: Long) {
        if (speechSeconds < DictationSpeedPolicy.MIN_SPEECH_SECONDS || decodeMillis <= 0L) return
        val sample = decodeMillis / 1000.0 / DictationSpeedPolicy.effectiveSeconds(speechSeconds)
        val factor = DictationSpeedPolicy.smoothedFactor(factorFor(modelId), sample)
        settings.putDouble(FACTOR_PREFIX + modelId, factor)
        if (switching) return
        val nudge = DictationSpeedPolicy.nudgeFor(modelId, factor, isDeclined(modelId))
        if (nudge != null) {
            logger.i { "Decode factor ${factor.roundTo(2)} on $modelId predicts a missed window; offering ${nudge.targetModelId}" }
        }
        _nudge.value = nudge
    }

    /** The dialog is acting on the pending nudge; it stays as it is until [endSwitch], [clear] or [decline]. */
    fun beginSwitch() {
        switching = true
    }

    /** The switch ended without selecting the target (failed or cancelled); later dictations re-evaluate the nudge. */
    fun endSwitch() {
        switching = false
    }

    /** The user keeps [modelId]: never nudge for it again. */
    fun decline(modelId: String) {
        settings.putBoolean(DECLINED_PREFIX + modelId, true)
        switching = false
        _nudge.value = null
    }

    /** Clears the pending nudge without recording a decision (dismissed, or the switch was made). */
    fun clear() {
        switching = false
        _nudge.value = null
    }

    private fun Double.roundTo(decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        return kotlin.math.round(this * factor) / factor
    }
}
