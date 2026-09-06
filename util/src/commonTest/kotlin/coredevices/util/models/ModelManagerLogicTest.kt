package coredevices.util.models

import coredevices.util.transcription.CactusModelPathProvider
import coredevices.util.transcription.SpeedScore
import coredevices.util.transcription.WhisperSpeedCalibration
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
    fun modelInfoRoundsTheSizeToTheNearestMebibyte() {
        assertEquals(465, ModelManager.modelInfoFor(WhisperModelCatalog.byId("whisper-small-en")!!).sizeInMB)
        assertEquals(141, ModelManager.modelInfoFor(WhisperModelCatalog.byId("whisper-base-en")!!).sizeInMB)
        assertEquals(74, ModelManager.modelInfoFor(WhisperModelCatalog.byId("whisper-tiny-en")!!).sizeInMB)
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
        // their size comes from the on-disk measurement, rounded to the
        // nearest MiB like a catalog row (406.67 MiB reads as 407).
        val staleBytes = 406L * 1024 * 1024 + 700_000
        val provider = FakeProvider(
            downloaded = listOf("parakeet-tdt-0.6b-v3", "whisper-base-en"),
            sizes = mapOf("parakeet-tdt-0.6b-v3" to staleBytes),
        )
        val available = ModelManager.availableSTTModels(provider)
        val stale = available.firstOrNull { it.slug == "parakeet-tdt-0.6b-v3" }
        assertTrue(stale != null, "stale non-catalog directory must stay listed for deletion")
        assertEquals(407, stale.sizeInMB)
        // A downloaded catalog model is not double-listed as stale.
        assertEquals(1, available.count { it.slug == "whisper-base-en" })
    }

    private val eightGiB = 8L * 1024 * 1024 * 1024
    private val twoGiB = 2L * 1024 * 1024 * 1024

    /** A score at which [tier] is estimated at [seconds] for a full window on this phone. */
    private fun scoreFor(tier: WhisperTier, seconds: Double): SpeedScore {
        val reference = WhisperSpeedCalibration.referenceWindowSeconds(tier)!!
        val ratio = seconds / (reference * WhisperSpeedCalibration.BACKGROUND_MARGIN)
        return SpeedScore((WhisperSpeedCalibration.REFERENCE_SCORE_NS * ratio).toLong(), threads = 2, measuredAtEpochMs = 0L)
    }

    @Test
    fun recommendationWithoutAScoreIsTheRamTier() {
        assertEquals(RecommendedModel.Standard("whisper-small-en"), ModelManager.recommendedModel(eightGiB, true, null))
        assertEquals(RecommendedModel.Standard("whisper-small"), ModelManager.recommendedModel(eightGiB, false, null))
        assertEquals(RecommendedModel.Lite("whisper-base-en"), ModelManager.recommendedModel(twoGiB, true, null))
    }

    @Test
    fun recommendationStepsDownWhileTheEstimateExceedsTheWindow() {
        // Small at 20 s exceeds; base is several times cheaper and fits.
        val slow = scoreFor(WhisperTier.Small, 20.0)
        assertEquals(RecommendedModel.Lite("whisper-base-en"), ModelManager.recommendedModel(eightGiB, true, slow))
        assertEquals(RecommendedModel.Lite("whisper-base"), ModelManager.recommendedModel(eightGiB, false, slow))
        // Base at 20 s exceeds too: the walk ends on the tiny floor.
        val verySlow = scoreFor(WhisperTier.Base, 20.0)
        assertEquals(RecommendedModel.Minimal("whisper-tiny-en"), ModelManager.recommendedModel(eightGiB, true, verySlow))
        assertEquals(RecommendedModel.Minimal("whisper-tiny-en"), ModelManager.recommendedModel(twoGiB, true, verySlow))
        // Tiny exceeding as well still yields tiny: there is nothing cheaper.
        val glacial = scoreFor(WhisperTier.Tiny, 20.0)
        assertEquals(RecommendedModel.Minimal("whisper-tiny"), ModelManager.recommendedModel(eightGiB, false, glacial))
    }

    @Test
    fun availableModelsCarryEstimatesOnlyWithAScore() {
        val without = ModelManager.availableSTTModels(FakeProvider())
        assertTrue(without.all { it.estimatedWindowSeconds == null })
        val score = scoreFor(WhisperTier.Base, 4.0)
        val with = ModelManager.availableSTTModels(FakeProvider(), score)
        assertEquals(4.0, with.first { it.slug == "whisper-base-en" }.estimatedWindowSeconds!!, 1e-6)
        assertTrue(with.first { it.slug == "whisper-small" }.estimatedWindowSeconds!! > 4.0)
    }
}
