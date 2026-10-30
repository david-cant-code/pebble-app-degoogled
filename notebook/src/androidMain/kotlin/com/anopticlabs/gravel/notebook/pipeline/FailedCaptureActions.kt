package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.QueueStore.FailedRemoval
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * What the failed list does with flagged captures. Both actions leave alone the capture the drain is
 * running ([processing]), which is flagged during its last attempt. A retry asks for a run that goes
 * round ([CaptureCoordinator.requestAgain]), since a pass under way may have read the queue before it.
 */
class FailedCaptureActions(
    private val queue: QueueStore,
    private val processing: (String) -> Boolean,
    private val requestAgain: () -> Unit,
) {
    /** Outlives a closed screen, so a written retry still asks for its run. */
    suspend fun retry(ids: Set<String>) = withContext(NonCancellable) {
        if (queue.retryFailed(ids, processing)) requestAgain()
    }

    suspend fun delete(id: String): FailedRemoval = queue.removeFailed(id, processing)
}
