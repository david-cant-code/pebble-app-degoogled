package coredevices.coreapp.transcription

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.coreapp.model.WhisperModelProvider
import coredevices.coreapp.testsupport.ReadOnlyModelPathProvider
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.models.WhisperTier
import coredevices.util.transcription.SpeedScore
import coredevices.util.transcription.WhisperSpeedCalibration
import coredevices.util.transcription.transcriptionThreadCount
import coredevices.whisper.EnginePlacement
import coredevices.whisper.isWhisperSupported
import coredevices.whisper.pcm16ToFloats
import coredevices.whisper.whisperBenchmark
import coredevices.whisper.whisperFree
import coredevices.whisper.whisperInit
import coredevices.whisper.whisperTranscribe
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Calibration run for [WhisperSpeedCalibration] plus the one assertion the
 * probe must hold: on the same phone, repeated probe scores stay within a
 * quarter of each other. With the app's activity on screen (the state the
 * reference numbers are defined in) it prints the probe score and, for
 * every installed tier, the median decode of a full 15 second window of
 * speech with the detector off, which are the constants the calibration
 * object records. It also prints what the current constants predict, so
 * a second phone cross-checks them. Pass `-e download true` to install a
 * missing tier through the production provider first. The spread
 * assertion is advisory: a warm or busy phone can exceed it. Run on its
 * own:
 *   adb shell am instrument -w \
 *     -e class coredevices.coreapp.transcription.WhisperSpeedCalibrationBenchmark \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class WhisperSpeedCalibrationBenchmark {

    private companion object {
        const val TAG = "SpeedCalib"
        const val RUNS = 3
        const val WINDOW_SAMPLES = 15 * 16_000
        val CLIPS = listOf("eval_shopping_list_shrimp.raw", "eval_text_eric_shrimp.raw")
        val TIER_MODELS = mapOf(
            WhisperTier.Tiny to "whisper-tiny-en",
            WhisperTier.Base to "whisper-base-en",
            WhisperTier.Small to "whisper-small-en",
        )
    }

    private fun log(line: String) {
        android.util.Log.i(TAG, line)
        println("[$TAG] $line")
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun cpuset(): String = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")

    @Test
    fun probeIsStableAndEachTierIsCalibrated() {
        Assume.assumeTrue("engine unsupported on this CPU", isWhisperSupported())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.startActivitySync(intent)
            Thread.sleep(1500)
        }
        val threads = transcriptionThreadCount()
        log("cpuset=${cpuset()} threads=$threads")

        val scores = (1..RUNS).map { whisperBenchmark(threads) }
        val score = median(scores)
        val spread = (scores.max() - scores.min()).toDouble() / scores.min()
        log("probe nsPerBlock=$scores median=$score spread=${"%.2f".format(spread)}")
        assertTrue(spread <= 0.25, "probe scores spread ${"%.2f".format(spread)} exceeds 0.25")

        // A full window: the two speech clips joined and repeated to exactly
        // 15 s, so the encoder sees the longest input a dictation can carry.
        val speech = CLIPS.map { name ->
            pcm16ToFloats(instrumentation.context.assets.open(name).use { it.readBytes() })
        }.reduce { acc, clip -> acc + clip }
        val window = FloatArray(WINDOW_SAMPLES) { speech[it % speech.size] }

        val modelsDir = File(context.filesDir, "models")
        val download = InstrumentationRegistry.getArguments().getString("download") == "true"
        for ((tier, modelId) in TIER_MODELS) {
            val provider = ReadOnlyModelPathProvider(modelsDir, modelId)
            if (!provider.isModelDownloaded(modelId) && download) {
                log("$modelId missing, downloading once...")
                runBlocking {
                    withTimeout(15.minutes) {
                        WhisperModelProvider(context, HttpClient(OkHttp), coredevices.util.AndroidPlatform(context))
                            .getModelPath(modelId)
                    }
                }
            }
            if (!provider.isModelDownloaded(modelId)) {
                log("$modelId not installed; skipping (pass -e download true to fetch it)")
                continue
            }
            val handle = whisperInit(runBlocking { provider.getModelPath(modelId) })
            try {
                var callId = 1L
                whisperTranscribe(handle, FloatArray(16_000), threads, "en", callId++, EnginePlacement.DEFAULT)
                val times = ArrayList<Long>(RUNS)
                var words = 0
                repeat(RUNS) {
                    val start = TimeSource.Monotonic.markNow()
                    val text = whisperTranscribe(handle, window, threads, "en", callId++, EnginePlacement.DEFAULT)
                    times += start.elapsedNow().inWholeMilliseconds
                    words = text.split(' ').count { it.isNotBlank() }
                }
                val windowMs = median(times)
                val predicted = WhisperSpeedCalibration.estimateWindowSeconds(
                    modelId, SpeedScore(score, threads, 0L),
                )
                log(
                    "tier=$tier model=$modelId windowMs=$times median=$windowMs words=$words " +
                        "referenceSeconds=${"%.2f".format(windowMs / 1000.0)} " +
                        "predictedSeconds=${predicted?.let { "%.2f".format(it) } ?: "uncalibrated"} " +
                        "(prediction includes the ${WhisperSpeedCalibration.BACKGROUND_MARGIN}x background margin)",
                )
            } finally {
                whisperFree(handle)
            }
        }
    }
}
