package com.anopticlabs.gravel.notebook.ui

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.storage.KeyStoreFailureException
import com.anopticlabs.gravel.notebook.storage.NotebookRecovery
import com.anopticlabs.gravel.notebook.storage.RecoveryPlan
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a storage card action did not finish: a failure that may pass, or a key that fails or could not be looked up, kept until forced. */
enum class RecoveryFailure { NOT_FINISHED, KEY_STORE }

/** [busy] while a storage card action runs; [failed] when the last one, for [failedOn], did not finish; [runs] counts the finished ones. */
data class RecoveryRunState(val busy: Boolean = false, val failed: RecoveryFailure? = null, val failedOn: StorageTrouble? = null, val runs: Long = 0)

/** What a restore from the storage card sets aside or deletes first; null when nothing needs to be. */
internal fun restorePlanFor(trouble: StorageTrouble): RecoveryPlan? = when (trouble) {
    StorageTrouble.KeyGone -> RecoveryPlan.StartOver
    is StorageTrouble.Damaged -> RecoveryPlan.StartFresh(trouble.parts)
    StorageTrouble.Unreadable -> null
}

/**
 * The storage card's actions, one at a time on a scope of their own, process-wide so leaving the
 * screen does not cut a reset short. A restore goes to the backup flow: from the newest automatic
 * backup when part of the Notebook is damaged and one exists, else from a file.
 */
class StorageRecoveryRunner(
    private val recovery: NotebookRecovery,
    private val restoreFile: (RecoveryPlan) -> Unit,
    private val restoreAutomatic: (RecoveryPlan) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(RecoveryRunState())
    val state: StateFlow<RecoveryRunState> = _state.asStateFlow()

    fun repair(trouble: StorageTrouble) = run(trouble) { recovery.repair() }

    fun startFresh(trouble: StorageTrouble.Damaged) = run(trouble) { recovery.startFresh(trouble.parts) }

    /** [force] deletes a key that fails or cannot be looked up, after the card's own warning. */
    fun startOver(force: Boolean = false) = run(StorageTrouble.KeyGone) { recovery.startOver(force) }

    /** [automaticBackupAt] is when the newest automatic backup was made, null when there is none to restore. */
    fun restore(trouble: StorageTrouble, automaticBackupAt: Long?) {
        val plan = restorePlanFor(trouble) ?: return
        if (plan is RecoveryPlan.StartFresh && automaticBackupAt != null) restoreAutomatic(plan) else restoreFile(plan)
    }

    /** Drops the failure line once a status read no longer finds the kind of trouble it was for. */
    fun seen(trouble: StorageTrouble?) {
        _state.update { s -> if (s.failedOn != null && (trouble == null || trouble::class != s.failedOn::class)) s.copy(failed = null, failedOn = null) else s }
    }

    private fun run(trouble: StorageTrouble, block: suspend () -> Unit) {
        val current = _state.value
        if (current.busy || !_state.compareAndSet(current, current.copy(busy = true, failed = null, failedOn = null))) return
        scope.launch {
            val failed = try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w { "storage recovery stopped: ${e::class.simpleName}" }
                if (e is KeyStoreFailureException) RecoveryFailure.KEY_STORE else RecoveryFailure.NOT_FINISHED
            }
            _state.update { RecoveryRunState(busy = false, failed = failed, failedOn = trouble.takeIf { failed != null }, runs = it.runs + 1) }
        }
    }

    private companion object {
        val logger = Logger.withTag("StorageRecovery")
    }
}
