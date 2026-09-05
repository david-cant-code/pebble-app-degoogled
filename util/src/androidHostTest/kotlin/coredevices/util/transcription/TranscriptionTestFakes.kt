package coredevices.util.transcription

import coredevices.analytics.CoreAnalytics
import coredevices.whisper.EnginePlacement
import coredevices.whisper.TranscribeStats
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

/** A provider with one installed model and, when [vadPath] is set, the detector. */
internal class FakeModelProvider(@Volatile var vadPath: String? = null) : CactusModelPathProvider {
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

/**
 * An engine that answers [reply] for real audio and "" for the all-zero
 * warm-up pass, counting the real calls so a test can tell whether the
 * local model ran.
 */
internal class FakeWhisperEngine(@Volatile var reply: String = "hello world") {
    @Volatile var realCalls = 0

    val engine = object : WhisperEngine {
        override fun supported(): Boolean = true
        override fun init(modelPath: String): Long = 1L
        override fun vadInit(modelPath: String): Long = 7L
        override fun vadFree(handle: Long) {}
        override fun transcribe(
            handle: Long,
            pcm: FloatArray,
            threads: Int,
            language: String?,
            callId: Long,
            placement: EnginePlacement,
            vadHandle: Long,
            stats: TranscribeStats?,
        ): String {
            if (pcm.all { it == 0f }) return ""
            realCalls++
            return reply
        }
        override fun cancel(callId: Long) {}
        override fun free(handle: Long) {}
    }
}

/** Real-looking PCM16 bytes: a quarter second of a non-zero ramp at 16 kHz. */
internal fun realPcmBytes(): ByteArray = ByteArray(8000) { (it % 100 + 1).toByte() }
