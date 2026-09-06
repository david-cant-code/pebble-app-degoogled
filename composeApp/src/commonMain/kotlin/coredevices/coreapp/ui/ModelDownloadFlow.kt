package coredevices.coreapp.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.ModelManager
import coredevices.util.models.awaitModelDownloadSettled
import coredevices.util.transcription.CactusModelPathProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Dialog title while a model prompt's download runs. */
internal const val MODEL_DOWNLOADING_TITLE = "Downloading voice model"

/** Dialog title after a model prompt's download failed. */
internal const val MODEL_DOWNLOAD_FAILED_TITLE = "Download failed"

/** Dialog body after a model prompt's download failed. */
internal const val MODEL_DOWNLOAD_FAILED_BODY = "The download didn't finish. Check your connection and try again."

/**
 * The download-then-act state the model prompts share. [download] acts at
 * once when the model is already installed, otherwise schedules the
 * download and, once it has settled (see [awaitModelDownloadSettled]),
 * runs [onInstalled] only when the model really installed and reports
 * every other end as [failed]. [onStarted] runs when a download is
 * scheduled and [onEnded] when one ends without installing, by failure or
 * [cancel], so a caller can hold state for exactly the download's life.
 * The dependencies are plain functions so the machine runs under host
 * tests; [rememberModelDownloadFlow] wires the real ones.
 */
@Stable
class ModelDownloadFlow(
    private val scope: CoroutineScope,
    private val status: Flow<ModelDownloadStatus>,
    private val isInstalled: suspend (slug: String) -> Boolean,
    private val schedule: suspend (slug: String) -> Boolean,
    private val cancelDownload: () -> Unit,
    private val onStarted: () -> Unit = {},
    private val onInstalled: (slug: String) -> Unit,
    private val onEnded: () -> Unit = {},
) {
    var downloading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var settling: Job? = null

    /** Installs [slug] if needed and hands it to [onInstalled]; ignored while a download runs or is being scheduled. */
    fun download(slug: String) {
        // The job itself is the re-entry guard: [downloading] is raised only
        // once the schedule has gone through, and a second tap can land while
        // the install check is still on the IO dispatcher.
        if (downloading || settling?.isActive == true) return
        failed = false
        settling = scope.launch {
            if (isInstalled(slug)) {
                onInstalled(slug)
                return@launch
            }
            if (!schedule(slug)) {
                failed = true
                return@launch
            }
            downloading = true
            onStarted()
            var installed = false
            try {
                awaitModelDownloadSettled(status)
                installed = isInstalled(slug)
            } finally {
                // The scope dies with its composition, so a settle cancelled
                // that way still ends the switch it started; [cancel] has
                // already ended it and leaves nothing to do here.
                if (downloading) {
                    downloading = false
                    if (!installed) onEnded()
                }
            }
            if (installed) onInstalled(slug) else failed = true
        }
    }

    /** Stops the running download; nothing is selected afterwards. */
    fun cancel() {
        if (!downloading) return
        settling?.cancel()
        settling = null
        cancelDownload()
        downloading = false
        onEnded()
    }
}

/**
 * A [ModelDownloadFlow] over the injected model manager and provider,
 * remembered per [key]: the callbacks are captured with it, so a caller
 * whose callbacks close over changing state keys on that state.
 */
@Composable
fun rememberModelDownloadFlow(
    key: Any?,
    onStarted: () -> Unit = {},
    onInstalled: (slug: String) -> Unit,
    onEnded: () -> Unit = {},
): ModelDownloadFlow {
    val modelManager: ModelManager = koinInject()
    val modelProvider: CactusModelPathProvider = koinInject()
    val scope = rememberCoroutineScope()
    return remember(key) {
        ModelDownloadFlow(
            scope = scope,
            status = modelManager.modelDownloadStatus,
            isInstalled = { slug -> withContext(Dispatchers.IO) { modelProvider.isModelDownloaded(slug) } },
            schedule = { slug ->
                val info = modelManager.getAvailableSTTModels().firstOrNull { it.slug == slug }
                info != null && modelManager.downloadSTTModel(info, allowMetered = true)
            },
            cancelDownload = modelManager::cancelDownload,
            onStarted = onStarted,
            onInstalled = onInstalled,
            onEnded = onEnded,
        )
    }
}

/** The body a model prompt shows while its download runs. */
@Composable
internal fun ModelDownloadProgress(text: String) {
    Text(text)
    Spacer(Modifier.height(24.dp))
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}
