package coredevices.coreapp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.DialogProperties
import PlatformContext
import com.russhwolf.settings.Settings
import coredevices.coreapp.STT_MODE_BEFORE_UPDATE_KEY
import coredevices.coreapp.STT_UPDATE_NOTIFICATION_ID
import coredevices.coreapp.util.cancelNotifyLocal
import coredevices.ui.M3Dialog
import coredevices.util.CoreConfigHolder
import coredevices.util.models.CactusSTTMode
import coredevices.util.models.ModelManager
import coredevices.util.transcription.CactusModelPathProvider
import coredevices.whisper.isWhisperSupported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * One-time engine-migration dialog. Shows when the startup sweep has
 * stashed a local STT mode (the previous engine's model was removed) and
 * no catalog model is installed yet; downloads the recommended model and
 * restores the stashed mode on success. Unsupported CPUs never see it:
 * a download the engine cannot use would be 150+ MB of pure waste.
 */
@Composable
fun SttModelUpdatePrompt() {
    val modelProvider: CactusModelPathProvider = koinInject()
    val modelManager: ModelManager = koinInject()
    val configHolder: CoreConfigHolder = koinInject()
    val settings: Settings = koinInject()
    val platformContext: PlatformContext = koinInject()
    // Chosen after the speed probe, so a slow phone is offered a tier it
    // can run inside the watch's dictation window.
    var targetModel by remember { mutableStateOf<String?>(null) }
    var needsUpdate by remember { mutableStateOf(false) }

    fun anyModelInstalled(): Boolean =
        modelProvider.getDownloadedModels().any { modelProvider.isModelDownloaded(it) }

    LaunchedEffect(Unit) {
        val required = isWhisperSupported() &&
            settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY) &&
            withContext(Dispatchers.IO) { !anyModelInstalled() }
        if (required) {
            modelManager.ensureSpeedMeasured()
            targetModel = modelManager.getRecommendedSTTModel().modelSlug
        }
        needsUpdate = required
    }

    val download = rememberModelDownloadFlow(
        key = targetModel,
        onInstalled = { slug ->
            val restored = CactusSTTMode.fromId(
                settings.getInt(STT_MODE_BEFORE_UPDATE_KEY, CactusSTTMode.RemoteOnly.id)
            )
            configHolder.update(
                configHolder.config.value.copy(
                    sttConfig = configHolder.config.value.sttConfig.copy(
                        mode = restored,
                        modelName = slug,
                    )
                )
            )
            settings.remove(STT_MODE_BEFORE_UPDATE_KEY)
            needsUpdate = false
        },
    )

    val target = targetModel
    if (!needsUpdate || target == null) return

    fun startDownload() {
        // Upstream dismisses the "model update available" nag once the user
        // starts the download; same behavior via the fork's notification seam.
        cancelNotifyLocal(platformContext, STT_UPDATE_NOTIFICATION_ID)
        download.download(target)
    }

    M3Dialog(
        onDismissRequest = { if (!download.downloading) needsUpdate = false },
        properties = DialogProperties(
            dismissOnBackPress = !download.downloading,
            dismissOnClickOutside = !download.downloading,
        ),
        icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
        title = {
            Text(
                when {
                    download.downloading -> MODEL_DOWNLOADING_TITLE
                    download.failed -> MODEL_DOWNLOAD_FAILED_TITLE
                    else -> "Voice recognition upgraded"
                }
            )
        },
        buttons = {
            if (download.downloading) {
                TextButton(onClick = download::cancel) { Text("Cancel") }
            } else {
                TextButton(onClick = { needsUpdate = false }) { Text("Later") }
                TextButton(onClick = { startDownload() }) { Text(if (download.failed) "Retry" else "Download") }
            }
        },
    ) {
        when {
            download.downloading -> ModelDownloadProgress("Downloading the new voice model. This may take a few minutes.")
            download.failed -> Text(MODEL_DOWNLOAD_FAILED_BODY)
            else -> Text(
                "Offline voice recognition now uses a new engine and needs a fresh model download to keep transcribing offline."
            )
        }
    }
}
