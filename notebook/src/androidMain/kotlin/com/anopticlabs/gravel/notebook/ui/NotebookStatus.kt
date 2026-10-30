package com.anopticlabs.gravel.notebook.ui

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.FailedCapture
import com.anopticlabs.gravel.notebook.capture.ProcessingRules
import com.anopticlabs.gravel.notebook.capture.failedCaptures
import com.anopticlabs.gravel.notebook.capture.isLong
import com.anopticlabs.gravel.notebook.capture.mayStart
import com.anopticlabs.gravel.notebook.capture.needsReview
import com.anopticlabs.gravel.notebook.capture.waitsForPower
import com.anopticlabs.gravel.notebook.pipeline.CapturePublisher
import com.anopticlabs.gravel.notebook.storage.CorruptFileException
import com.anopticlabs.gravel.notebook.storage.NotebookRecovery
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import com.anopticlabs.gravel.notebook.storage.isKeyUnavailableFailure
import com.anopticlabs.gravel.notebook.storage.isPersistedFormatFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Why a Notebook store could not be read: a lost key, a damaged file, or a failure that may pass. */
enum class StorageProblem { KEY_MISSING, DAMAGED, UNREADABLE }

internal fun storageProblemOf(e: Throwable): StorageProblem = when {
    isKeyUnavailableFailure(e) -> StorageProblem.KEY_MISSING
    e is CorruptFileException || isPersistedFormatFailure(e) -> StorageProblem.DAMAGED
    else -> StorageProblem.UNREADABLE
}

/**
 * What the capture area shows besides the live rows; [failed] are the flagged captures the drain is not running; [trouble] set means only [retained] was read, [automaticBackupAt] is
 * read only with damage. [waitingForPower] are the long captures the charger rule holds, which [waiting] leaves out;
 * [longToRun] is whether a long one may start or is under way.
 */
data class NotebookStatus(
    val waiting: Int = 0,
    val failed: List<FailedCapture> = emptyList(),
    val completionsToReview: Int = 0,
    val retained: Int = 0,
    val trouble: StorageTrouble? = null,
    val automaticBackupAt: Long? = null,
    val waitingForPower: Set<String> = emptySet(),
    val longToRun: Boolean = false,
)

class NotebookStatusReader(
    private val queue: QueueStore,
    private val publisher: CapturePublisher,
    private val recovery: NotebookRecovery,
    private val automaticBackupAt: () -> Long? = { null },
    private val rules: () -> ProcessingRules = { ProcessingRules() },
) {
    /**
     * Strict reads of every store part ([NotebookRecovery.diagnose]), the queue and the parked
     * claims (an empty list clears their notification, see [CapturePublisher.readParked]), and a
     * count of the recordings kept for a later attempt to queue them. [inFlight] are the captures
     * a drain is working on, counted as neither waiting nor flagged.
     */
    suspend fun read(inFlight: Set<String>): NotebookStatus = withContext(Dispatchers.IO) {
        try {
            recovery.diagnose()?.let { return@withContext troubled(it, null) }
            val queued = queue.readQueue(strict = true)
            val parked = publisher.readParked()
            val pending = queued.filterNot { it.needsReview }
            val now = rules()
            val forPower = pending.filter { it.id !in inFlight && now.waitsForPower(it) }.mapTo(HashSet()) { it.id }
            NotebookStatus(
                waiting = pending.count { it.id !in inFlight && it.id !in forPower },
                failed = failedCaptures(queued.filter { it.id !in inFlight }, queue::hasBlob),
                completionsToReview = parked.size,
                retained = queue.retainedCount(),
                waitingForPower = forPower,
                longToRun = pending.any { it.isLong && (it.id in inFlight || now.mayStart(it)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            troubled(recovery.diagnose() ?: StorageTrouble.Unreadable, e)
        }
    }

    private suspend fun troubled(trouble: StorageTrouble, e: Exception?): NotebookStatus {
        logger.w { "Notebook storage: $trouble${e?.let { " (${it::class.simpleName})" }.orEmpty()}" }
        val retained = try {
            queue.retainedCount()
        } catch (e: Exception) {
            0
        }
        return NotebookStatus(retained = retained, trouble = trouble, automaticBackupAt = if (trouble is StorageTrouble.Damaged) automaticBackupAt() else null)
    }

    private companion object {
        val logger = Logger.withTag("NotebookStatus")
    }
}
