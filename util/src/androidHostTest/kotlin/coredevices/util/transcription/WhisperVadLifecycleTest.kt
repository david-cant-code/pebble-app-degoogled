package coredevices.util.transcription

import coredevices.analytics.CoreAnalytics
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.whisper.EnginePlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Pins the voice activity detector's lifecycle in [WhisperTranscriptionService]
 * through the engine seam: loaded once alongside the first model init when
 * the provider has the file, handed to every real transcription and never
 * to the warm-up, and simply absent (untrimmed decoding, no failure) when
 * the provider has nothing.
 */
class WhisperVadLifecycleTest {

    private class FakeEngine {
        val vadInits = mutableListOf<String>()
        val transcribeVadHandles = mutableListOf<Long>()
        val warmUpVadHandles = mutableListOf<Long>()

        val engine = object : WhisperEngine {
            override fun supported(): Boolean = true
            override fun init(modelPath: String): Long = 1L
            override fun vadInit(modelPath: String): Long {
                vadInits += modelPath
                return 7L
            }
            override fun vadFree(handle: Long) {}
            override fun transcribe(
                handle: Long,
                pcm: FloatArray,
                threads: Int,
                language: String?,
                callId: Long,
                placement: EnginePlacement,
                vadHandle: Long,
                stats: coredevices.whisper.TranscribeStats?,
            ): String {
                if (pcm.all { it == 0f }) {
                    warmUpVadHandles += vadHandle
                    return ""
                }
                transcribeVadHandles += vadHandle
                return "hello world"
            }
            override fun cancel(callId: Long) {}
            override fun free(handle: Long) {}
        }
    }

    private class FakeProvider(private val vadPath: String?) : CactusModelPathProvider {
        override suspend fun getSTTModelPath(): String = "/fake/model"
        override suspend fun getLMModelPath(): String = error("no language model")
        override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String = "/fake/$modelId"
        override fun isModelDownloaded(modelName: String): Boolean = true
        override fun getDownloadedModels(): List<String> = emptyList()
        override fun getIncompatibleModels(): List<String> = emptyList()
        override fun deleteModel(modelName: String) {}
        override fun getModelSizeBytes(modelName: String): Long = 0L
        override fun initTelemetry() {}
        override suspend fun getVadModelPath(): String? = vadPath
        override fun isVadModelInstalled(): Boolean = vadPath != null
    }

    private object NoopAnalytics : CoreAnalytics {
        override fun logEvent(name: String, parameters: Map<String, Any>?) {}
        override suspend fun logHeartbeatState(name: String, value: Boolean, timestamp: Instant) {}
        override suspend fun processHeartbeat() {}
        override fun updateLastConnectedSerial(serial: String?) {}
        override fun updateRingTransferDurationMetric(duration: Duration) {}
        override fun updateRingLifetimeCollectionCount(serial: String, count: Int) {}
    }

    private fun serviceWith(fake: FakeEngine, vadPath: String?) = WhisperTranscriptionService(
        coreConfigFlow = CoreConfigFlow(
            MutableStateFlow(CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = "model-a"))),
        ),
        modelProvider = FakeProvider(vadPath),
        analytics = NoopAnalytics,
        inferenceBoost = NoOpInferenceBoost(),
        engine = fake.engine,
    )

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        try {
            withTimeout(60.seconds) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private fun realPcmBytes(): ByteArray = ByteArray(8000) { (it % 100 + 1).toByte() }

    @Test
    fun detectorLoadsWithTheModelAndReachesEveryRealTranscription() = runBlocking(Dispatchers.Default) {
        val fake = FakeEngine()
        val service = serviceWith(fake, vadPath = "/fake/vad-silero/ggml-silero.bin")
        awaitUntil("model init") { service.isModelReady }
        awaitUntil("detector init") { service.isVadReady }
        assertEquals(listOf("/fake/vad-silero/ggml-silero.bin"), fake.vadInits)

        service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        assertEquals(listOf(7L, 7L), fake.transcribeVadHandles)
        assertTrue(fake.warmUpVadHandles.all { it == 0L }, "the warm-up must bypass the detector")
        assertEquals(1, fake.vadInits.size, "one detector per process")
    }

    @Test
    fun absentDetectorMeansUntrimmedDecodingNotFailure() = runBlocking(Dispatchers.Default) {
        val fake = FakeEngine()
        val service = serviceWith(fake, vadPath = null)
        awaitUntil("model init") { service.isModelReady }
        delay(200)
        assertFalse(service.isVadReady)
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertEquals(listOf(0L), fake.transcribeVadHandles)
        assertTrue(fake.vadInits.isEmpty())
    }
}
