package com.anopticlabs.gravel.notebook.storage

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.backup.BackupStaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recovers what an ended process left in the Notebook's files and deletes what was set aside long
 * enough; each step's failure is logged and the next runs.
 */
class NotebookHousekeeping(
    private val notes: NoteStore,
    private val queue: QueueStore,
    private val backupStaging: BackupStaging,
    /** [NotebookRecovery.removeStartOverLeftovers]. */
    private val removeStartOverLeftovers: suspend () -> Unit,
) {
    private val mutex = Mutex()

    /**
     * Queues the recordings kept for a later attempt, removes a Start over's leftover folders and
     * sweeps the set-aside files ([EncryptedFiles.sweepQuarantine]). [atStart] adds what only a
     * process start needs: stale staging files, backup staging files and orphan blobs go, the note
     * index is reconciled with the note files, a missing Unclassified is listed, and a damaged
     * recordings list is rebuilt from the recordings.
     */
    suspend fun run(atStart: Boolean) = mutex.withLock {
        if (atStart) {
            step("staging sweep") { notes.sweepStaging() }
            step("backup staging sweep") { backupStaging.sweep() }
            step("note index") {
                val result = notes.reconcile()
                if (result != ReconcileResult()) logger.i { "note index: $result" }
            }
            step("default folders") { notes.ensureDefaultFolders() }
            step("recordings list") { queue.rebuildIfDamaged()?.let { logger.i { "recordings list rebuilt with $it" } } }
        }
        step("salvage") {
            val result = queue.salvagePending()
            if (result.queued.isNotEmpty() || result.kept > 0) {
                logger.i { "salvage queued ${result.queued.size}, kept ${result.kept}" }
            }
        }
        if (atStart) step("orphan blob sweep") { queue.sweepOrphanBlobs() }
        step("start over leftovers") { removeStartOverLeftovers() }
        step("set-aside sweep") { notes.sweepQuarantine() }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "$name failed: ${e::class.simpleName}" }
        }
    }

    private companion object {
        val logger = Logger.withTag("NotebookHousekeeping")
    }
}
