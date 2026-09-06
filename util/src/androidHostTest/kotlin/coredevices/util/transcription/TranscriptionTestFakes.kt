package coredevices.util.transcription

import coredevices.analytics.CoreAnalytics
import coredevices.whisper.EnginePlacement
import coredevices.whisper.TranscribeStats
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Instant

/** Analytics that record nothing: the fork's real backend is a no-op too. */
internal object NoopAnalytics : CoreAnalytics {
    override fun logEvent(name: String, parameters: Map<String, Any>?) {}
    override suspend fun logHeartbeatState(name: String, value: Boolean, timestamp: Instant) {}
    override suspend fun processHeartbeat() {}
    override fun updateLastConnectedSerial(serial: String?) {}
    override fun updateRingTransferDurationMetric(duration: Duration) {}
    override fun updateRingLifetimeCollectionCount(serial: String, count: Int) {}
}

/** A provider with one installed model. */
internal class FakeModelProvider : CactusModelPathProvider {
    override suspend fun getSTTModelPath(): String = "/fake/model"
    override suspend fun getLMModelPath(): String = error("no language model")
    override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String = "/fake/$modelId"
    override fun isModelDownloaded(modelName: String): Boolean = true
    override fun getDownloadedModels(): List<String> = emptyList()
    override fun getIncompatibleModels(): List<String> = emptyList()
    override fun deleteModel(modelName: String) {}
    override fun getModelSizeBytes(modelName: String): Long = 0L
    override fun initTelemetry() {}
}

/**
 * An engine that answers [reply] for real audio and "" for the all-zero
 * warm-up pass, counting the real calls so a test can tell whether the
 * local model ran. It reports the input size through the stats slot the
 * way the shim does (or nothing at all while [reportInput] is off), and a
 * real call blocks on [gate] while one is set, so a test can act while a
 * decode is in flight.
 */
internal class FakeWhisperEngine(@Volatile var reply: String = "hello world") {
    @Volatile var realCalls = 0
    @Volatile var reportInput = true
    @Volatile var gate: CountDownLatch? = null
    @Volatile var inRealTranscribe = false

    /** How long a real call takes; the speed record ignores a decode of zero milliseconds. */
    @Volatile var decodeMillis: Long = 0

    val engine = object : WhisperEngine {
        override fun supported(): Boolean = true
        override fun init(modelPath: String): Long = 1L
        override fun transcribe(
            handle: Long,
            pcm: FloatArray,
            threads: Int,
            language: String?,
            callId: Long,
            placement: EnginePlacement,
            stats: TranscribeStats?,
        ): String {
            if (pcm.all { it == 0f }) return ""
            realCalls++
            if (reportInput) stats?.inputSamples = pcm.size
            inRealTranscribe = true
            try {
                if (decodeMillis > 0) Thread.sleep(decodeMillis)
                // Bounded so a deadlocked test fails instead of hanging the run.
                gate?.await(20, TimeUnit.SECONDS)
            } finally {
                inRealTranscribe = false
            }
            return reply
        }
        override fun cancel(callId: Long) {}
        override fun free(handle: Long) {}
    }
}

/** Real-looking PCM16 bytes: [seconds] of a non-zero ramp at 16 kHz, a quarter second by default. */
internal fun realPcmBytes(seconds: Double = 0.25): ByteArray =
    ByteArray((seconds * 16_000 * 2).toInt()) { (it % 100 + 1).toByte() }
