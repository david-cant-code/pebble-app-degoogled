package com.anopticlabs.gravel.models

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.llm.ChatMessage
import com.anopticlabs.gravel.llm.LlmEngineClient
import com.anopticlabs.gravel.llm.LlmGenerateStats
import com.anopticlabs.gravel.llm.StopReason
import com.anopticlabs.gravel.notebook.llm.Prompts
import coredevices.util.transcription.CactusModelPathProvider
import coredevices.util.transcription.SpeedScore
import coredevices.util.transcription.WhisperSpeedCalibration
import coredevices.util.transcription.transcriptionThreadCount
import coredevices.whisper.isWhisperSupported
import coredevices.whisper.whisperBenchmark
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.koin.mp.KoinPlatform
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Calibration run for [LlmSpeedCalibration]. With the app's activity on
 * screen (the state the speech reference is defined in) it times the
 * speech engine's probe, then for each installed text model the note
 * prompt over [TRANSCRIPT] at [textGenerationThreadCount], and prints
 * each model's median scaled to [LlmSpeedCalibration.SPEECH_REFERENCE_SCORE_NS]
 * (the value the calibration records) beside what the current constants
 * predict. Never downloads: a model that is not installed is skipped
 * ([LlmCatalogModelsDeviceTest] stages one). Run on its own:
 *   adb shell am instrument -w \
 *     -e class com.anopticlabs.gravel.models.LlmSpeedCalibrationBenchmark \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class LlmSpeedCalibrationBenchmark {

    private companion object {
        const val TAG = "LlmSpeedCalib"
        const val RUNS = 3
        const val CONTEXT = 4096
        const val MAX_TOKENS = 256

        // About 150 words: a minute of speech at a conversational pace.
        const val TRANSCRIPT = "Okay, a few things for this weekend. First I need to pick up the dry cleaning " +
            "before the shop closes on Saturday, they said noon. Then groceries: we are out of coffee, oat milk, " +
            "eggs, spinach and those rice crackers the kids like. Also remember to call the plumber about the " +
            "kitchen tap, it has been dripping all week and the landlord said we could book someone ourselves and " +
            "send him the invoice. On Sunday the neighbors are coming over for lunch, so I should make the lemon " +
            "chicken and maybe a salad, and check that we still have enough chairs. I want to finish the book club " +
            "novel before Thursday, I am only halfway through it. And the car needs an oil change soon, the light " +
            "came on yesterday, so book that for next week if the garage has a slot on Tuesday or Wednesday morning."
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("[$TAG] $line")
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    @Test
    fun eachTextModelIsCalibrated() {
        Assume.assumeTrue("speech engine unsupported on this CPU", isWhisperSupported())
        Assume.assumeTrue("text engine unsupported on this device", isTextEngineSupported())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.startActivitySync(intent)
            Thread.sleep(1500)
        }

        val probeThreads = transcriptionThreadCount()
        val warmUp = whisperBenchmark(probeThreads)
        val scores = (1..RUNS).map { whisperBenchmark(probeThreads) }
        val score = median(scores)
        log("probe threads=$probeThreads first=$warmUp (discarded) nsPerBlock=$scores median=$score")

        val provider = KoinPlatform.getKoin().get<CactusModelPathProvider>()
        val messages = listOf(
            ChatMessage("system", Prompts.NOTE_SYSTEM.trim()),
            ChatMessage("user", Prompts.notePromptVerbatim(TRANSCRIPT).trim()),
        )
        log("transcript words=${TRANSCRIPT.split(' ').size}")
        var measured = 0
        for (model in LlmModelCatalog.MODELS) {
            val installed = File(context.filesDir, "models/${model.id}/${model.fileName}")
            if (installed.length() != model.sizeBytes) {
                log("${model.id} not installed; skipping")
                continue
            }
            val path = runBlocking { provider.getModelPath(model.id, allowReinstall = false) }
            val handle = LlmEngineClient.init(File(path), CONTEXT)
            try {
                val prompt = LlmEngineClient.formatChat(handle, messages) + model.promptSuffix
                val threads = textGenerationThreadCount()
                val runtime = LlmEngineClient.runtime(bindIfNeeded = false)
                log("${model.id} engine cpuset=${runtime?.cpuset} allowed=${runtime?.cpusAllowedList} threads=$threads")
                generate(handle, prompt, threads, "warm-up")
                val times = (1..RUNS).map { generate(handle, prompt, threads, model.id) }
                val medianMs = median(times)
                val scaled = medianMs / 1000.0 * LlmSpeedCalibration.SPEECH_REFERENCE_SCORE_NS / score
                val predicted = LlmSpeedCalibration.estimateNoteSeconds(model.id, SpeedScore(score, probeThreads, 0L))
                log(
                    "model=${model.id} noteMs=$times median=$medianMs referenceSeconds=${"%.2f".format(scaled)} " +
                        "predictedSeconds=${predicted?.let { "%.2f".format(it) } ?: "uncalibrated"} " +
                        "(prediction includes the ${WhisperSpeedCalibration.BACKGROUND_MARGIN}x background margin)",
                )
                measured++
            } finally {
                LlmEngineClient.free(handle)
            }
        }
        Assume.assumeTrue("no text model installed", measured > 0)
    }

    /** One note generation; returns its wall time in milliseconds. */
    private fun generate(handle: Long, prompt: String, threads: Int, label: String): Long {
        val stats = LlmGenerateStats()
        val start = SystemClock.elapsedRealtime()
        val answer = LlmEngineClient.generate(
            handle, prompt, MAX_TOKENS, threads, callId = SystemClock.elapsedRealtimeNanos(), stats = stats,
        )
        val elapsed = SystemClock.elapsedRealtime() - start
        log(
            "$label ${elapsed} ms: prompt=${stats.promptTokens} tokens/${stats.prefillMillis} ms " +
                "generated=${stats.generatedTokens} tokens/${stats.decodeMillis} ms answer=${answer.replace("\n", "\\n")}",
        )
        assertEquals(StopReason.END_OF_GENERATION, stats.stopReason, "the note did not end within $MAX_TOKENS tokens")
        assertTrue(answer.contains("\"title\""), "the answer is not the note JSON")
        return elapsed
    }
}
