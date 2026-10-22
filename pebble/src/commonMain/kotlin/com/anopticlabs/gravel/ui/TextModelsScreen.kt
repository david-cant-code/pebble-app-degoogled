package com.anopticlabs.gravel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anopticlabs.gravel.models.LlmModel
import com.anopticlabs.gravel.models.LlmModelCatalog
import coredevices.pebble.ui.TopBarParams
import coredevices.util.models.ModelManager
import coredevices.util.transcription.SpeedScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel

/** What the phone's memory and speed make of the catalog. */
data class TextModelFit(val recommended: LlmModel?, val totalRamBytes: Long)

class TextModelsViewModel(
    private val textModels: TextModels,
    private val modelManager: ModelManager,
) : ViewModel() {
    val facts: StateFlow<TextModelFacts> = textModels.facts

    val speedScore: StateFlow<SpeedScore?> = modelManager.speedScore

    private val _measuring = MutableStateFlow(false)
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

    // Reads /proc/meminfo and loads the CPU probe, so off the main thread.
    val fit: StateFlow<TextModelFit?> = speedScore
        .map { withContext(Dispatchers.IO) { TextModelFit(modelManager.getRecommendedTextModel(), modelManager.totalRamBytes()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // No rows before the phone and its memory are read: a tap then would act on what is not known yet.
    val rows: StateFlow<List<TextModelRow>> = combine(facts, speedScore, fit) { facts, score, fit ->
        if (fit == null || !facts.read) emptyList() else textModelRows(facts, fit.recommended?.id, score, fit.totalRamBytes)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** For each time the page opens: reads the phone again, and measures its speed when no score is kept. */
    fun start() {
        textModels.refresh()
        if (speedScore.value == null) measure { modelManager.ensureSpeedMeasured() }
    }

    fun retestSpeed() = measure { modelManager.measureSpeed() }

    private fun measure(block: suspend () -> Unit) {
        viewModelScope.launch {
            _measuring.value = true
            try {
                block()
            } finally {
                _measuring.value = false
            }
        }
    }

    fun download(modelId: String) = textModels.download(modelId)

    fun cancelDownload() = textModels.cancelDownload()

    fun choose(modelId: String) = textModels.choose(modelId)

    fun delete(modelId: String) = textModels.delete(modelId)
}

@Composable
fun TextModelsScreen(topBarParams: TopBarParams) {
    val viewModel = koinViewModel<TextModelsViewModel>()
    LaunchedEffect(Unit) {
        topBarParams.title("LLM")
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    LaunchedEffect(viewModel) { viewModel.start() }
    val facts by viewModel.facts.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val fit by viewModel.fit.collectAsState()
    val score by viewModel.speedScore.collectAsState()
    val measuring by viewModel.measuring.collectAsState()
    TextModelsContent(
        facts = facts,
        rows = rows,
        recommended = fit?.recommended,
        totalRamBytes = fit?.totalRamBytes ?: 0L,
        score = score,
        measuring = measuring,
        onRetestSpeed = viewModel::retestSpeed,
        onDownload = viewModel::download,
        onCancelDownload = viewModel::cancelDownload,
        onChoose = viewModel::choose,
        onDelete = viewModel::delete,
    )
}

@Composable
fun TextModelsContent(
    facts: TextModelFacts,
    rows: List<TextModelRow>,
    recommended: LlmModel?,
    totalRamBytes: Long,
    score: SpeedScore?,
    measuring: Boolean,
    onRetestSpeed: () -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onChoose: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var heavyDownload by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "intro") {
                Text(
                    "The Notebook uses a text model on this phone to title and tag notes and to word reminders.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (!facts.engineSupported) {
                item(key = "unsupported") {
                    Text(
                        "This phone's processor can't run these models, so the Notebook can't turn recordings into notes " +
                            "or reminders here. Downloaded models can still be deleted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item(key = "speed") { TextSpeedRow(score, measuring, onRetestSpeed) }
            items(rows, key = { it.model.id }) { row ->
                TextModelListItem(
                    row = row,
                    totalRamBytes = totalRamBytes,
                    onDownload = { if (row.tooHeavy) heavyDownload = row.model.id else onDownload(row.model.id) },
                    onCancelDownload = onCancelDownload,
                    onChoose = { onChoose(row.model.id) },
                    onDelete = { pendingDelete = row.model.id },
                )
            }
        }
    }
    heavyDownload?.takeIf { rows.isNotEmpty() }?.let(LlmModelCatalog::byId)?.let { model ->
        AlertDialog(
            onDismissRequest = { heavyDownload = null },
            title = { Text("May run slowly") },
            text = { Text(heavyWarning(model, totalRamBytes)) },
            confirmButton = {
                TextButton(onClick = {
                    heavyDownload = null
                    onDownload(model.id)
                }) { Text("Download anyway") }
            },
            dismissButton = { TextButton(onClick = { heavyDownload = null }) { Text("Cancel") } },
        )
    }
    val deletable = rows.firstOrNull {
        it.model.id == pendingDelete && (it.state == TextModelState.Installed || it.state is TextModelState.Partial)
    }
    // A row that stops being deletable while asked (its download restarted, say) closes the question for good;
    // rows not read yet (after the process is recreated) do not.
    LaunchedEffect(pendingDelete, deletable, rows.isEmpty()) {
        if (pendingDelete != null && deletable == null && rows.isNotEmpty()) pendingDelete = null
    }
    deletable?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (row.state is TextModelState.Partial) "Delete downloaded part?" else "Delete model?") },
            text = { Text(textModelDeleteMessage(row, facts, recommended)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(row.model.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TextSpeedRow(score: SpeedScore?, measuring: Boolean, onRetest: () -> Unit) {
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
            Text(
                when {
                    measuring -> "This takes about a second, longer while the phone is processing a Notebook recording."
                    score == null -> "Each model will show about how long it takes, after transcription, to turn a one-minute " +
                        "recording into a note once the speed test has run."
                    else -> "Each model shows about how long it takes, after transcription, to turn a one-minute recording " +
                        "into a note on this phone with the app in the background."
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

/** One model's row, shared with the Notebook setup guide, which passes no [onDelete] and so shows no delete. */
@Composable
internal fun TextModelListItem(
    row: TextModelRow,
    totalRamBytes: Long,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onChoose: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val installed = row.state == TextModelState.Installed
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(enabled = installed, onClick = onChoose),
        // Where no model meets the phone's memory and speed, the recommendation falls back to the smallest.
        overlineContent = if (row.recommended) {
            { Text(if (row.tooHeavy || row.slow) "Smallest model" else "Recommended for your device") }
        } else null,
        headlineContent = { Text(row.model.displayName) },
        supportingContent = {
            Column {
                Text(row.detail)
                val state = row.state
                when {
                    state is TextModelState.Partial -> Text(partialDownloadText(row.model, state.bytes))
                    state == TextModelState.Downloading -> Text("Downloading in the background, or waiting for a network")
                }
                if (row.tooHeavy && !installed) {
                    Text(heavyWarning(row.model, totalRamBytes), color = MaterialTheme.colorScheme.error)
                }
                if (row.loadFailed) {
                    Text(
                        "Could not be loaded. Choose another model, or delete this one and download it again.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                row.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        leadingContent = {
            RadioButton(selected = row.inUse, onClick = onChoose, enabled = installed)
        },
        trailingContent = {
            when (row.state) {
                TextModelState.Downloading -> Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(26.dp), strokeWidth = 2.dp)
                    IconButton(onClick = onCancelDownload) {
                        Icon(Icons.Default.Cancel, contentDescription = "Cancel download")
                    }
                }
                TextModelState.Installed -> onDelete?.let { DeleteButton(it) }
                is TextModelState.Partial -> Row {
                    DownloadButton(row.canDownload, onDownload)
                    onDelete?.let { DeleteButton(it) }
                }
                TextModelState.NotDownloaded -> DownloadButton(row.canDownload, onDownload)
            }
        },
    )
}

@Composable
private fun DownloadButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.Download, contentDescription = "Download")
    }
}

@Composable
private fun DeleteButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
    }
}

internal fun heavyWarning(model: LlmModel, totalRamBytes: Long): String =
    "This model is meant for phones with at least ${gigabytes(model.minRamBytes)} GB of memory and this one has " +
        "${gigabytes(totalRamBytes)} GB, so it may run slowly or fail to load."

// Tenths of a GiB: a floor exceedsDeviceRam flags is over 10% above the phone's memory, so the two figures differ.
internal fun gigabytes(bytes: Long): String {
    val tenths = (bytes * 10 + GIB / 2) / GIB
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

private const val GIB = 1024L * 1024L * 1024L
