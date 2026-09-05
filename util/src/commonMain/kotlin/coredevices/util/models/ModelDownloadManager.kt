package coredevices.util.models

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull

expect class ModelDownloadManager {
    val downloadStatus: StateFlow<ModelDownloadStatus>
    fun downloadSTTModel(modelInfo: ModelInfo, allowMetered: Boolean): Boolean
    fun cancelDownload()
}

sealed interface ModelDownloadStatus {
    object Idle : ModelDownloadStatus
    object Cancelled : ModelDownloadStatus
    data class Downloading(val modelSlug: String, val progress: Float? = null) : ModelDownloadStatus
    data class Failed(val modelSlug: String, val errorMessage: String) : ModelDownloadStatus
}

/**
 * Suspends until a download scheduled just before this call has settled:
 * the first non-Downloading status after the replayed current one (Idle
 * on success and on Android cancel, Cancelled on iOS cancel, Failed on
 * error). Waiting on Idle alone would leave a failed download's waiter
 * alive to act on the next unrelated download's Idle. Whether the model
 * then exists is the caller's check: a cancelled download settles too.
 */
suspend fun awaitModelDownloadSettled(status: Flow<ModelDownloadStatus>) {
    status.drop(1).firstOrNull { it !is ModelDownloadStatus.Downloading }
}
