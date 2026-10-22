package com.anopticlabs.gravel.notebook.backup

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.storage.KeyStoreFailureException
import com.anopticlabs.gravel.notebook.storage.RecoveryPlan
import com.anopticlabs.gravel.notebook.ui.StorageProblem
import com.anopticlabs.gravel.notebook.ui.storageProblemOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import java.io.Closeable
import java.nio.CharBuffer
import java.security.DigestInputStream
import java.security.MessageDigest

enum class BackupPurpose { RESTORE, CHECK }

/** What stopped a restore part way: the archive, Gravel's staging copy of it, or a store. */
enum class RestoreStop { FILE, STAGING, KEY_MISSING, DAMAGED, UNREADABLE, KEY_STORE }

/** Why a run ended without its result. */
sealed interface BackupFailure {
    /** The Notebook could not be read, so no backup was made. */
    data class NotRead(val problem: StorageProblem) : BackupFailure

    /** Too little free space to stage an archive. */
    data object NoSpace : BackupFailure

    /** Gravel's own staging files could not be written or read; for a save, [fileName] and [removed] as in [NotSaved]. */
    data class Staging(val fileName: String? = null, val removed: Boolean? = null) : BackupFailure

    /** The archive was made but not saved into [fileName]; [removed] says whether the document was deleted, null when nothing tried. */
    data class NotSaved(val failure: PublishFailure, val fileName: String?, val removed: Boolean?) : BackupFailure

    /** A process ended with a save unfinished; [written] once its first byte was about to land, [removed]: the document was deleted. */
    data class Interrupted(val fileName: String?, val removed: Boolean, val written: Boolean) : BackupFailure

    data object FileUnreadable : BackupFailure

    data class NotOpened(val problem: BackupProblem) : BackupFailure

    /** A restore that failed after it began; what it merged before stays. */
    data class RestoreStopped(val reason: RestoreStop) : BackupFailure

    /** A process ended while a restore ran. */
    data object RestoreInterrupted : BackupFailure

    data object NoPicker : BackupFailure

    /** Android could not ask for the screen lock, so the restore did not start. */
    data object LockFailed : BackupFailure

    /** No automatic backup made with the stored key and not found damaged. */
    data object NoAutomaticBackup : BackupFailure
}

/** The newest automatic backup made with the stored key and not found damaged, with that key, which the flow wipes. */
class AutomaticRestore<H>(val handle: H, val fileName: String?, val key: CharArray, val sha256: String)

sealed interface BackupFlowState {
    data object Idle : BackupFlowState

    data object NewPassword : BackupFlowState

    data class Building(val stage: BackupStage?, val fraction: Float) : BackupFlowState

    /** The save picker is to open with [suggestedName]; [opened] once [BackupFlow.claimPicker] handed it out. */
    data class SaveTo(val suggestedName: String, val opened: Boolean) : BackupFlowState

    data class Saving(val fraction: Float) : BackupFlowState

    data class Saved(val outcome: BackupPublishOutcome, val created: CreatedBackup, val summary: BackupSummary) : BackupFlowState

    data class PickFile(val purpose: BackupPurpose, val opened: Boolean) : BackupFlowState

    data class Copying(val purpose: BackupPurpose) : BackupFlowState

    /** [recoveryKey] when the archive is an automatic backup, which opens with its recovery key instead of a password. */
    data class EnterPassword(val purpose: BackupPurpose, val fileName: String?, val wrong: Boolean, val recoveryKey: Boolean = false) : BackupFlowState

    data class Checking(val purpose: BackupPurpose, val fraction: Float) : BackupFlowState

    /** [plan] is what the restore sets aside or deletes before it adds the archive's contents. */
    data class Contents(val purpose: BackupPurpose, val fileName: String?, val summary: BackupSummary, val plan: RecoveryPlan? = null) : BackupFlowState

    data class Restoring(val stage: BackupStage?, val fraction: Float) : BackupFlowState

    /** [offerKey]: the recovery key the restore opened with can set up automatic backups ([BackupFlow.useKeyForAutomaticBackups]). */
    data class Restored(val report: RestoreReport, val offerKey: Boolean = false) : BackupFlowState

    /** [filePlan]: an automatic restore did not get past opening or the screen lock; [BackupFlow.pickFileInstead] restores a file with it. */
    data class Failed(val failure: BackupFailure, val filePlan: RecoveryPlan? = null) : BackupFlowState

    /** Tidying what an ended process's save left in the picked folder. */
    data object CleaningUp : BackupFlowState

    /** The screen lock is to be confirmed before an automatic backup is restored; [opened] once [BackupFlow.claimLockPrompt] handed it out. */
    data class ConfirmLock(val opened: Boolean) : BackupFlowState
}

/**
 * One manual backup, restore or check at a time, process-wide so a run outlives the screen that
 * started it. A backup is built into staging before the save picker opens, and published with its
 * password, which the flow holds as an array until the publish ends and then zeroes, as it zeroes
 * the array [open] gets once the key is derived and wipes the key when the run ends. A save and a
 * restore under way are noted in [runs], a save by stage ([SaveStage]), so the next process can
 * remove what the save wrote, leave alone what it did not, and say what happened
 * ([recoverUnfinished]). Saving and restoring cannot be canceled. Building and restoring hold
 * [gate], so they wait for an automatic run. After a restore that opened with a recovery key, the
 * flow keeps the key until the run ends, to hand it to [takeKey] when [canTakeKey] allowed the
 * offer. A restore can carry a [RecoveryPlan], which [prepareRestore] carries out once the
 * archive has been checked again and before anything is added. A restore of the newest automatic
 * backup ([automaticBackup]) opens with the stored key, after the screen lock is confirmed on a
 * phone that has one ([screenLocked]). [H] is the document handle of [documents].
 */
class BackupFlow<H>(
    private val backup: BackupOperations,
    private val documents: BackupDocumentOps<H>,
    private val runs: BackupRunRecord<H>,
    private val requestDrain: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val gate: BackupGate = BackupGate(),
    private val canTakeKey: suspend () -> Boolean = { false },
    private val takeKey: (CharArray) -> Unit = { it.fill('\u0000') },
    private val prepareRestore: suspend (RecoveryPlan) -> Unit = {},
    private val automaticBackup: suspend () -> AutomaticRestore<H>? = { null },
    private val screenLocked: () -> Boolean = { true },
) {
    private val lock = Any()
    private val _state = MutableStateFlow<BackupFlowState>(BackupFlowState.Idle)
    val state: StateFlow<BackupFlowState> = _state.asStateFlow()

    // One run's holdings, all under [lock]; [run] changes whenever a run ends, so a late job result is dropped.
    private var run = 0L
    private var job: Job? = null
    private var password: CharArray? = null
    private var openPassword: CharArray? = null
    private var staged: StagedBackup? = null
    private var archive: StagedArchive? = null
    private var key: ArchiveKey? = null
    private var recoveryKey: CharArray? = null
    private var fileName: String? = null
    private var plan: RecoveryPlan? = null
    private var lockPrompt = 0
    private var recovered = false

    fun startBackup() {
        start(BackupFlowState.NewPassword)
    }

    fun startRestore(plan: RecoveryPlan? = null) {
        synchronized(lock) {
            if (start(BackupFlowState.PickFile(BackupPurpose.RESTORE, opened = false))) this.plan = plan
        }
    }

    fun startCheck() {
        start(BackupFlowState.PickFile(BackupPurpose.CHECK, opened = false))
    }

    /** Restores the newest automatic backup after [plan]'s step, once the screen lock is confirmed where there is one. */
    fun startAutomaticRestore(plan: RecoveryPlan) {
        // A lock that cannot be read counts as one, so the prompt decides.
        val locked = try {
            screenLocked()
        } catch (e: Exception) {
            true
        }
        synchronized(lock) {
            if (!start(BackupFlowState.ConfirmLock(opened = false))) return
            this.plan = plan
            if (!locked) openAutomatic()
        }
    }

    private fun start(first: BackupFlowState): Boolean = synchronized(lock) {
        val s = _state.value
        // A prompt that never answered gives way to a new start.
        if (s is BackupFlowState.ConfirmLock && s.opened) deleteLater(endRun()) else if (s != BackupFlowState.Idle) return false
        run++
        _state.value = first
        true
    }

    /** The number of the screen-lock prompt to show, once per prompt the flow waits on; null when there is none. */
    fun claimLockPrompt(): Int? = synchronized(lock) {
        val s = _state.value
        if (s is BackupFlowState.ConfirmLock && !s.opened) {
            _state.value = s.copy(opened = true)
            ++lockPrompt
        } else {
            null
        }
    }

    /** How prompt [prompt] ended; an answer to any prompt but the one shown now changes nothing. */
    fun lockAnswered(prompt: Int, answer: LockAnswer) = synchronized(lock) {
        val s = _state.value
        if (s !is BackupFlowState.ConfirmLock || !s.opened || prompt != lockPrompt) return@synchronized
        when (answer) {
            LockAnswer.CONFIRMED -> openAutomatic()
            LockAnswer.CANCELED -> {
                deleteLater(endRun())
                _state.value = BackupFlowState.Idle
            }
            LockAnswer.FAILED -> {
                val filePlan = plan
                deleteLater(fail(BackupFailure.LockFailed, filePlan))
            }
        }
    }

    /** Under [lock]: stages the newest automatic backup and checks it with the stored key, as [fileChosen] and [open] do for a picked file. */
    private fun openAutomatic() {
        _state.value = BackupFlowState.Copying(BackupPurpose.RESTORE)
        val mine = run
        job = scope.launch {
            val found = try {
                automaticBackup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w { "automatic backup not found: ${e::class.simpleName}" }
                null
            }
            val failure = if (found == null) {
                BackupFailure.NoAutomaticBackup
            } else {
                try {
                    val copy = stagedAsRecorded(found)
                    val late = synchronized(lock) {
                        if (run != mine) return@synchronized true
                        archive = copy
                        fileName = found.fileName
                        _state.value = BackupFlowState.Checking(BackupPurpose.RESTORE, 0f)
                        false
                    }
                    if (late) {
                        copy.close()
                        return@launch
                    }
                    when (val checked = check(mine, BackupPurpose.RESTORE, copy, found.key, typedKey = null)) {
                        null -> return@launch
                        else -> checked
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stagingFailure(e)
                } finally {
                    found.key.fill('\u0000')
                }
            }
            synchronized(lock) {
                if (run != mine) return@synchronized emptyList()
                val filePlan = plan
                fail(failure, filePlan)
            }.forEach { it.close() }
        }
    }

    /** After an automatic restore failed before its contents were shown: picks a backup file to restore with the same plan. */
    fun pickFileInstead() = synchronized(lock) {
        val filePlan = (_state.value as? BackupFlowState.Failed)?.filePlan ?: return@synchronized
        deleteLater(endRun())
        _state.value = BackupFlowState.Idle
        startRestore(filePlan)
    }

    /**
     * Once per process: tidies after the run the last process left unfinished, whatever this one is
     * doing, and reports it when idle. A save that reached [SaveStage.WRITING] loses its document; one
     * only [SaveStage.PICKED] loses it only while empty; a verified one keeps it.
     */
    fun recoverUnfinished() {
        synchronized(lock) {
            if (recovered) return
            recovered = true
        }
        scope.launch {
            val unfinished = runs.read() ?: return@launch
            val tidying = unfinished is UnfinishedRun.Save && unfinished.stage != SaveStage.VERIFIED
            val mine = synchronized(lock) {
                if (_state.value != BackupFlowState.Idle) return@synchronized null
                run++
                if (tidying) _state.value = BackupFlowState.CleaningUp
                run
            }
            val failure = when (unfinished) {
                UnfinishedRun.Restore -> BackupFailure.RestoreInterrupted
                is UnfinishedRun.Save -> {
                    val failure = when (unfinished.stage) {
                        SaveStage.VERIFIED -> null
                        SaveStage.WRITING -> BackupFailure.Interrupted(unfinished.fileName, documents.delete(unfinished.handle), written = true)
                        SaveStage.PICKED -> BackupFailure.Interrupted(unfinished.fileName, deleteIfEmpty(unfinished.handle), written = false)
                    }
                    documents.release(unfinished.handle)
                    failure
                }
            }
            runs.clear(unfinished)
            if (mine == null) return@launch
            synchronized(lock) {
                if (run != mine) return@launch
                if (failure == null) {
                    endRun()
                    _state.value = BackupFlowState.Idle
                } else {
                    fail(failure)
                }
            }
        }
    }

    /**
     * The host is in front again: a picker this flow opened and still waits on answered into an
     * activity that came back without it, so the run ends as if the user backed out.
     */
    fun returned() = synchronized(lock) {
        val s = _state.value
        if ((s is BackupFlowState.SaveTo && s.opened) || (s is BackupFlowState.PickFile && s.opened)) {
            deleteLater(endRun())
            _state.value = BackupFlowState.Idle
        }
    }

    /** True once per picker the flow is waiting on: the caller then opens it. */
    fun claimPicker(): Boolean = synchronized(lock) {
        val claimed = when (val s = _state.value) {
            is BackupFlowState.SaveTo -> s.takeIf { !it.opened }?.copy(opened = true)
            is BackupFlowState.PickFile -> s.takeIf { !it.opened }?.copy(opened = true)
            else -> null
        }
        claimed?.let { _state.value = it }
        claimed != null
    }

    fun pickerUnavailable() = synchronized(lock) {
        if (_state.value is BackupFlowState.SaveTo || _state.value is BackupFlowState.PickFile) deleteLater(fail(BackupFailure.NoPicker))
    }

    /** Hands the recovery key the restore opened with to automatic backup setup, and closes the report. */
    fun useKeyForAutomaticBackups() = synchronized(lock) {
        val s = _state.value
        val k = recoveryKey
        if (s !is BackupFlowState.Restored || !s.offerKey || k == null) return@synchronized
        recoveryKey = null
        deleteLater(endRun())
        _state.value = BackupFlowState.Idle
        takeKey(k)
    }

    /** Leaves the current step and ends the run, except while saving or restoring. */
    fun close() = synchronized(lock) {
        when (_state.value) {
            is BackupFlowState.Saving, is BackupFlowState.Restoring -> Unit
            else -> {
                deleteLater(endRun())
                _state.value = BackupFlowState.Idle
            }
        }
    }

    /** Builds the archive under [chars], which the flow keeps until the publish ends and then zeroes. */
    fun backUp(chars: CharArray) = synchronized(lock) {
        if (_state.value != BackupFlowState.NewPassword || backupPasswordProblem(CharBuffer.wrap(chars), CharBuffer.wrap(chars)) != null) {
            chars.fill('\u0000')
            return@synchronized
        }
        password = chars
        _state.value = BackupFlowState.Building(null, 0f)
        val mine = run
        job = scope.launch {
            val built = try {
                gate.withLock { backup.create(chars) { stage, fraction -> progress(mine) { BackupFlowState.Building(stage, fraction) } } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w { "backup not made: ${e::class.simpleName}" }
                val failure = when (e) {
                    is StagingSpaceException -> BackupFailure.NoSpace
                    is StagingException -> BackupFailure.Staging()
                    else -> BackupFailure.NotRead(storageProblemOf(e))
                }
                synchronized(lock) { if (run == mine) fail(failure) else emptyList() }.forEach { it.close() }
                return@launch
            }
            val late = synchronized(lock) {
                if (run != mine) return@synchronized true
                staged = built
                _state.value = BackupFlowState.SaveTo(suggestedBackupName(clock(), zone()), opened = false)
                false
            }
            if (late) built.close()
        }
    }

    /** The save picker's answer: null when the user backed out. An answer no run waits for is tidied after. */
    fun documentChosen(handle: H?) = synchronized(lock) {
        val s = _state.value
        if (s !is BackupFlowState.SaveTo || !s.opened) {
            if (handle != null) tidyAfterDeadRun(handle, show = s == BackupFlowState.Idle)
            return@synchronized
        }
        if (handle == null) {
            deleteLater(endRun())
            _state.value = BackupFlowState.Idle
            return@synchronized
        }
        val built = staged!!
        val chars = password!!
        _state.value = BackupFlowState.Saving(0f)
        val mine = run
        job = scope.launch {
            val name = documents.describe(handle)?.displayName
            val picked = UnfinishedRun.Save(handle, name, SaveStage.PICKED)
            runs.write(picked)
            documents.hold(handle)
            var note: UnfinishedRun<H> = picked
            val result = try {
                Result.success(
                    backup.publish(
                        documents, handle, built, chars,
                        // No byte lands without a note that the next process may remove the document.
                        onWriting = { note = UnfinishedRun.Save(handle, name, SaveStage.WRITING).also { check(runs.write(it)) { "run note not written" } } },
                        onVerified = {
                            val verified = UnfinishedRun.Save(handle, name, SaveStage.VERIFIED)
                            if (runs.write(verified)) note = verified else runs.clear(note)
                        },
                    ) { stage, fraction ->
                        if (stage == BackupStage.CHECKING) progress(mine) { BackupFlowState.Saving(fraction) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val failure = result.exceptionOrNull()?.let { e ->
                logger.w { "backup not saved: ${e::class.simpleName}" }
                if (e is BackupPublishException) {
                    BackupFailure.NotSaved(e.failure, e.fileName ?: name, e.removed)
                } else {
                    // Thrown outside the publisher's writes, so the document is removed only while still empty.
                    val removed = deleteIfEmpty(handle)
                    if (e is StagingException) BackupFailure.Staging(name, removed) else BackupFailure.NotSaved(PublishFailure.NOT_WRITTEN, name, removed)
                }
            }
            runs.clear(note)
            documents.release(handle)
            synchronized(lock) {
                if (run != mine) return@synchronized emptyList()
                val files = endRun()
                _state.value = if (failure == null) {
                    val published = result.getOrThrow()
                    BackupFlowState.Saved(published.outcome, built.created, published.summary)
                } else {
                    BackupFlowState.Failed(failure)
                }
                files
            }.forEach { it.close() }
        }
    }

    /** Under [lock]: deletes the document a dead run's picker created when [deleteIfEmpty] finds it empty; says so when [show]. */
    private fun tidyAfterDeadRun(handle: H, show: Boolean) {
        val mine = if (show) {
            run++
            _state.value = BackupFlowState.CleaningUp
            run
        } else {
            null
        }
        scope.launch {
            val name = documents.describe(handle)?.displayName
            val removed = deleteIfEmpty(handle)
            if (mine != null) synchronized(lock) { if (run == mine) fail(BackupFailure.Interrupted(name, removed, written = false)) }
        }
    }

    /** Deletes a document Gravel did not write to only when it reports no data and its first read finds the end. */
    private fun deleteIfEmpty(handle: H): Boolean {
        if ((documents.describe(handle)?.sizeBytes ?: 0L) > 0L) return false
        val empty = try {
            documents.openRead(handle).use { it.read() < 0 }
        } catch (e: Exception) {
            false
        }
        return empty && documents.delete(handle)
    }

    /** The open picker's answer for [purpose]: null when the user backed out. A result after the process died starts a run. */
    fun fileChosen(purpose: BackupPurpose, handle: H?) = synchronized(lock) {
        val s = _state.value
        val waiting = s is BackupFlowState.PickFile && s.opened && s.purpose == purpose
        if (!waiting && s != BackupFlowState.Idle) return@synchronized
        if (handle == null) {
            if (waiting) {
                deleteLater(endRun())
                _state.value = BackupFlowState.Idle
            }
            return@synchronized
        }
        if (!waiting) run++
        _state.value = BackupFlowState.Copying(purpose)
        val mine = run
        job = scope.launch {
            val failure = try {
                val name = documents.describe(handle)?.displayName
                val copy = staged(handle)
                val late = synchronized(lock) {
                    if (run != mine) return@synchronized true
                    archive = copy
                    fileName = name
                    _state.value = BackupFlowState.EnterPassword(purpose, name, wrong = false, recoveryKey = copy.isAutomatic)
                    false
                }
                if (late) copy.close()
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stagingFailure(e)
            }
            synchronized(lock) { if (run == mine) fail(failure) else emptyList() }.forEach { it.close() }
        }
    }

    private suspend fun staged(handle: H): StagedArchive = documents.openRead(handle).use { backup.stage(it) }

    /** Stages [found]'s file; one whose SHA-256 is not the one recorded when it was written is refused as damaged. */
    private suspend fun stagedAsRecorded(found: AutomaticRestore<H>): StagedArchive {
        val digest = MessageDigest.getInstance("SHA-256")
        val copy = documents.openRead(found.handle).use { backup.stage(DigestInputStream(it, digest)) }
        if (digest.digest().toHex() != found.sha256) {
            copy.close()
            throw InvalidBackupException(BackupProblem.DAMAGED, "the automatic backup is not the file that was written")
        }
        return copy
    }

    private fun stagingFailure(e: Exception): BackupFailure = when (e) {
        is InvalidBackupException -> BackupFailure.NotOpened(e.problem)
        is StagingSpaceException -> BackupFailure.NoSpace
        is StagingException -> BackupFailure.Staging()
        else -> {
            logger.w { "picked file not read: ${e::class.simpleName}" }
            BackupFailure.FileUnreadable
        }
    }

    /** Opens the picked archive with [chars], zeroed once its key is derived; a wrong password asks again. */
    fun open(chars: CharArray) = synchronized(lock) {
        val s = _state.value
        val picked = archive
        if (s !is BackupFlowState.EnterPassword || picked == null) {
            chars.fill('\u0000')
            return@synchronized
        }
        openPassword = chars
        _state.value = BackupFlowState.Checking(s.purpose, 0f)
        val mine = run
        job = scope.launch {
            // Copied before the typed chars are zeroed, for the offer after a restore; wiped unless the run takes it.
            val typedKey = if (s.purpose == BackupPurpose.RESTORE && picked.isAutomatic) RecoveryKey.parse(CharBuffer.wrap(chars)) else null
            val failure = check(mine, s.purpose, picked, chars, typedKey) ?: return@launch
            if (failure == BackupFailure.NotOpened(BackupProblem.WRONG_PASSWORD_OR_DAMAGED)) {
                synchronized(lock) { if (run == mine) _state.value = s.copy(wrong = true) }
                return@launch
            }
            synchronized(lock) { if (run == mine) fail(failure) else emptyList() }.forEach { it.close() }
        }
    }

    /**
     * Derives [picked]'s key from [secret], which it zeroes, and reads the archive through; shows
     * its contents and returns null, or returns why not. The run takes [typedKey] on success; it
     * is wiped otherwise.
     */
    private suspend fun check(mine: Long, purpose: BackupPurpose, picked: StagedArchive, secret: CharArray, typedKey: CharArray?): BackupFailure? {
        var held = typedKey
        var derived: ArchiveKey? = null
        try {
            derived = try {
                backup.unlock(picked, secret)
            } finally {
                secret.fill('\u0000')
            }
            val summary = backup.inspect(picked, derived) { _, fraction -> progress(mine) { BackupFlowState.Checking(purpose, fraction) } }
            synchronized(lock) {
                if (run == mine) {
                    key = derived
                    derived = null
                    recoveryKey = held
                    held = null
                    _state.value = BackupFlowState.Contents(purpose, fileName, summary, plan.takeIf { purpose == BackupPurpose.RESTORE })
                }
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidBackupException) {
            return BackupFailure.NotOpened(e.problem)
        } catch (e: StagingException) {
            return BackupFailure.Staging()
        } catch (e: Exception) {
            logger.w { "picked file not checked: ${e::class.simpleName}" }
            return BackupFailure.FileUnreadable
        } finally {
            held?.fill('\u0000')
            derived?.wipe()
        }
    }

    /** Restores the archive [open] checked, with the key it derived, then asks for a drain unless it finished adding no capture. */
    fun restore() = synchronized(lock) {
        val s = _state.value
        val picked = archive
        val derived = key
        if (s !is BackupFlowState.Contents || s.purpose != BackupPurpose.RESTORE || picked == null || derived == null) return@synchronized
        val prepare = s.plan
        _state.value = BackupFlowState.Restoring(null, 0f)
        val mine = run
        job = scope.launch {
            val note = UnfinishedRun.Restore
            runs.write(note)
            val result = try {
                val restored = gate.withLock {
                    backup.restore(picked, derived, { prepare?.let { prepareRestore(it) } }) { stage, fraction ->
                        progress(mine) { BackupFlowState.Restoring(stage, fraction) }
                    }
                }
                Result.success(restored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            runs.clear(note)
            if (result.isFailure || result.getOrThrow().capturesAdded > 0) requestDrain()
            val offer = result.isSuccess && synchronized(lock) { recoveryKey != null } && runCatching { canTakeKey() }.getOrDefault(false)
            synchronized(lock) {
                if (run != mine) return@synchronized emptyList()
                // The key outlives the run's other holdings while the offer is open.
                val kept = recoveryKey.takeIf { offer }
                if (kept != null) recoveryKey = null
                val files = endRun()
                recoveryKey = kept
                _state.value = result.fold(
                    onSuccess = { BackupFlowState.Restored(it, offerKey = offer) },
                    onFailure = {
                        logger.w { "restore stopped: ${it::class.simpleName}" }
                        BackupFlowState.Failed(BackupFailure.RestoreStopped(stopOf(it)))
                    },
                )
                files
            }.forEach { it.close() }
        }
    }

    private fun stopOf(e: Throwable): RestoreStop = when {
        e is InvalidBackupException -> RestoreStop.FILE
        e is StagingException -> RestoreStop.STAGING
        e is KeyStoreFailureException -> RestoreStop.KEY_STORE
        else -> when (storageProblemOf(e)) {
            StorageProblem.KEY_MISSING -> RestoreStop.KEY_MISSING
            StorageProblem.DAMAGED -> RestoreStop.DAMAGED
            StorageProblem.UNREADABLE -> RestoreStop.UNREADABLE
        }
    }

    private inline fun progress(mine: Long, next: () -> BackupFlowState) = synchronized(lock) {
        if (run == mine) _state.value = next()
    }

    /** Under [lock], in one write so no one watching sees the failure without [filePlan]; returns the run's staging files for the caller to delete outside it. */
    private fun fail(failure: BackupFailure, filePlan: RecoveryPlan? = null): List<Closeable> {
        val files = endRun()
        _state.value = BackupFlowState.Failed(failure, filePlan)
        return files
    }

    /** Under [lock]: drops the run's holdings, zeroing its passwords; returns its staging files for the caller to delete. */
    private fun endRun(): List<Closeable> {
        run++
        job?.cancel()
        job = null
        password?.fill('\u0000')
        password = null
        openPassword?.fill('\u0000')
        openPassword = null
        val files = listOfNotNull(staged, archive)
        staged = null
        archive = null
        key?.wipe()
        key = null
        recoveryKey?.fill('\u0000')
        recoveryKey = null
        fileName = null
        plan = null
        return files
    }

    /** Deletes [files] on the I/O dispatcher, away from a caller on the main thread. */
    private fun deleteLater(files: List<Closeable>) {
        if (files.isNotEmpty()) scope.launch { files.forEach { it.close() } }
    }

    private companion object {
        val logger = Logger.withTag("BackupFlow")
    }
}

enum class PasswordProblem { NOT_TEXT, TOO_SHORT, DIFFERENT }

/** What keeps [password], typed again as [again], from sealing a backup; null when it can. */
fun backupPasswordProblem(password: CharSequence, again: CharSequence): PasswordProblem? {
    val first = normalized(password) ?: return PasswordProblem.NOT_TEXT
    val second = normalized(again)
    try {
        if (!ArchiveCrypto.isLongEnough(first)) return PasswordProblem.TOO_SHORT
        return if (second != null && first.contentEquals(second)) null else PasswordProblem.DIFFERENT
    } finally {
        first.fill('\u0000')
        second?.fill('\u0000')
    }
}

private fun normalized(chars: CharSequence): CharArray? {
    val copy = CharArray(chars.length) { chars[it] }
    try {
        return ArchiveCrypto.normalizedPassword(copy)
    } finally {
        copy.fill('\u0000')
    }
}
