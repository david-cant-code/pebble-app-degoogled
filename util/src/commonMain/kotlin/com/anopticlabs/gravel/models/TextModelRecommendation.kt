package com.anopticlabs.gravel.models

import coredevices.util.transcription.SpeedScore
import coredevices.util.transcription.WhisperSpeedCalibration

/**
 * Seconds per text model for the note of a one-minute recording, scaled from the speech
 * probe's score as [WhisperSpeedCalibration] scales a dictation window. Re-run `LlmSpeedCalibrationBenchmark` when the text engine, the note prompt,
 * the thread rule or the speech calibration changes.
 */
object LlmSpeedCalibration {
    const val NOTE_BUDGET_SECONDS = 45.0

    /** The speech reference score the times below are scaled to. */
    const val SPEECH_REFERENCE_SCORE_NS = 92_931_000L

    // Reference phone, app on screen, two engine threads, measured 2026-09-22; 0 = not measured.
    private val referenceNoteSeconds: Map<String, Double> = mapOf(
        "qwen3-1_7b-q4_k_m" to 8.96,
        "qwen3_5-0_8b-q5_k_m" to 6.04,
    )

    fun referenceNoteSeconds(modelId: String): Double? = referenceNoteSeconds[modelId]?.takeIf { it > 0.0 }

    fun estimateNoteSeconds(modelId: String, score: SpeedScore?): Double? {
        if (score == null) return null
        val reference = referenceNoteSeconds(modelId) ?: return null
        return reference * (score.nsPerBlock.toDouble() / SPEECH_REFERENCE_SCORE_NS) * WhisperSpeedCalibration.BACKGROUND_MARGIN
    }
}

/** The first of [models] within its RAM floor and the note budget, else the last; null where the engine cannot run. */
fun recommendedTextModel(
    engineSupported: Boolean,
    totalRamBytes: Long,
    score: SpeedScore?,
    models: List<LlmModel> = LlmModelCatalog.MODELS,
): LlmModel? {
    if (!engineSupported) return null
    return models.firstOrNull { fitsDevice(it, totalRamBytes, score) } ?: models.last()
}

private fun fitsDevice(model: LlmModel, totalRamBytes: Long, score: SpeedScore?): Boolean =
    !exceedsDeviceRam(model.minRamBytes, totalRamBytes) &&
        (LlmSpeedCalibration.estimateNoteSeconds(model.id, score) ?: 0.0) <= LlmSpeedCalibration.NOTE_BUDGET_SECONDS

/** More than 10% over the device total, so a phone that nominally meets a floor passes; an unknown total never fails. */
fun exceedsDeviceRam(minRamBytes: Long, totalRamBytes: Long): Boolean =
    totalRamBytes > 0L && minRamBytes * 10L > totalRamBytes * 11L

/** The Notebook's text model: the chosen one if installed, else the recommended one if installed, else the largest installed. */
fun pickTextModel(installed: List<LlmModel>, selectedId: String?, recommended: LlmModel?): LlmModel? =
    installed.firstOrNull { it.id == selectedId }
        ?: installed.firstOrNull { it.id == recommended?.id }
        ?: installed.maxByOrNull { it.sizeBytes }
