package coredevices.coreapp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogProperties
import coredevices.ui.M3Dialog
import coredevices.util.CoreConfigHolder
import coredevices.util.transcription.DictationSpeedTracker
import coredevices.util.transcription.speedNudgeCopy
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
    val configHolder: CoreConfigHolder = koinInject()
    val nudge by tracker.nudge.collectAsState()
    val current = nudge ?: return
    val copy = remember(current) { speedNudgeCopy(current) }
    // Keyed on the offer, not the nudge value: the tracker holds the nudge
    // during a switch, and a re-scored copy for the same pair must not
    // reset a download in progress.
    val pair = current.currentModelId to current.targetModelId
    val download = rememberModelDownloadFlow(
        key = pair,
        onStarted = tracker::beginSwitch,
        onInstalled = { modelId ->
            val config = configHolder.config.value
            configHolder.update(config.copy(sttConfig = config.sttConfig.copy(modelName = modelId)))
            tracker.clear()
        },
        onEnded = tracker::endSwitch,
    )

    M3Dialog(
        onDismissRequest = { if (!download.downloading) tracker.clear() },
        properties = DialogProperties(
            dismissOnBackPress = !download.downloading,
            dismissOnClickOutside = !download.downloading,
        ),
        icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
        // The body is several sentences; in landscape it must scroll so the
        // buttons stay on screen.
        scrollableContent = true,
        title = {
            Text(
                when {
                    download.downloading -> MODEL_DOWNLOADING_TITLE
                    download.failed -> MODEL_DOWNLOAD_FAILED_TITLE
                    else -> copy.title
                }
            )
        },
        verticalButtons = {
            if (download.downloading) {
                TextButton(onClick = download::cancel) { Text("Cancel") }
            } else {
                TextButton(onClick = { download.download(current.targetModelId) }) {
                    Text(if (download.failed) "Retry" else copy.switchLabel)
                }
                TextButton(onClick = { tracker.decline(current.currentModelId) }) { Text(copy.keepLabel) }
            }
        },
    ) {
        when {
            download.downloading -> ModelDownloadProgress("Downloading the smaller voice model. This may take a few minutes.")
            download.failed -> Text(MODEL_DOWNLOAD_FAILED_BODY)
            else -> Text(copy.body)
        }
    }
}
