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
    fun getDownloadedSTTModelSlugs(): List<String> =
        downloadedSTTModelSlugs(modelPathProvider)

    fun deleteModel(modelName: String) {
        modelPathProvider?.deleteModel(modelName)
    }

    /**
     * The whole catalog (sizes from the pins, so the UI shows them before
     * anything is downloaded), plus any downloaded directory outside the
     * catalog so stale models remain visible for deletion.
     */
    suspend fun getAvailableSTTModels(): List<ModelInfo> =
        availableSTTModels(modelPathProvider)

    fun getRecommendedSTTMode(): CactusSTTMode {
        return when {
            platform.supportsNPU() || platform.supportsHeavyCPU() -> CactusSTTMode.RemoteFirst
            else -> CactusSTTMode.RemoteOnly
        }
    }

    /**
     * Device-appropriate default pick: small tier as Standard on machines
     * with enough RAM, base tier as Lite below that, English-only variants
     * on English-language devices. Both the model and the Lite/Standard
     * label come from a single [WhisperModelCatalog.recommended] decision
     * over one RAM read, so the label always matches the chosen model.
     */
    fun getRecommendedSTTModel(): RecommendedModel {
        val recommendation = WhisperModelCatalog.recommended(
            totalRamBytes = platform.totalRamBytes(),
            preferEnglishOnly = platform.prefersEnglishModels(),
        )
        return if (recommendation.standardTier) {
            RecommendedModel.Standard(recommendation.model.id)
        } else {
            RecommendedModel.Lite(recommendation.model.id)
        }
    }

    companion object {
        /**
         * Catalog models actually installed and usable by the engine.
         * Static and provider-parameterized so the install filter stays
         * host-testable with a fake provider (the instance method delegates
         * here).
         */
        internal fun downloadedSTTModelSlugs(provider: CactusModelPathProvider?): List<String> =
            provider?.getDownloadedModels()?.filter { provider.isModelDownloaded(it) } ?: emptyList()

        /**
         * The whole catalog plus any downloaded directory outside the
         * catalog (stale previous-engine leftovers), so those stay visible
         * for deletion. Static and provider-parameterized for host tests.
         */
        internal fun availableSTTModels(provider: CactusModelPathProvider?): List<ModelInfo> {
            val catalog = WhisperModelCatalog.MODELS.map { model ->
                ModelInfo(
                    slug = model.id,
                    sizeInMB = (model.sizeBytes / (1024 * 1024)).toInt(),
                    url = WhisperModelCatalog.urlFor(model),
                )
            }
            val stale = provider?.getDownloadedModels()
                ?.filter { WhisperModelCatalog.byId(it) == null && !WhisperModelCatalog.isVadModelId(it) }
                ?.map { slug ->
                    val sizeMB = (provider.getModelSizeBytes(slug) / (1024 * 1024)).toInt()
                    ModelInfo(slug = slug, sizeInMB = sizeMB)
                } ?: emptyList()
            return catalog + stale
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
