package coredevices.util.models

import coredevices.util.transcription.CactusModelPathProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host tests for ModelManager's provider-facing catalog logic, exercised
 * through the static seams the instance methods delegate to so no
 * platform-specific ModelDownloadManager is needed. Covers the two
 * decisions the settings UI depends on: which downloaded slugs count as
 * usable STT models, and how the available list merges the catalog with
 * stale non-catalog directories.
 */
class ModelManagerLogicTest {

    private class FakeProvider(
        val downloaded: List<String> = emptyList(),
        val installed: Set<String> = emptySet(),
        val sizes: Map<String, Long> = emptyMap(),
    ) : CactusModelPathProvider {
        override suspend fun getSTTModelPath(): String = error("unused")
        override suspend fun getLMModelPath(): String = error("unused")
        override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String = error("unused")
        override fun isModelDownloaded(modelName: String): Boolean = modelName in installed
        override fun getDownloadedModels(): List<String> = downloaded
        override fun getIncompatibleModels(): List<String> = emptyList()
        override fun deleteModel(modelName: String) {}
        override fun getModelSizeBytes(modelName: String): Long = sizes[modelName] ?: 0L
        override fun initTelemetry() {}
    }

    @Test
    fun downloadedSlugsKeepOnlyInstalledCatalogModels() {
        // A directory can be listed (present) but not in installed shape
        // (torn install / quarantine leftover); only installed ones gate
        // the local speech section, so those must be filtered out.
        val provider = FakeProvider(
            downloaded = listOf("whisper-base-en", "whisper-small", "parakeet-tdt-0.6b-v3"),
            installed = setOf("whisper-base-en"),
        )
        assertEquals(listOf("whisper-base-en"), ModelManager.downloadedSTTModelSlugs(provider))
    }

    @Test
    fun downloadedSlugsAreEmptyWithoutAProvider() {
        assertEquals(emptyList(), ModelManager.downloadedSTTModelSlugs(null))
    }

    @Test
    fun availableModelsAreTheWholeCatalogWhenNothingIsDownloaded() {
        val available = ModelManager.availableSTTModels(FakeProvider())
        assertEquals(
            WhisperModelCatalog.MODELS.map { it.id }.toSet(),
            available.map { it.slug }.toSet(),
        )
        // Catalog sizes come from the pins so the UI can show them pre-download.
        val base = available.first { it.slug == "whisper-base-en" }
        assertTrue(base.sizeInMB > 0)
        assertTrue(base.url.isNotBlank())
    }

    @Test
    fun availableModelsAppendStaleNonCatalogDirectories() {
        // Previous-engine leftovers stay visible so they remain deletable;
        // their size comes from the on-disk measurement, not a pin.
        val staleBytes = 406L * 1024 * 1024
        val provider = FakeProvider(
            downloaded = listOf("parakeet-tdt-0.6b-v3", "whisper-base-en"),
            sizes = mapOf("parakeet-tdt-0.6b-v3" to staleBytes),
        )
        val available = ModelManager.availableSTTModels(provider)
        val stale = available.firstOrNull { it.slug == "parakeet-tdt-0.6b-v3" }
        assertTrue(stale != null, "stale non-catalog directory must stay listed for deletion")
        assertEquals(406, stale.sizeInMB)
        // A downloaded catalog model is not double-listed as stale.
        assertEquals(1, available.count { it.slug == "whisper-base-en" })
    }
}
