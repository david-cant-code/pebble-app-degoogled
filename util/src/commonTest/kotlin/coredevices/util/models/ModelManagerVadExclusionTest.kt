package coredevices.util.models

import coredevices.util.transcription.CactusModelPathProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The voice activity detector's directory must never surface as a stale
 * model in the picker, even from a provider that lists it: the provider
 * already filters it, and this is the second layer for a provider that
 * does not.
 */
class ModelManagerVadExclusionTest {

    private class ListingProvider(private val dirs: List<String>) : CactusModelPathProvider {
        override suspend fun getSTTModelPath(): String = error("unused")
        override suspend fun getLMModelPath(): String = error("unused")
        override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String = error("unused")
        override fun isModelDownloaded(modelName: String): Boolean = false
        override fun getDownloadedModels(): List<String> = dirs
        override fun getIncompatibleModels(): List<String> = emptyList()
        override fun deleteModel(modelName: String) {}
        override fun getModelSizeBytes(modelName: String): Long = 1024L * 1024
        override fun initTelemetry() {}
    }

    @Test
    fun detectorDirectoryIsNotListedAsAStaleModel() {
        val provider = ListingProvider(listOf(WhisperModelCatalog.VAD_MODEL.id, "parakeet-tdt-0.6b-v3"))
        val slugs = ModelManager.availableSTTModels(provider).map { it.slug }
        assertEquals(WhisperModelCatalog.MODELS.map { it.id } + "parakeet-tdt-0.6b-v3", slugs)
    }
}
