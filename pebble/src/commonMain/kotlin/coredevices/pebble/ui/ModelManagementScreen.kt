package coredevices.pebble.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ui.rememberNotebookTab
import com.russhwolf.settings.Settings
import coredevices.ui.M3Dialog
import coredevices.util.CoreConfigHolder
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.ModelInfo
import coredevices.util.models.ModelManager
import coredevices.util.models.RecommendedModel
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.models.awaitModelDownloadSettled
import coredevices.util.transcription.SpeedScore
import coredevices.util.transcription.WhisperSpeedCalibration
import coredevices.util.transcription.modelRowText
import coredevices.util.transcription.validateServerUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.math.roundToInt

/**
 * Completes a just-scheduled model download: waits for it to settle (see
 * [awaitModelDownloadSettled]) and selects the model only if it actually
 * installed. The install check guards the cancel/failure cases: selecting
 * an absent model strands config pointing at nothing, and the engine then
 * skips init for it, so local dictation dies at the next process restart.
 * Static so the settle/select decision stays under host tests.
 */
internal suspend fun settleDownloadThenSelect(
    status: Flow<ModelDownloadStatus>,
    isInstalled: () -> Boolean,
    refresh: () -> Unit,
    select: () -> Unit,
) {
    awaitModelDownloadSettled(status)
    refresh()
    if (isInstalled()) select()
}

internal const val NO_SPEECH_MODEL_MESSAGE = "No speech model is installed on this phone"

/**
 * Gravel: [NO_SPEECH_MODEL_MESSAGE], naming the mode's other destination for watch dictation in a mode that has
 * one: Rebble, signed in or not, or the server while a valid address is set.
 */
internal fun noSpeechModelMessage(stt: STTConfig): String {
    val elsewhere = when (stt.mode) {
        CactusSTTMode.RebbleFirst, CactusSTTMode.RebbleFallback -> "Rebble"
        CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst -> when {
            stt.serverUrl?.let { validateServerUrl(it) == null } != true -> null
            stt.serverOnMobileData -> "your server"
            else -> "your server on Wi-Fi"
        }
        CactusSTTMode.LocalOnly, CactusSTTMode.RemoteOnly, CactusSTTMode.RebbleOnly, CactusSTTMode.PlatformOnly -> null
    }
    if (elsewhere == null) return NO_SPEECH_MODEL_MESSAGE
    return "$NO_SPEECH_MODEL_MESSAGE. Watch dictation uses $elsewhere"
}

/**
 * Gravel: deletes [slug] and returns what the screen says about it, null for nothing. A deliberate difference from
 * upstream, which switches to Server Only once no speech model is left: the mode stays as chosen. Deleting the
 * selected model selects an installed one, [recommended] ahead of the catalog's order, or clears the selection.
 */
internal fun deleteModelKeepingMode(
    slug: String,
    holder: CoreConfigHolder,
    installed: () -> List<String>,
    delete: (String) -> Unit,
    recommended: () -> String,
): String? {
    val wasInstalled = slug in installed()
    delete(slug)
    val left = installed()
    if (slug in left) return null
    val config = holder.config.value
    val usesPhone = config.sttConfig.mode.usesLocalCactus()
    if (config.sttConfig.modelName == slug) {
        val next = WhisperModelCatalog.selectionAmong(left, recommended())
        holder.update(config.copy(sttConfig = config.sttConfig.copy(modelName = next)))
        if (next != null) return "Now using $next".takeIf { usesPhone }
    }
    return noSpeechModelMessage(config.sttConfig).takeIf { usesPhone && wasInstalled && left.isEmpty() }
}

class ModelManagementScreenViewModel(
    private val modelManager: ModelManager,
    private val coreConfigHolder: CoreConfigHolder,
    private val settings: Settings
) : ViewModel() {
    private val _downloadedModels = MutableStateFlow(modelManager.getDownloadedModelSlugs())
    val downloadedModels = _downloadedModels.asStateFlow()
    val modelDownloadState = modelManager.modelDownloadStatus

    init {
        viewModelScope.launch {
            modelDownloadState.drop(1).collect {
                if (it !is ModelDownloadStatus.Downloading) {
                    refreshDownloadedModels()
                }
            }
        }
    }

    val currentSTTModel = coreConfigHolder.config.map { it.sttConfig.modelName }.onEach {
        Logger.d("Current STT model changed to $it")
    }.stateIn(
        viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.Lazily,
        initialValue = coreConfigHolder.config.value.sttConfig.modelName
    )

    sealed interface AvailableModelsState {
        object Loading : AvailableModelsState
        data class Success(val models: List<ModelInfo>) : AvailableModelsState
        data class Error(val message: String) : AvailableModelsState
    }

    private val _availableSTTModels =
        MutableStateFlow<AvailableModelsState>(AvailableModelsState.Loading)
    val availableSTTModels = _availableSTTModels.asStateFlow()

    /** The phone's speed score; the estimates and the recommendation follow it. */
    val speedScore: StateFlow<SpeedScore?> = modelManager.speedScore

    private val _measuringSpeed = MutableStateFlow(false)
    val measuringSpeed = _measuringSpeed.asStateFlow()

    val recommendedModel: StateFlow<RecommendedModel> = speedScore
        .map { modelManager.getRecommendedSTTModel() }
        .stateIn(
            viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.Lazily,
            initialValue = modelManager.getRecommendedSTTModel(),
        )

    init {
        viewModelScope.launch {
            // The probe runs once per install, the first time the picker
            // opens; the list is (re)built from every score, including the
            // initial one, so the rows always show the current estimates.
            if (speedScore.value == null) {
                _measuringSpeed.value = true
                modelManager.ensureSpeedMeasured()
                _measuringSpeed.value = false
            }
            speedScore.collect { refreshAvailableModels() }
        }
    }

    fun retestSpeed() {
        viewModelScope.launch {
            _measuringSpeed.value = true
            modelManager.measureSpeed()
            _measuringSpeed.value = false
        }
    }

    fun refreshDownloadedModels() {
        _downloadedModels.value = modelManager.getDownloadedModelSlugs()
    }

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessages = _snackbarMessages.asSharedFlow()

    fun deleteModel(slug: String) {
        val message = deleteModelKeepingMode(
            slug = slug,
            holder = coreConfigHolder,
            installed = modelManager::getDownloadedSTTModelSlugs,
            delete = modelManager::deleteModel,
            recommended = { modelManager.getRecommendedSTTModel().modelSlug },
        )
        refreshDownloadedModels()
        message?.let(_snackbarMessages::tryEmit)
    }

    fun downloadSTTModel(info: ModelInfo) {
        viewModelScope.launch {
            modelManager.downloadSTTModel(info, allowMetered = true)
            settleDownloadThenSelect(
                status = modelDownloadState,
                isInstalled = { info.slug in modelManager.getDownloadedSTTModelSlugs() },
                refresh = ::refreshDownloadedModels,
                select = { setCurrentSTTModel(info.slug) },
            )
        }
    }

    fun cancelDownload() {
        modelManager.cancelDownload()
    }

    fun setCurrentSTTModel(slug: String) {
        val newConfig = coreConfigHolder.config.value.copy(
            sttConfig = coreConfigHolder.config.value.sttConfig.copy(
                modelName = slug
            )
        )
        coreConfigHolder.update(newConfig)
    }

    fun refreshAvailableModels() {
        viewModelScope.launch {
            _availableSTTModels.value = try {
                // The whole catalog is user-facing choice by design; the
                // Cactus-era recommended-or-downloaded filter would hide
                // the size/accuracy tiers the multi-model catalog exists
                // to offer.
                AvailableModelsState.Success(modelManager.getAvailableSTTModels())
            } catch (e: Exception) {
                AvailableModelsState.Error(e.message ?: "Unknown error")
            }
        }
    }

}

@Composable
fun ModelDownloadPromptDialog(
    recommended: RecommendedModel,
    downloadSizeInMb: Int,
    onGetRecommended: () -> Unit,
    onDismiss: () -> Unit,
) {
    M3Dialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,

            )
        },
        title = { Text("Download Required") },
        verticalButtons = {
            TextButton(
                onClick = onGetRecommended,
            ) {
                Text(
                    when (recommended) {
                        is RecommendedModel.Standard -> "Download offline model: ${downloadSizeInMb}MB"
                        is RecommendedModel.Lite -> "Download lite model: ${downloadSizeInMb}MB"
                        is RecommendedModel.Minimal -> "Download minimal model: ${downloadSizeInMb}MB"
                    }
                )
            }
            TextButton(
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        }
    ) {
        Text(
            """
                To use offline speech recognition, you need to download a model first.
                Data charges may apply, Wi-Fi is recommended.
            """.trimIndent(),
        )
        when (recommended) {
            is RecommendedModel.Lite -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Your device may struggle with larger models, a reduced accuracy model will be used.")
            }
            is RecommendedModel.Minimal -> {
                Spacer(modifier = Modifier.height(8.dp))
                val window = WhisperSpeedCalibration.WINDOW_SECONDS.roundToInt()
                Text(
                    "This phone is too slow for the larger models to answer a full $window second " +
                        "dictation in time, so the smallest model will be used. Its accuracy " +
                        "is noticeably lower; another model can be chosen later under Manage Models.",
                )
            }
            is RecommendedModel.Standard -> {}
        }
    }
}

/**
 * The picker's header: whether the phone has been speed-tested, what the
 * per-model estimates below mean, and a way to test again (after a
 * system update, say, or with the phone cooler).
 */
@Composable
private fun SpeedTestRow(score: SpeedScore?, measuring: Boolean, notebookSupported: Boolean, onRetest: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                when {
                    measuring -> "Measuring this phone's speed"
                    score == null -> "Phone speed not measured"
                    else -> "Phone speed measured"
                }
            )
        },
        supportingContent = {
            // The numbers are the firmware constants the estimates and the deadline are built on.
            val window = WhisperSpeedCalibration.WINDOW_SECONDS.roundToInt()
            val deadline = WhisperSpeedCalibration.MARGINAL_LIMIT_SECONDS.roundToInt()
            Text(
                when {
                    // Gravel: the probe waits for a Notebook generation to end.
                    measuring && notebookSupported -> "This takes about a second, longer while the phone is processing a Notebook recording."
                    measuring -> "This takes about a second."
                    score == null -> "Each model will show how long a $window second dictation would take once the speed test has run."
                    else -> "Each model shows how long a full $window second dictation would take on this phone " +
                        "with the app in the background. A result later than $deadline seconds is too late for the watch."
                }
            )
        },
        trailingContent = {
            if (measuring) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(26.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRetest) { Text(if (score == null) "Test" else "Re-test") }
            }
        },
    )
}

@Composable
fun ModelManagementScreen(
    navBarNav: NavBarNav,
    topBarParams: TopBarParams,
    downloadStatus: State<ModelDownloadStatus>,
    downloadedModels: State<List<String>>,
    availableSTTModels: State<ModelManagementScreenViewModel.AvailableModelsState>,
    currentSTTModel: State<String?>,
    recommendedSTTModelSlug: String,
    speedScore: State<SpeedScore?>,
    measuringSpeed: State<Boolean>,
    onRetestSpeed: () -> Unit,
    onDownloadSTTModel: (ModelInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onSetCurrentSTTModel: (String) -> Unit,
    notebookSupported: Boolean = false,
) {
    LaunchedEffect(Unit) {
        topBarParams.title("Manage Models")
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    val downloadingModelSlug by remember {
        derivedStateOf {
            when (val status = downloadStatus.value) {
                is ModelDownloadStatus.Downloading -> status.modelSlug
                else -> null
            }
        }
    }
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        Scaffold { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(contentType = "speed") {
                    SpeedTestRow(
                        score = speedScore.value,
                        measuring = measuringSpeed.value,
                        notebookSupported = notebookSupported,
                        onRetest = onRetestSpeed,
                    )
                }
                when (val state = availableSTTModels.value) {
                    ModelManagementScreenViewModel.AvailableModelsState.Loading -> {
                        item(contentType = "loading") {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        }
                    }

                    is ModelManagementScreenViewModel.AvailableModelsState.Error -> {
                        item(contentType = "error") {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    is ModelManagementScreenViewModel.AvailableModelsState.Success -> {
                        val reorderedModels = state.models.sortedByDescending {
                            when {
                                it.slug == recommendedSTTModelSlug -> 3
                                it.slug == currentSTTModel.value -> 2
                                downloadedModels.value.contains(it.slug) -> 1
                                else -> 0
                            }
                        }
                        items(reorderedModels, key = { it.slug }) { model ->
                            val isRecommended = model.slug == recommendedSTTModelSlug
                            ModelListItem(
                                modifier = Modifier.animateItem(),
                                model = model,
                                isRecommended = isRecommended,
                                downloadState = when {
                                    downloadedModels.value.contains(model.slug) -> DownloadState.Downloaded
                                    downloadingModelSlug == model.slug -> {
                                        when (val status = downloadStatus.value) {
                                            is ModelDownloadStatus.Downloading -> {
                                                DownloadState.Downloading(status.progress)
                                            }
                                            else -> DownloadState.Downloading(null)
                                        }
                                    }
                                    else -> DownloadState.NotDownloaded
                                },
                                isSelected = currentSTTModel.value == model.slug,
                                onDownload = if (downloadingModelSlug == null) {
                                    { onDownloadSTTModel(model) }
                                } else {
                                    null
                                },
                                onDownloadCancel = {
                                    onCancelDownload()
                                },
                                onDelete = { onDeleteModel(model.slug) },
                                onSelect = { onSetCurrentSTTModel(model.slug) }
                            )
                        }
                    }
                }
            }
        }
    }
}

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data class Downloading(val progress: Float? = null) : DownloadState
    data object Downloaded : DownloadState
}

@Composable
private fun ModelListItem(
    modifier: Modifier = Modifier,
    model: ModelInfo,
    isRecommended: Boolean,
    downloadState: DownloadState,
    isSelected: Boolean,
    onDownload: (() -> Unit)?,
    onDownloadCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = downloadState == DownloadState.Downloaded) {
                onSelect()
            },
        overlineContent = { if (isRecommended) { Text("Recommended for your device") } },
        headlineContent = { Text(model.slug) },
        supportingContent = { Text(modelRowText(model.sizeInMB, model.estimatedWindowSeconds)) },
        leadingContent = {
            RadioButton(
                selected = isSelected && downloadState == DownloadState.Downloaded,
                onClick = onSelect,
                enabled = downloadState == DownloadState.Downloaded
            )
        },
        trailingContent = {
            when(downloadState) {
                is DownloadState.Downloading -> {
                    Box(contentAlignment = Alignment.Center) {
                        if (downloadState.progress != null) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(26.dp),
                                strokeWidth = 2.dp,
                                progress = { downloadState.progress },
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(26.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        IconButton(
                            onClick = onDownloadCancel,
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = "Download")
                        }
                    }
                }
                DownloadState.NotDownloaded -> {
                    IconButton(
                        onClick = { onDownload?.invoke() },
                        enabled = onDownload != null
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }
                DownloadState.Downloaded -> {
                    IconButton(
                        onClick = onDelete
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun ModelManagementScreen(
    navBarNav: NavBarNav,
    topBarParams: TopBarParams,
) {
    val viewModel = koinViewModel<ModelManagementScreenViewModel>()
    val downloadedModels = viewModel.downloadedModels.collectAsState()
    val availableSTTModels = viewModel.availableSTTModels.collectAsState()
    val currentSTTModel = viewModel.currentSTTModel.collectAsState()
    val downloadStatus = viewModel.modelDownloadState.collectAsState()
    val recommendedSTTModel by viewModel.recommendedModel.collectAsState()
    val speedScore = viewModel.speedScore.collectAsState()
    val measuringSpeed = viewModel.measuringSpeed.collectAsState()

    LaunchedEffect(viewModel, topBarParams) {
        viewModel.snackbarMessages.collect { topBarParams.showSnackbar(it) }
    }

    ModelManagementScreen(
        navBarNav = navBarNav,
        topBarParams = topBarParams,
        downloadStatus = downloadStatus,
        downloadedModels = downloadedModels,
        availableSTTModels = availableSTTModels,
        currentSTTModel = currentSTTModel,
        recommendedSTTModelSlug = recommendedSTTModel.modelSlug,
        speedScore = speedScore,
        measuringSpeed = measuringSpeed,
        onRetestSpeed = viewModel::retestSpeed,
        onDownloadSTTModel = viewModel::downloadSTTModel,
        onCancelDownload = viewModel::cancelDownload,
        onDeleteModel = viewModel::deleteModel,
        onSetCurrentSTTModel = viewModel::setCurrentSTTModel,
        notebookSupported = rememberNotebookTab()?.supported == true,
    )
}

@Preview
@Composable
fun ModelManagementScreenPreview() {
    PreviewWrapper {
        ModelManagementScreen(
            navBarNav = NoOpNavBarNav,
            topBarParams = WrapperTopBarParams,
            downloadStatus = mutableStateOf(ModelDownloadStatus.Downloading(modelSlug = "whisper-base")),
            downloadedModels = mutableStateOf(listOf("whisper-small")),
            availableSTTModels = mutableStateOf(
                ModelManagementScreenViewModel.AvailableModelsState.Success(
                    listOf(
                        ModelInfo(createdAt = Clock.System.now(), slug = "whisper-base", sizeInMB = 74, url = ""),
                        ModelInfo(createdAt = Clock.System.now(), slug = "whisper-small", sizeInMB = 244, url = ""),
                        ModelInfo(createdAt = Clock.System.now(), slug = "whisper-medium-pro", sizeInMB = 769, url = ""),
                    )
                )
            ),
            currentSTTModel = mutableStateOf("whisper-small"),
            recommendedSTTModelSlug = "whisper-medium-pro",
            speedScore = mutableStateOf(SpeedScore(nsPerBlock = 80_000_000L, threads = 2, measuredAtEpochMs = 0L)),
            measuringSpeed = mutableStateOf(false),
            onRetestSpeed = {},
            onDownloadSTTModel = {},
            onCancelDownload = {},
            onDeleteModel = {},
            onSetCurrentSTTModel = {},
        )
    }
}

@Preview
@Composable
fun ModelManagementScreenPromptDialogPreview() {
    PreviewWrapper {
        ModelDownloadPromptDialog(
            recommended = RecommendedModel.Standard("whisper-small-en"),
            onGetRecommended = {},
            onDismiss = {},
            downloadSizeInMb = 100,
        )
    }
}

@Preview
@Composable
fun ModelManagementScreenPromptDialogLitePreview() {
    PreviewWrapper {
        ModelDownloadPromptDialog(
            recommended = RecommendedModel.Lite("whisper-base-en"),
            onGetRecommended = {},
            onDismiss = {},
            downloadSizeInMb = 100,
        )
    }
}

@Preview
@Composable
fun ModelManagementScreenPromptDialogMinimalPreview() {
    PreviewWrapper {
        ModelDownloadPromptDialog(
            recommended = RecommendedModel.Minimal("whisper-tiny-en"),
            onGetRecommended = {},
            onDismiss = {},
            downloadSizeInMb = 74,
        )
    }
}