package coredevices.util.transcription

import coredevices.analytics.CoreAnalytics
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Host regression guard for the native handle lifecycle in
 * [WhisperTranscriptionService], driven through the [WhisperEngine] seam
 * with a scripted fake so the interleavings are deterministic and no
 * device or model download is involved.
 *
 * The guarded invariant: init and free participate in the same modelMutex
 * every other native call holds. Before the fix, a config-driven model
 * switch freed the context while a transcription could still be inside it
 * on the worker thread (native use-after-free), and two uncoordinated init
 * jobs could double-init or double-free the handle. The fresh-handle
 * warm-up test pins the model-switch half of the cold-handle fix (a fresh
 * handle must warm up regardless of how recently the previous model was
 * used); the instrumented WhisperColdStartRaceTest keeps guarding the same
 * design against the real engine.
 */
class WhisperHandleLifecycleTest {

    /**
     * Scripted engine: handles are 1, 2, ... in init order. A transcribe of
     * silent PCM (the warm-up input) returns immediately; a transcribe of
     * real PCM blocks on [transcribeGate] so tests control how long the
     * engine call is "inside" the context.
     */
    private class FakeEngine {
        val transcribeGate = CountDownLatch(1)
        private val lock = Any()
        private var nextHandle = 1L

        var initCount = 0
            private set
        var freeCount = 0
            private set
        val initedPaths = mutableListOf<String>()
        val warmedUpHandles = mutableListOf<Long>()

        @Volatile
        var inRealTranscribe = false

        /** True if free() ever ran while a real transcribe was in flight: the use-after-free. */
        @Volatile
        var freedWhileTranscribing = false

        val engine = object : WhisperEngine {
            override fun supported(): Boolean = true

            override fun init(modelPath: String): Long = synchronized(lock) {
                initCount++
                initedPaths.add(modelPath)
                nextHandle++
                nextHandle - 1
            }

            override fun vadInit(modelPath: String): Long = 0L
            override fun vadFree(handle: Long) {}

            override fun transcribe(
                handle: Long,
                pcm: FloatArray,
                threads: Int,
                language: String?,
                callId: Long,
                placement: coredevices.whisper.EnginePlacement,
                vadHandle: Long,
                stats: coredevices.whisper.TranscribeStats?,
            ): String {
                if (pcm.all { it == 0f }) {
                    synchronized(lock) { warmedUpHandles.add(handle) }
                    return ""
                }
                inRealTranscribe = true
                try {
                    // Bounded so a deadlocked test fails instead of hanging the run.
                    transcribeGate.await(20, TimeUnit.SECONDS)
                } finally {
                    inRealTranscribe = false
                }
                return "hello world"
            }

            override fun cancel(callId: Long) {}

            override fun free(handle: Long) {
                if (inRealTranscribe) freedWhileTranscribing = true
                synchronized(lock) { freeCount++ }
            }
        }
    }

    private class FakeProvider : CactusModelPathProvider {
        override suspend fun getSTTModelPath(): String = error("unused in these tests")
        override suspend fun getLMModelPath(): String = error("unused in these tests")
        override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String = "/fake/$modelId"
        override fun isModelDownloaded(modelName: String): Boolean = true
        override fun getDownloadedModels(): List<String> = emptyList()
        override fun getIncompatibleModels(): List<String> = emptyList()
        override fun deleteModel(modelName: String) {}
        override fun getModelSizeBytes(modelName: String): Long = 0L
        override fun initTelemetry() {}
    }

    private object NoopAnalytics : CoreAnalytics {
        override fun logEvent(name: String, parameters: Map<String, Any>?) {}
        override suspend fun logHeartbeatState(name: String, value: Boolean, timestamp: Instant) {}
        override suspend fun processHeartbeat() {}
        override fun updateLastConnectedSerial(serial: String?) {}
        override fun updateRingTransferDurationMetric(duration: Duration) {}
        override fun updateRingLifetimeCollectionCount(serial: String, count: Int) {}
    }

    private fun configFor(modelName: String) = CoreConfig(
        sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = modelName),
    )

    private fun serviceFor(fake: FakeEngine, config: MutableStateFlow<CoreConfig>) =
        WhisperTranscriptionService(
            coreConfigFlow = CoreConfigFlow(config),
            modelProvider = FakeProvider(),
            analytics = NoopAnalytics,
            inferenceBoost = NoOpInferenceBoost(),
            engine = fake.engine,
        )

    /** Non-zero PCM16 bytes so the fake treats the call as a real transcription. */
    private fun realPcmBytes(): ByteArray = ByteArray(8000) { (it % 100 + 1).toByte() }

    // Generous bound: every wait returns as soon as its condition holds, so
    // the value only stretches a failing run, and a CI runner sharing its
    // cores with parallel compilation can stall a passing one for seconds.
    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        try {
            withTimeout(60.seconds) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    @Test
    fun modelSwitchWaitsForInFlightTranscription() = runBlocking(Dispatchers.Default) {
        val fake = FakeEngine()
        val config = MutableStateFlow(configFor("model-a"))
        val service = serviceFor(fake, config)
        awaitUntil("initial init of model-a") { service.isModelReady }

        val result = async { service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) }
        awaitUntil("transcription inside the engine") { fake.inRealTranscribe }

        // Switch models while the engine call is in flight. The re-init
        // must queue behind the transcription on modelMutex, not free the
        // context under it. The delay gives a regressed (unlocked) free
        // ample time to happen before the negative assertion.
        config.value = configFor("model-b")
        delay(300)
        assertEquals(
            0, fake.freeCount,
            "model switch freed the native context while a transcription was inside the engine",
        )

        fake.transcribeGate.countDown()
        assertEquals("hello world", result.await())
        awaitUntil("re-init of model-b") { fake.initedPaths.contains("/fake/model-b") }
        assertFalse(fake.freedWhileTranscribing)
        assertEquals(1, fake.freeCount)
    }

    @Test
    fun concurrentInitRequestsInitializeExactlyOnce() = runBlocking(Dispatchers.Default) {
        val fake = FakeEngine()
        val config = MutableStateFlow(configFor("model-a"))
        val service = serviceFor(fake, config)

        // Pile early-init kicks on top of the construction-time config
        // observer; every one of them may launch an init job, and the
        // serialized re-check inside initIfNeeded must collapse them into
        // a single engine init with nothing to free.
        service.earlyInit()
        service.earlyInit()
        awaitUntil("init settled") { service.isModelReady }
        delay(300) // let any duplicate init jobs run their course

        assertEquals(1, fake.initCount)
        assertEquals(0, fake.freeCount)
    }

    @Test
    fun modelSwitchWarmsUpTheFreshHandle() = runBlocking(Dispatchers.Default) {
        val fake = FakeEngine()
        val config = MutableStateFlow(configFor("model-a"))
        val service = serviceFor(fake, config)
        awaitUntil("initial init of model-a") { service.isModelReady }
        awaitUntil("warm-up of model-a's handle") { 1L in fake.warmedUpHandles }

        // Switch immediately: the recency mark is fresh from model-a's
        // warm-up, which is exactly the state that used to skip the fresh
        // handle's warm-up and hand the first real dictation the one-time
        // graph/buffer setup cost (the cold-handle model-switch bug).
        config.value = configFor("model-b")
        awaitUntil("re-init of model-b") { fake.initedPaths.contains("/fake/model-b") }
        awaitUntil("warm-up of model-b's fresh handle") { 2L in fake.warmedUpHandles }
        assertTrue(fake.freedWhileTranscribing.not())
    }
}
