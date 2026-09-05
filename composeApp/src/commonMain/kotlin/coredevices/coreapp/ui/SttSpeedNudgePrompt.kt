package coredevices.coreapp.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coredevices.ui.M3Dialog
import coredevices.util.CoreConfigHolder
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.ModelManager
import coredevices.util.transcription.CactusModelPathProvider
import coredevices.util.transcription.DictationSpeedTracker
import coredevices.util.transcription.speedNudgeCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Offers a cheaper model when real dictations on the selected one decode
 * too slowly for the watch's window (see DictationSpeedPolicy). Shown
 * once per model: keeping the current model records the decision, and a
 * later, slower tier raises its own prompt. Switching selects the target
 * at once when it is installed and downloads it first otherwise.
 */
@Composable
fun SttSpeedNudgePrompt() {
    val tracker: DictationSpeedTracker = koinInject()
    val modelManager: ModelManager = koinInject()
    val configHolder: CoreConfigHolder = koinInject()
    val modelProvider: CactusModelPathProvider = koinInject()
    val scope = rememberCoroutineScope()
    val nudge by tracker.nudge.collectAsState()
    val current = nudge ?: return
    val copy = remember(current) { speedNudgeCopy(current) }
    // Keyed on the offer, not the nudge value: the tracker holds the nudge
    // during a switch, and a re-scored copy for the same pair must not
    // reset a download in progress.
    val pair = current.currentModelId to current.targetModelId
    var downloading by remember(pair) { mutableStateOf(false) }
    var failed by remember(pair) { mutableStateOf(false) }
    val downloadStatus by modelManager.modelDownloadStatus.collectAsState()

    fun select(modelId: String) {
        val config = configHolder.config.value
        configHolder.update(config.copy(sttConfig = config.sttConfig.copy(modelName = modelId)))
        tracker.clear()
    }

    LaunchedEffect(downloadStatus) {
        if (!downloading) return@LaunchedEffect
        when (downloadStatus) {
            is ModelDownloadStatus.Idle -> {
                val installed = withContext(Dispatchers.IO) {
                    modelProvider.isModelDownloaded(current.targetModelId)
                }
                if (installed) {
                    select(current.targetModelId)
                } else {
                    failed = true
                    tracker.endSwitch()
                }
                downloading = false
            }
            is ModelDownloadStatus.Failed -> {
                failed = true
                downloading = false
                tracker.endSwitch()
            }
            else -> {}
        }
    }

    fun switchModel() {
        failed = false
        scope.launch {
            val installed = withContext(Dispatchers.IO) {
                modelProvider.isModelDownloaded(current.targetModelId)
            }
            if (installed) {
                select(current.targetModelId)
                return@launch
            }
            val info = modelManager.getAvailableSTTModels().firstOrNull { it.slug == current.targetModelId }
            if (info != null && modelManager.downloadSTTModel(info, allowMetered = true)) {
                tracker.beginSwitch()
                downloading = true
            } else {
                failed = true
            }
        }
    }

    M3Dialog(
        onDismissRequest = { if (!downloading) tracker.clear() },
        properties = DialogProperties(
            dismissOnBackPress = !downloading,
            dismissOnClickOutside = !downloading,
        ),
        icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
        // The body is several sentences; in landscape it must scroll so the
        // buttons stay on screen.
        scrollableContent = true,
        title = {
            Text(
                when {
                    downloading -> "Downloading voice model"
                    failed -> "Download failed"
                    else -> copy.title
                }
            )
        },
        verticalButtons = {
            if (downloading) {
                TextButton(onClick = {
                    modelManager.cancelDownload()
                    downloading = false
                    tracker.endSwitch()
                }) { Text("Cancel") }
            } else {
                TextButton(onClick = { switchModel() }) { Text(if (failed) "Retry" else copy.switchLabel) }
                TextButton(onClick = { tracker.decline(current.currentModelId) }) { Text(copy.keepLabel) }
            }
        },
    ) {
        when {
            downloading -> {
                Text("Downloading the smaller voice model. This may take a few minutes.")
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            failed -> Text("The download didn't finish. Check your connection and try again.")
            else -> Text(copy.body)
        }
    }
}
