package coredevices.coreapp.transcription

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.coreapp.model.WhisperModelProvider
import coredevices.coreapp.testsupport.NoopAnalytics
import coredevices.coreapp.testsupport.ReadOnlyModelPathProvider
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.util.transcription.NoOpInferenceBoost
import coredevices.util.transcription.WhisperTranscriptionService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.sin
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Instrumented diagnostics for local transcription cancellation on the whisper engine. Runs the
 * *real* native model on-device, so it answers the question "does cancelling the coroutine
 * actually stop the native inference, and how promptly?", which is what the service's cancellation
 * wiring (the armed cancel flag polled by the engine's abort callback) relies on.
 *
 * The model is loaded once and shared across the tests (a fresh service per test re-runs engine
 * init, which skews timings).
 *
 * If the model isn't present it's downloaded on demand (one-time). Note: `gradle
 * connectedAndroidTest` uninstalls the app afterwards, wiping the model, so it re-downloads each
 * run; run via `adb shell am instrument` against a persistent install to avoid that:
 *   adb shell am instrument -w \
 *     -e class coredevices.coreapp.transcription.WhisperLocalCancellationTest \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class WhisperLocalCancellationTest {
    private companion object {
        const val MODEL_NAME = "whisper-base-en"
        const val SAMPLE_RATE = 16_000

        // Long enough that baseline inference is far larger than BUDGET + MAX_UNWIND, so "did the
        // budget bound it?" is unambiguous. Pure tone; content is irrelevant.
        val AUDIO_DURATION = 300.seconds

        // How long we let inference run before cancelling / the phone-side budget under test.
        val PRE_CANCEL_DELAY = 3.seconds
        val BUDGET = 4.seconds

        // A cooperative native stop should unwind well within this. If it doesn't, the abort
        // callback is not being honoured by the native inference loop.
        val MAX_UNWIND = 5.seconds

        // A real speech clip (raw PCM 16k/mono/s16 in test assets) for the
        // post-cancel recovery assertion: the wedge symptom was a blank
        // result, so a non-blank transcription containing this word proves
        // the handle is healthy after a cancellation.
        const val CLIP_ASSET = "eval_shopping_list_shrimp.raw"
        const val CLIP_KEYWORD = "shrimp"

        // Generous wall-clock ceiling for one decode with no caller timeout.
        // The native decode bound (temperature ladder off, token cap) turns
        // the measured 38-89 s degraded-audio runaway into seconds; this
        // bound is far under that runaway yet well above a healthy decode,
        // so it fails only if those native parameters regress.
        val DECODE_LATENCY_BOUND = 25.seconds

        private val initLock = Any()
        private var sharedService: WhisperTranscriptionService? = null
        private var modelPresent = false
        private var clip: ByteArray = ByteArray(0)
    }

    private lateinit var service: WhisperTranscriptionService

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Use the running app's Koin graph (MainApplication started it). Do NOT stop/replace it:
        // the live app (PebbleService, its uncaught-exception handler) depends on that graph.
        synchronized(initLock) {
            if (sharedService == null) {
                clip = InstrumentationRegistry.getInstrumentation().context.assets
                    .open(CLIP_ASSET).use { it.readBytes() }
                // Read-only provider points at the existing model and never downloads or deletes,
                // so a test run can't wipe it; the service uses this for the whole run.
                val modelsDir = File(context.filesDir, "models")
                val provider = ReadOnlyModelPathProvider(modelsDir, MODEL_NAME)

                if (!provider.isModelDownloaded(MODEL_NAME)) {
                    println("[whisper-cancel] model missing, downloading $MODEL_NAME (one-time)...")
                    // Only the *production* provider downloads, through its full verify gate.
                    runBlocking {
                        withTimeout(20.minutes) {
                            WhisperModelProvider(context, HttpClient(OkHttp), coredevices.util.AndroidPlatform(context)).getModelPath(MODEL_NAME)
                        }
                    }
                }
                modelPresent = provider.isModelDownloaded(MODEL_NAME)
                println("[whisper-cancel] model present=$modelPresent")

                if (modelPresent) {
                    val svc = WhisperTranscriptionService(
                        coreConfigFlow = CoreConfigFlow(
                            MutableStateFlow(
                                CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = MODEL_NAME)),
                            ),
                        ),
                        modelProvider = provider,
                        analytics = NoopAnalytics,
                        inferenceBoost = NoOpInferenceBoost(),
                    )
                    val load = TimeSource.Monotonic.markNow()
                    runBlocking {
                        svc.earlyInit()
                        withTimeout(2.minutes) { while (!svc.isModelReady) delay(200) }
                    }
                    println("[whisper-cancel] model loaded in ${load.elapsedNow()}")
                    sharedService = svc
                }
            }
        }
        Assume.assumeTrue("STT model '$MODEL_NAME' unavailable (download failed?)", modelPresent)
        service = sharedService!!
    }

    /** Quiet sine tone PCM_16BIT mono; keeps the model busy for the buffer's full duration. */
    private fun tonePcm(duration: Duration): ByteArray {
        val samples = (SAMPLE_RATE * duration.inWholeMilliseconds / 1000).toInt()
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2.0 * Math.PI * 220.0 * i / SAMPLE_RATE) * 4000).toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Run a transcription, swallowing the *result* outcome (NoSpeechDetected etc.; we feed a tone,
     * so a blank result is expected). Cancellation is rethrown so it stays cooperative. We only
     * care about timing/completion here, not the recognised text.
     */
    private suspend fun runTranscriptionIgnoringResult(audio: ByteArray) {
        try {
            service.transcribeLocal(audio = audio, sampleRate = SAMPLE_RATE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // result error (e.g. NoSpeechDetected); irrelevant to a timing test
        }
    }

    /**
     * Baseline: how long does an *uncancelled* local transcription of the buffer take? This is the
     * number a cancel has to beat.
     */
    @Test
    fun baseline_uncancelledLocalTranscriptionDuration() = runBlocking(Dispatchers.Default) {
        val audio = tonePcm(AUDIO_DURATION)
        val mark = TimeSource.Monotonic.markNow()
        runTranscriptionIgnoringResult(audio)
        val elapsed = mark.elapsedNow()
        println("[whisper-cancel] baseline uncancelled inference of $AUDIO_DURATION audio took $elapsed")
        assertTrue(elapsed > Duration.ZERO)
    }

    /**
     * Cancelling the collecting coroutine mid-inference must make the native call unwind promptly
     * (via the armed cancel flag). We cancel after [PRE_CANCEL_DELAY] and assert the job actually
     * finishes within [MAX_UNWIND]. If native ignores the abort callback, join() blocks for the
     * whole buffer and the outer withTimeout fails the test with a clear message.
     */
    @Test
    fun cancellingTranscriptionUnwindsPromptly() = runBlocking(Dispatchers.Default) {
        val audio = tonePcm(AUDIO_DURATION)

        val started = CompletableDeferred<Unit>()
        val job: Job = launch {
            started.complete(Unit)
            runTranscriptionIgnoringResult(audio)
        }
        started.await()
        delay(PRE_CANCEL_DELAY)
        assertTrue(
            job.isActive,
            "inference finished before we could cancel (${PRE_CANCEL_DELAY}); increase AUDIO_DURATION",
        )

        val cancelMark = TimeSource.Monotonic.markNow()
        job.cancel()
        try {
            withTimeout(MAX_UNWIND) { job.join() }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "Native transcription did not unwind within $MAX_UNWIND of cancellation; the " +
                    "abort callback is not being honoured by the native inference loop.",
            )
        }
        val unwind = cancelMark.elapsedNow()
        println("[whisper-cancel] native inference unwound $unwind after cancel")
        assertTrue(unwind < MAX_UNWIND, "cancellation unwind took $unwind, expected < $MAX_UNWIND")
    }

    /**
     * The phone-side timeout (e.g. the 14s budget) must actually bound the native work. With a
     * [BUDGET] far shorter than the baseline, the call must return at ~[BUDGET], not run to
     * completion. Asserted on elapsed wall time, not on which exception surfaces: a blocking
     * native call that ignores cancellation masks the TimeoutCancellationException, so the type is
     * unreliable; the timing is what matters.
     */
    @Test
    fun withTimeoutBoundsLocalTranscription() = runBlocking(Dispatchers.Default) {
        val audio = tonePcm(AUDIO_DURATION)
        val mark = TimeSource.Monotonic.markNow()
        try {
            withTimeout(BUDGET) { runTranscriptionIgnoringResult(audio) }
        } catch (_: TimeoutCancellationException) {
            // expected when the budget is actually enforced
        }
        val elapsed = mark.elapsedNow()
        println("[whisper-cancel] withTimeout($BUDGET) returned after $elapsed")
        assertTrue(
            elapsed < BUDGET + MAX_UNWIND,
            "withTimeout took $elapsed; native work was not bounded by the $BUDGET budget; the " +
                "abort callback is not honoured during in-flight inference.",
        )
    }

    /**
     * The reported symptom of the cancellation bug was that a cancelled dictation kept the busy gate
     * occupied, failing every later attempt. This asserts recovery directly: after cancelling a
     * long-running transcription, a fresh transcription of a real speech clip must succeed with
     * healthy (non-blank, expected-word) text, proving the busy gate, model mutex, and native handle
     * are all released and usable.
     */
    @Test
    fun transcriptionAfterCancellationSucceeds() = runBlocking(Dispatchers.Default) {
        val tone = tonePcm(AUDIO_DURATION)
        val job: Job = launch { runTranscriptionIgnoringResult(tone) }
        delay(PRE_CANCEL_DELAY)
        assertTrue(job.isActive, "inference finished before we could cancel; increase AUDIO_DURATION")
        job.cancel()
        withTimeout(MAX_UNWIND) { job.join() }

        // The real recovery assertion: a new transcription must genuinely run and return real text.
        val text = service.transcribeLocal(audio = clip, sampleRate = SAMPLE_RATE, timeout = 30.seconds)
        println("[whisper-cancel] post-cancel transcription: '$text'")
        assertTrue(text.isNotBlank(), "post-cancel transcription came back blank; the handle wedged")
        assertTrue(
            text.lowercase().contains(CLIP_KEYWORD),
            "post-cancel transcription '$text' is missing '$CLIP_KEYWORD'; the handle produced garbage",
        )
    }

    /**
     * The native decode bound (temperature-fallback ladder disabled, per-segment token cap) is what
     * keeps a degraded-audio decode from running tens of seconds. With no caller timeout at all, a
     * full firmware-window clip must still return well within [DECODE_LATENCY_BOUND]; dropping those
     * native parameters reopens the 38-89 s runaway and fails this. Random noise is the worst case
     * for the decoder (it drives the repetition/hallucination the bound contains).
     */
    @Test
    fun decodeLatencyIsBoundedWithoutACallerTimeout() = runBlocking(Dispatchers.Default) {
        val noise = ByteArray(SAMPLE_RATE * 15 * 2)
        var state = 0x2BAD_C0DE
        for (i in noise.indices) {
            // Cheap deterministic pseudo-noise; no Random import, reproducible across runs.
            state = state * 1103515245 + 12345
            noise[i] = (state ushr 16).toByte()
        }
        val mark = TimeSource.Monotonic.markNow()
        runTranscriptionIgnoringResult(noise)
        val elapsed = mark.elapsedNow()
        println("[whisper-cancel] unbounded decode of 15s noise returned after $elapsed")
        assertTrue(
            elapsed < DECODE_LATENCY_BOUND,
            "decode took $elapsed with no caller timeout; the native decode-latency bound " +
                "(temperature ladder off, token cap) has regressed.",
        )
    }
}
