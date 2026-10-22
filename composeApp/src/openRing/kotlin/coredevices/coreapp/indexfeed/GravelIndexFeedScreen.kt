package coredevices.coreapp.indexfeed

import PlatformUiContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coredevices.pebble.ui.TopBarParams
import coredevices.util.rememberUiContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun GravelIndexFeedScreen(topBarParams: TopBarParams) {
    val viewModel = koinInject<IndexFeedViewModel>()
    val sharer = koinInject<IndexArchiveSharer>()
    val recordings by viewModel.recordings.collectAsState()
    val playing by viewModel.playingPath.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val uiContext = rememberUiContext()
    var menuOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.ensureLoaded()
        topBarParams.searchAvailable(null)
        topBarParams.setHidden(false)
        topBarParams.title("Index")
        topBarParams.actions {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !exporting) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Export recordings") },
                        enabled = !exporting,
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                runExport(exporting = { exporting = it }, topBarParams, uiContext, viewModel, sharer, share = false)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share recordings") },
                        enabled = !exporting,
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                runExport(exporting = { exporting = it }, topBarParams, uiContext, viewModel, sharer, share = true)
                            }
                        },
                    )
                }
            }
        }
        launch {
            topBarParams.scrollToTop.collect {
                if (viewModel.recordings.value.isNotEmpty()) listState.animateScrollToItem(0)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        if (exporting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (recordings.isEmpty()) {
            Text(
                "Recordings from your Index 01 show up here after a sync.",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(recordings, key = { it.id }) { recording ->
                    RecordingRow(
                        recording = recording,
                        playing = playing == recording.audioPath,
                        onPlay = { viewModel.togglePlayback(recording.audioPath) },
                        onRetry = { viewModel.retry(recording.id) },
                        onDelete = { scope.launch { viewModel.delete(recording.id) } },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private suspend fun runExport(
    exporting: (Boolean) -> Unit,
    topBarParams: TopBarParams,
    uiContext: PlatformUiContext?,
    viewModel: IndexFeedViewModel,
    sharer: IndexArchiveSharer,
    share: Boolean,
) {
    if (uiContext == null) {
        topBarParams.showSnackbar("Couldn't open a save dialog")
        return
    }
    exporting(true)
    try {
        val zip = viewModel.exportArchive()
        val empty = viewModel.recordings.value.isEmpty()
        if (share) {
            sharer.share(uiContext, INDEX_EXPORT_NAME, zip)
        } else {
            when (sharer.saveWithPicker(uiContext, INDEX_EXPORT_NAME, zip)) {
                ArchiveSaveResult.Saved -> topBarParams.showSnackbar(
                    if (empty) "No recordings yet. The archive has the manifest only."
                    else "Saved recordings",
                )
                ArchiveSaveResult.Cancelled -> topBarParams.showSnackbar("Export cancelled")
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        topBarParams.showSnackbar("Couldn't export recordings")
    } finally {
        exporting(false)
    }
}

@Composable
private fun RecordingRow(
    recording: IndexRecording,
    playing: Boolean,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatRecordedAt(recording.recordedAtEpochSeconds), style = MaterialTheme.typography.titleMedium)
            Text(formatDuration(recording.durationMillis), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recording.buttonSequence?.let { sequence ->
            Text(
                "Button: $sequence",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            transcriptLabel(recording),
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            TextButton(onClick = onPlay) { Text(if (playing) "Stop" else "Play") }
            if (canRetryTranscription(recording)) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
