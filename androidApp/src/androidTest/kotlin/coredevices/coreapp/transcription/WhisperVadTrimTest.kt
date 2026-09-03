package coredevices.coreapp.transcription

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.coreapp.model.WhisperModelProvider
import coredevices.coreapp.testsupport.ReadOnlyModelPathProvider
import coredevices.util.models.WhisperModelCatalog
import coredevices.whisper.EnginePlacement
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
 * without it; a silence-only clip returns "" without an encoder pass.
 * The detector file is downloaded once through the production provider
 * if absent, like the speech model in the other suites.
 */
class WhisperVadTrimTest {

    private companion object {
        const val MODEL_NAME = "whisper-base-en"
        const val CLIP_ASSET = "eval_shopping_list_shrimp.raw"
        const val KEYWORD = "shrimp"
        const val PAD_SECONDS = 6
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
    fun paddedClipDecodesFasterWithTheDetectorAndSilenceReturnsNothing() {
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
        val silence = FloatArray(10 * 16_000)

        val handle = whisperInit(runBlocking { provider.getModelPath(MODEL_NAME) })
        val vad = whisperVadInit(vadPath!!)
        var callId = 1L
        try {
            // Warm-up absorbs the one-time graph and buffer setup.
            whisperTranscribe(handle, clip, 4, "en", callId++, EnginePlacement.DEFAULT)

            val (plainText, plainMs) = timed { whisperTranscribe(handle, padded, 4, "en", callId++, EnginePlacement.DEFAULT) }
            val (vadText, vadMs) = timed { whisperTranscribe(handle, padded, 4, "en", callId++, EnginePlacement.DEFAULT, vad) }
            val (silenceText, silenceMs) = timed { whisperTranscribe(handle, silence, 4, "en", callId++, EnginePlacement.DEFAULT, vad) }
            log("padded ${padded.size / 16_000}s: untrimmed ${plainMs} ms '$plainText'; trimmed ${vadMs} ms '$vadText'; silence ${silenceMs} ms '$silenceText'")

            assertTrue(vadText.lowercase().contains(KEYWORD), "trimmed decode lost the keyword: '$vadText'")
            assertTrue(vadMs < plainMs, "trimmed decode ($vadMs ms) was not faster than untrimmed ($plainMs ms)")
            assertEquals("", silenceText, "silence must decode to nothing")
            assertTrue(silenceMs < 1500, "silence rejection took $silenceMs ms; it must not run the encoder")
        } finally {
            whisperVadFree(vad)
            whisperFree(handle)
        }
    }
}
