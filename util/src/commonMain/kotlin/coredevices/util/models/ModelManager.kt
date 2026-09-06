package coredevices.util.models

import coredevices.util.Platform
import coredevices.util.transcription.CactusModelPathProvider
import coredevices.util.transcription.DeviceSpeedEstimator
import coredevices.util.transcription.SpeedScore
import coredevices.util.transcription.WhisperSpeedCalibration
import coredevices.util.transcription.WindowFit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing surface over the whisper model catalog and the provider that
 * installs from it. The Cactus-era language-model surface is gone with the
 * engine: only STT models exist in this fork.
 */
class ModelManager(
    private val platform: Platform,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPathProvider: CactusModelPathProvider? = null,
    private val speedEstimator: DeviceSpeedEstimator? = null,
) {
    val modelDownloadStatus = modelDownloadManager.downloadStatus

    /** The phone's speed score; null until the probe has run on this install. */
    val speedScore: StateFlow<SpeedScore?> = speedEstimator?.score ?: MutableStateFlow(null)

    /** Runs the speed probe now (about a second of CPU) and caches the result. */
    suspend fun measureSpeed(): SpeedScore? = speedEstimator?.measure()

    /** The cached speed score, measuring once when there is none. */
    suspend fun ensureSpeedMeasured(): SpeedScore? = speedEstimator?.cachedOrMeasure()

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
     * catalog so stale models remain visible for deletion. Each catalog
     * entry carries the phone's estimated full-window decode when a speed
     * score exists.
     */
    suspend fun getAvailableSTTModels(): List<ModelInfo> =
        availableSTTModels(modelPathProvider, speedScore.value)

    fun getRecommendedSTTMode(): CactusSTTMode {
        return when {
            platform.supportsNPU() || platform.supportsHeavyCPU() -> CactusSTTMode.RemoteFirst
            else -> CactusSTTMode.RemoteOnly
        }
    }

    /**
     * Device-appropriate default pick: the RAM tier from
     * [WhisperModelCatalog.recommended], stepped down while the phone's
     * speed score says a full dictation window would exceed the watch's
     * limit on it (see [recommendedModel]). Without a score the RAM tier
     * stands, so callers that can afford the probe call
     * [ensureSpeedMeasured] first.
     */
    fun getRecommendedSTTModel(): RecommendedModel = recommendedModel(
        totalRamBytes = platform.totalRamBytes(),
        preferEnglishOnly = platform.prefersEnglishModels(),
        score = speedScore.value,
    )

    companion object {
        private const val MIB = 1024L * 1024L

        /** Whole MiB, rounded to nearest: the one size rule for every row the picker and the download job see. */
        private fun mebibytes(bytes: Long): Int = ((bytes + MIB / 2) / MIB).toInt()

        /**
         * A catalog entry as the download job and the picker carry it. The
         * size is rounded to the nearest MiB for the download job's traffic
         * estimate.
         */
        internal fun modelInfoFor(model: WhisperModel, estimatedWindowSeconds: Double? = null): ModelInfo = ModelInfo(
            slug = model.id,
            sizeInMB = mebibytes(model.sizeBytes),
            url = WhisperModelCatalog.urlFor(model),
            estimatedWindowSeconds = estimatedWindowSeconds,
        )

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
        internal fun availableSTTModels(
            provider: CactusModelPathProvider?,
            score: SpeedScore? = null,
        ): List<ModelInfo> {
            val catalog = WhisperModelCatalog.MODELS.map { model ->
                modelInfoFor(model, WhisperSpeedCalibration.estimateWindowSeconds(model.id, score))
            }
            val stale = provider?.getDownloadedModels()
                ?.filter { WhisperModelCatalog.byId(it) == null }
                ?.map { slug -> ModelInfo(slug = slug, sizeInMB = mebibytes(provider.getModelSizeBytes(slug))) }
                ?: emptyList()
            return catalog + stale
        }

        /**
         * The RAM tier, stepped down one tier at a time while the estimate
         * for it on this phone exceeds the watch's window, to the tiny
         * floor at most. The label follows the tier the walk ends on, so
         * it always matches the model. Static so the walk stays under host
         * tests.
         */
        internal fun recommendedModel(
            totalRamBytes: Long,
            preferEnglishOnly: Boolean,
            score: SpeedScore?,
        ): RecommendedModel {
            var model = WhisperModelCatalog.recommended(totalRamBytes, preferEnglishOnly).model
            while (WhisperSpeedCalibration.fitOf(model.id, score) == WindowFit.Exceeds) {
                model = WhisperModelCatalog.stepDown(model) ?: break
            }
            return when (model.tier) {
                WhisperTier.Small -> RecommendedModel.Standard(model.id)
                WhisperTier.Base -> RecommendedModel.Lite(model.id)
                WhisperTier.Tiny -> RecommendedModel.Minimal(model.id)
            }
        }
    }
}

/**
 * The default model for a device with the tier it landed on: Standard is
 * the small tier, Lite the base tier, Minimal the tiny tier, which only
 * the speed step-down ever reaches.
 */
sealed class RecommendedModel {
    abstract val modelSlug: String
    data class Lite(override val modelSlug: String) : RecommendedModel()
    data class Standard(override val modelSlug: String) : RecommendedModel()
    data class Minimal(override val modelSlug: String) : RecommendedModel()
}

/**
 * @param estimatedWindowSeconds the phone's estimated decode time for a
 *   full 15 s dictation on this model, null without a speed score or for
 *   non-catalog directories.
 */
data class ModelInfo(
    val createdAt: kotlin.time.Instant = kotlin.time.Clock.System.now(),
    val slug: String,
    val sizeInMB: Int = 0,
    val url: String = "",
    val estimatedWindowSeconds: Double? = null,
)

expect fun Platform.supportsNPU(): Boolean
expect fun Platform.supportsHeavyCPU(): Boolean

/** Total device RAM; drives the model recommendation tier. */
expect fun Platform.totalRamBytes(): Long

/** True when the device language makes the English-only models the better pick. */
expect fun Platform.prefersEnglishModels(): Boolean
