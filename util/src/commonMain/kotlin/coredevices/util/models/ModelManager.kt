package coredevices.util.models

import coredevices.util.Platform
import coredevices.util.transcription.CactusModelPathProvider

/**
 * UI-facing surface over the whisper model catalog and the provider that
 * installs from it. The Cactus-era language-model surface is gone with the
 * engine: only STT models exist in this fork.
 */
class ModelManager(
    private val platform: Platform,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPathProvider: CactusModelPathProvider? = null,
) {
    val modelDownloadStatus = modelDownloadManager.downloadStatus

    fun downloadSTTModel(modelInfo: ModelInfo, allowMetered: Boolean): Boolean {
        return modelDownloadManager.downloadSTTModel(modelInfo, allowMetered)
    }

    fun cancelDownload() {
        modelDownloadManager.cancelDownload()
    }

    fun getDownloadedModelSlugs(): List<String> {
        return modelPathProvider?.getDownloadedModels() ?: emptyList()
    }

    /**
     * Catalog models actually installed and usable by the engine; the raw
     * directory view above additionally includes stale previous-engine
     * leftovers so they stay deletable.
     */
    fun getDownloadedSTTModelSlugs(): List<String> {
        return getDownloadedModelSlugs().filter { modelPathProvider?.isModelDownloaded(it) == true }
    }

    fun deleteModel(modelName: String) {
        modelPathProvider?.deleteModel(modelName)
    }

    /**
     * The whole catalog (sizes from the pins, so the UI shows them before
     * anything is downloaded), plus any downloaded directory outside the
     * catalog so stale models remain visible for deletion.
     */
    suspend fun getAvailableSTTModels(): List<ModelInfo> {
        val catalog = WhisperModelCatalog.MODELS.map { model ->
            ModelInfo(
                slug = model.id,
                sizeInMB = (model.sizeBytes / (1024 * 1024)).toInt(),
                url = WhisperModelCatalog.urlFor(model),
            )
        }
        val stale = modelPathProvider?.getDownloadedModels()
            ?.filter { WhisperModelCatalog.byId(it) == null }
            ?.map { slug ->
                val sizeMB = ((modelPathProvider.getModelSizeBytes(slug)) / (1024 * 1024)).toInt()
                ModelInfo(slug = slug, sizeInMB = sizeMB)
            } ?: emptyList()
        return catalog + stale
    }

    fun getRecommendedSTTMode(): CactusSTTMode {
        return when {
            platform.supportsNPU() || platform.supportsHeavyCPU() -> CactusSTTMode.RemoteFirst
            else -> CactusSTTMode.RemoteOnly
        }
    }

    /**
     * Device-appropriate default pick: small tier as Standard on machines
     * with enough RAM, base tier as Lite below that, English-only variants
     * on English-language devices. The tier threshold lives in the catalog
     * next to the entries it gates.
     */
    fun getRecommendedSTTModel(): RecommendedModel {
        val model = WhisperModelCatalog.recommended(
            totalRamBytes = platform.totalRamBytes(),
            preferEnglishOnly = platform.prefersEnglishModels(),
        )
        return if (platform.totalRamBytes() >= WhisperModelCatalog.STANDARD_TIER_MIN_TOTAL_RAM) {
            RecommendedModel.Standard(model.id)
        } else {
            RecommendedModel.Lite(model.id)
        }
    }
}

sealed class RecommendedModel {
    abstract val modelSlug: String
    data class Lite(override val modelSlug: String) : RecommendedModel()
    data class Standard(override val modelSlug: String) : RecommendedModel()
}

data class ModelInfo(
    val createdAt: kotlin.time.Instant = kotlin.time.Clock.System.now(),
    val slug: String,
    val sizeInMB: Int = 0,
    val url: String = ""
)

expect fun Platform.supportsNPU(): Boolean
expect fun Platform.supportsHeavyCPU(): Boolean

/** Total device RAM; drives the model recommendation tier. */
expect fun Platform.totalRamBytes(): Long

/** True when the device language makes the English-only models the better pick. */
expect fun Platform.prefersEnglishModels(): Boolean
