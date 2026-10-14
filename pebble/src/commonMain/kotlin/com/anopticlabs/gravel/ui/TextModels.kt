package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.anopticlabs.gravel.models.LlmModel
import com.anopticlabs.gravel.models.LlmModelCatalog
import com.anopticlabs.gravel.models.LlmSpeedCalibration
import com.anopticlabs.gravel.models.exceedsDeviceRam
import com.anopticlabs.gravel.models.pickTextModel
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.transcription.SpeedScore
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.getKoin
import kotlin.math.roundToInt

/**
 * The Notebook's text models as Settings > AI > LLM sees them. Only platforms with the Notebook bind it, so
 * common code looks it up with `getOrNull` and treats a missing binding as no LLM entry.
 */
interface TextModels {
    /** False where the phone cannot run the Notebook; the LLM entry is hidden then. */
    val supported: Boolean

    /** What is on the phone now, read again by [refresh] and after every action. */
    val facts: StateFlow<TextModelFacts>

    fun refresh()

    fun download(modelId: String)

    fun cancelDownload()

    /** Makes [modelId] the Notebook's choice. */
    fun choose(modelId: String)

    /** Deletes [modelId]'s installed file, or else the part an interrupted download kept. */
    fun delete(modelId: String)
}

data class TextModelFacts(
    /** False until the phone was first read; nothing below is known before. */
    val read: Boolean = false,
    /** False only once the phone is known unable to run the text engine. */
    val engineSupported: Boolean = true,
    val installed: Set<String> = emptySet(),
    /** Bytes that interrupted downloads kept, by model. */
    val partialBytes: Map<String, Long> = emptyMap(),
    val chosen: String? = null,
    /** The model the Notebook loads next. */
    val inUse: String? = null,
    val inUseLoadFailed: Boolean = false,
    /** The text lane's download status. */
    val download: ModelDownloadStatus = ModelDownloadStatus.Idle,
)

sealed interface TextModelState {
    data object NotDownloaded : TextModelState
    data class Partial(val bytes: Long) : TextModelState
    data object Downloading : TextModelState
    data object Installed : TextModelState
}

data class TextModelRow(
    val model: LlmModel,
    val state: TextModelState,
    val recommended: Boolean,
    val inUse: Boolean,
    val loadFailed: Boolean,
    /** The model's memory floor is more than 10% above the phone's memory ([exceedsDeviceRam]): a download asks first. */
    val tooHeavy: Boolean,
    /** Its estimate is over the note budget, so it is recommended only as the smallest. */
    val slow: Boolean,
    val canDownload: Boolean,
    /** Why the model's last download failed, while the text lane still says so. */
    val failure: String?,
    val detail: String,
)

/** One row per catalog model; downloads wait while the text lane runs one, and stay off where the engine cannot run. */
fun textModelRows(
    facts: TextModelFacts,
    recommendedId: String?,
    score: SpeedScore?,
    totalRamBytes: Long,
    models: List<LlmModel> = LlmModelCatalog.MODELS,
): List<TextModelRow> {
    val downloading = (facts.download as? ModelDownloadStatus.Downloading)?.modelSlug
    val failed = facts.download as? ModelDownloadStatus.Failed
    return models.map { model ->
        val partial = facts.partialBytes[model.id] ?: 0L
        val state = when {
            model.id in facts.installed -> TextModelState.Installed
            model.id == downloading -> TextModelState.Downloading
            partial > 0 -> TextModelState.Partial(partial)
            else -> TextModelState.NotDownloaded
        }
        val inUse = model.id == facts.inUse && state == TextModelState.Installed
        val estimate = LlmSpeedCalibration.estimateNoteSeconds(model.id, score)
        TextModelRow(
            model = model,
            state = state,
            recommended = model.id == recommendedId,
            inUse = inUse,
            loadFailed = inUse && facts.inUseLoadFailed,
            tooHeavy = exceedsDeviceRam(model.minRamBytes, totalRamBytes),
            slow = (estimate ?: 0.0) > LlmSpeedCalibration.NOTE_BUDGET_SECONDS,
            canDownload = facts.engineSupported && downloading == null &&
                (state == TextModelState.NotDownloaded || state is TextModelState.Partial),
            failure = failed?.takeIf { it.modelSlug == model.id && state != TextModelState.Installed }?.errorMessage,
            detail = textModelDetail(model, estimate),
        )
    }
}

/** The row's size and, with a speed score, the text model's part of a note, the estimate the recommendation uses. */
fun textModelDetail(model: LlmModel, estimateSeconds: Double?): String {
    val size = "${megabytes(model.sizeBytes)} MB"
    if (estimateSeconds == null) return size
    val rounded = estimateSeconds.roundToInt()
    val about = if (rounded < 1) "under 1 s" else "about $rounded s"
    val estimate = "$about per one-minute recording, after transcription"
    return if (estimateSeconds > LlmSpeedCalibration.NOTE_BUDGET_SECONDS) "$size, $estimate, slow on this phone" else "$size, $estimate"
}

fun partialDownloadText(model: LlmModel, bytes: Long): String =
    "Paused at ${partMegabytes(bytes)} of ${megabytes(model.sizeBytes)} MB"

/** The delete confirmation, naming what the Notebook uses afterwards when [row] is the model in use. */
fun textModelDeleteMessage(
    row: TextModelRow,
    facts: TextModelFacts,
    recommended: LlmModel?,
    models: List<LlmModel> = LlmModelCatalog.MODELS,
): String {
    val name = row.model.displayName
    val state = row.state
    if (state is TextModelState.Partial) {
        val frees = if (megabytes(state.bytes) == 0L) "less than 1 MB" else "about ${megabytes(state.bytes)} MB"
        return "Delete the downloaded part of $name? This frees $frees, and a new download starts from the beginning."
    }
    val frees = "This frees about ${megabytes(row.model.sizeBytes)} MB"
    if (!facts.engineSupported) return "Delete $name? $frees."
    if (!row.inUse) return "Delete $name? $frees, and you'd have to download it again to use it."
    val remaining = models.filter { it.id in facts.installed && it.id != row.model.id }
    val next = pickTextModel(remaining, facts.chosen.takeUnless { it == row.model.id }, recommended)
    return if (next != null) {
        "Delete $name? $frees. The Notebook will use ${next.displayName} instead."
    } else {
        "Delete $name? $frees. Notebook recordings will wait until you download a text model."
    }
}

/** The LLM entry's line in Settings > AI. */
fun textModelsSummary(facts: TextModelFacts): String {
    val downloading = (facts.download as? ModelDownloadStatus.Downloading)?.modelSlug
    return when {
        !facts.engineSupported -> "This phone can't run a text model"
        downloading != null -> "Downloading ${LlmModelCatalog.byId(downloading)?.displayName ?: downloading}"
        facts.inUse != null -> (LlmModelCatalog.byId(facts.inUse)?.displayName ?: facts.inUse) +
            if (facts.inUseLoadFailed) " could not be loaded" else ""
        else -> "No text model installed"
    }
}

// Whole MiB, rounded to nearest, as the speech model rows count them.
private fun megabytes(bytes: Long): Long = (bytes + MIB / 2) / MIB

private fun partMegabytes(bytes: Long): String = megabytes(bytes).takeIf { it > 0 }?.toString() ?: "less than 1"

private const val MIB = 1024L * 1024L

@Composable
fun rememberTextModels(): TextModels? {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<TextModels>() }
}
