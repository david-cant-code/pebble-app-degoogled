package coredevices.coreapp.transcription

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.coreapp.model.WhisperModelProvider
import coredevices.coreapp.testsupport.ReadOnlyModelPathProvider
import coredevices.util.models.WhisperModelCatalog
import coredevices.whisper.EnginePlacement
import coredevices.whisper.TranscribeStats
import coredevices.whisper.isWhisperSupported
import coredevices.whisper.pcm16ToFloats
import coredevices.whisper.whisperFree
import coredevices.whisper.whisperInit
import coredevices.whisper.whisperTranscribe
import coredevices.whisper.whisperVadFree
import coredevices.whisper.whisperVadInit
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Exercises the voice activity detector through the engine binding on the
 * real engine: a speech clip padded with silence decodes to the same
 * keyword with the detector on, in less time than the padded clip takes
 * without it, and the decoded sample count shows the padding went; a clip
 * with a long silent gap between two utterances keeps the gap (only the
 * edges are ever cut); a silence-only clip is decoded untrimmed rather
 * than rejected on the detector's verdict. The detector file is downloaded
 * once through the production provider if absent, like the speech model
 * in the other suites.
 *
 * Run one class at a time against a persistent install; a
 * `connectedAndroidTest` run reinstalls the app, which wipes the model,
 * and the test then skips:
 *   adb shell am instrument -w \
 *     -e class coredevices.coreapp.transcription.WhisperVadTrimTest \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class WhisperVadTrimTest {

    private companion object {
        const val MODEL_NAME = "whisper-base-en"
        const val CLIP_ASSET = "eval_shopping_list_shrimp.raw"
        const val KEYWORD = "shrimp"
        const val PAD_SECONDS = 6
        const val GAP_SECONDS = 5
    }

    private fun log(line: String) {
        android.util.Log.i("VadTrimTest", line)
        println("[VadTrimTest] $line")
    }

    private fun timed(block: () -> String): Pair<String, Long> {
        val start = TimeSource.Monotonic.markNow()
        val text = block()
        return text to start.elapsedNow().inWholeMilliseconds
    }

    @Test
    fun detectorCutsOnlyTheEdgesAndNeverRejectsAudio() {
        Assume.assumeTrue("engine unsupported on this CPU", isWhisperSupported())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelsDir = File(context.filesDir, "models")
        val provider = ReadOnlyModelPathProvider(modelsDir, MODEL_NAME)
        Assume.assumeTrue("speech model '$MODEL_NAME' not installed", provider.isModelDownloaded(MODEL_NAME))

        if (!provider.isVadModelInstalled()) {
            log("detector missing, downloading once...")
            runBlocking {
                withTimeout(5.minutes) {
                    WhisperModelProvider(context, HttpClient(OkHttp), coredevices.util.AndroidPlatform(context))
                        .getModelPath(WhisperModelCatalog.VAD_MODEL.id)
                }
            }
        }
        val vadPath = runBlocking { provider.getVadModelPath() }
        Assume.assumeTrue("detector unavailable (download failed?)", vadPath != null)

        val clip = pcm16ToFloats(instrumentation.context.assets.open(CLIP_ASSET).use { it.readBytes() })
        val pad = FloatArray(PAD_SECONDS * 16_000)
        val padded = pad + clip + pad
        val gap = FloatArray(GAP_SECONDS * 16_000)
        val gapped = clip + gap + clip
        val silence = FloatArray(10 * 16_000)

        val handle = whisperInit(runBlocking { provider.getModelPath(MODEL_NAME) })
        val vad = whisperVadInit(vadPath!!)
        var callId = 1L
        try {
            // Warm-up absorbs the one-time graph and buffer setup.
            whisperTranscribe(handle, clip, 4, "en", callId++, EnginePlacement.DEFAULT)

            val (plainText, plainMs) = timed { whisperTranscribe(handle, padded, 4, "en", callId++, EnginePlacement.DEFAULT) }
            val vadStats = TranscribeStats()
            val (vadText, vadMs) = timed { whisperTranscribe(handle, padded, 4, "en", callId++, EnginePlacement.DEFAULT, vad, vadStats) }
            val gapStats = TranscribeStats()
            val (gapText, gapMs) = timed { whisperTranscribe(handle, gapped, 4, "en", callId++, EnginePlacement.DEFAULT, vad, gapStats) }
            val silenceStats = TranscribeStats()
            val (silenceText, silenceMs) = timed { whisperTranscribe(handle, silence, 4, "en", callId++, EnginePlacement.DEFAULT, vad, silenceStats) }
            log("padded ${padded.size / 16_000}s: untrimmed ${plainMs} ms '$plainText'; trimmed ${vadMs} ms '$vadText' (${vadStats.decodedSamples} samples)")
            log("gapped ${gapped.size / 16_000}s: ${gapMs} ms '$gapText' (${gapStats.decodedSamples} samples); silence ${silenceMs} ms '$silenceText' (${silenceStats.decodedSamples} samples)")

            assertTrue(vadText.lowercase().contains(KEYWORD), "trimmed decode lost the keyword: '$vadText'")
            assertTrue(vadMs < plainMs, "trimmed decode ($vadMs ms) was not faster than untrimmed ($plainMs ms)")
            assertTrue(
                vadStats.decodedSamples in 1 until clip.size + 16_000,
                "padding must be cut: decoded ${vadStats.decodedSamples} of ${padded.size} for a ${clip.size}-sample clip",
            )
            assertTrue(gapText.lowercase().contains(KEYWORD), "gapped decode lost the keyword: '$gapText'")
            assertTrue(
                gapStats.decodedSamples > 2 * clip.size + (GAP_SECONDS - 1) * 16_000,
                "the interior gap must be kept: decoded ${gapStats.decodedSamples} of ${gapped.size}",
            )
            assertEquals(silence.size, silenceStats.decodedSamples, "silence must be decoded untrimmed, not rejected by the detector")
        } finally {
            whisperVadFree(vad)
            whisperFree(handle)
        }
    }
}
