package coredevices.coreapp.testsupport

import coredevices.analytics.CoreAnalytics
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.transcription.CactusModelPathProvider
import java.io.File
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Read-only view of the already-downloaded model directory for on-device STT tests. Unlike the
 * production provider it never downloads, deletes, or quarantines, so running the tests can't wipe
 * the on-device model.
 */
class ReadOnlyModelPathProvider(
    private val modelsDir: File,
    private val sttModelId: String,
) : CactusModelPathProvider {
    private fun installedFile(modelId: String): File? {
        val model = WhisperModelCatalog.byId(modelId) ?: return null
        return modelsDir.resolve(model.id).resolve(model.fileName)
    }

    override suspend fun getSTTModelPath(): String = getModelPath(sttModelId)
    override suspend fun getLMModelPath(): String = error("no language model in this fork")
    override suspend fun getModelPath(modelId: String): String =
        installedFile(modelId)?.takeIf { it.isFile }?.absolutePath
            ?: error("model $modelId not installed; tests never download")

    override fun isModelDownloaded(modelName: String): Boolean {
        val model = WhisperModelCatalog.byId(modelName) ?: return false
        return modelsDir.resolve(model.id).resolve(model.fileName)
            .let { it.isFile && it.length() == model.sizeBytes }
    }

    override fun getDownloadedModels(): List<String> =
        modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

    override fun getIncompatibleModels(): List<String> = emptyList()
    override fun deleteModel(modelName: String) { /* never delete in tests */ }
    override fun getModelSizeBytes(modelName: String): Long = 0L
    override fun initTelemetry() {}
}

/** No-op analytics for on-device STT tests. */
object NoopAnalytics : CoreAnalytics {
    override fun logEvent(name: String, parameters: Map<String, Any>?) {}
    override suspend fun logHeartbeatState(name: String, value: Boolean, timestamp: Instant) {}
    override suspend fun processHeartbeat() {}
    override fun updateLastConnectedSerial(serial: String?) {}
    override fun updateRingTransferDurationMetric(duration: Duration) {}
    override fun updateRingLifetimeCollectionCount(serial: String, count: Int) {}
}
