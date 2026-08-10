package coredevices.coreapp.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
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
import PlatformContext
import com.russhwolf.settings.Settings
import coredevices.coreapp.STT_MODE_BEFORE_UPDATE_KEY
import coredevices.coreapp.STT_UPDATE_NOTIFICATION_ID
import coredevices.coreapp.util.cancelNotifyLocal
import coredevices.ui.M3Dialog
import coredevices.util.CoreConfigHolder
import coredevices.util.models.CactusSTTMode
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.ModelManager
import coredevices.util.transcription.CactusModelPathProvider
import coredevices.whisper.isWhisperSupported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val targetModel = remember { modelManager.getRecommendedSTTModel().modelSlug }

    var needsUpdate by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val downloadStatus by modelManager.modelDownloadStatus.collectAsState()

    fun anyModelInstalled(): Boolean =
        modelProvider.getDownloadedModels().any { modelProvider.isModelDownloaded(it) }

    LaunchedEffect(Unit) {
        needsUpdate = isWhisperSupported() &&
            settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY) &&
            withContext(Dispatchers.IO) { !anyModelInstalled() }
    }

    LaunchedEffect(downloadStatus) {
        if (!downloading) return@LaunchedEffect
        when (downloadStatus) {
            is ModelDownloadStatus.Idle -> {
                val installed = withContext(Dispatchers.IO) {
                    modelProvider.isModelDownloaded(targetModel)
                }
                if (!installed) {
                    failed = true
                } else {
                    val restored = CactusSTTMode.fromId(
                        settings.getInt(STT_MODE_BEFORE_UPDATE_KEY, CactusSTTMode.RemoteOnly.id)
                    )
                    configHolder.update(
                        configHolder.config.value.copy(
                            sttConfig = configHolder.config.value.sttConfig.copy(
                                mode = restored,
                                modelName = targetModel,
                            )
                        )
                    )
                    settings.remove(STT_MODE_BEFORE_UPDATE_KEY)
                    needsUpdate = false
                }
                downloading = false
            }
            is ModelDownloadStatus.Failed -> {
                failed = true
                downloading = false
            }
            else -> {}
        }
    }

    if (!needsUpdate) return

    fun startDownload() {
        failed = false
        // Upstream dismisses the "model update available" nag once the user
        // starts the download; same behavior via the fork's notification seam.
        cancelNotifyLocal(platformContext, STT_UPDATE_NOTIFICATION_ID)
        scope.launch {
            val info = modelManager.getAvailableSTTModels().firstOrNull { it.slug == targetModel }
            if (info != null && modelManager.downloadSTTModel(info, allowMetered = true)) {
                downloading = true
            } else {
                failed = true
            }
        }
    }

    M3Dialog(
        onDismissRequest = { if (!downloading) needsUpdate = false },
        properties = DialogProperties(
            dismissOnBackPress = !downloading,
            dismissOnClickOutside = !downloading,
        ),
        icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
        title = {
            Text(
                when {
                    downloading -> "Downloading voice model"
                    failed -> "Download failed"
                    else -> "Voice recognition upgraded"
                }
            )
        },
        buttons = {
            if (downloading) {
                TextButton(onClick = {
                    modelManager.cancelDownload()
                    downloading = false
                }) { Text("Cancel") }
            } else {
                TextButton(onClick = { needsUpdate = false }) { Text("Later") }
                TextButton(onClick = { startDownload() }) { Text(if (failed) "Retry" else "Download") }
            }
        },
    ) {
        when {
            downloading -> {
                Text("Downloading the new voice model. This may take a few minutes.")
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            failed -> Text("The download didn't finish. Check your connection and try again.")
            else -> Text(
                "Offline voice recognition now uses a new engine and needs a fresh model download to keep transcribing offline."
            )
        }
    }
}
