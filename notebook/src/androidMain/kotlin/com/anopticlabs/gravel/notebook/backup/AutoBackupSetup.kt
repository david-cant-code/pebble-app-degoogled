package com.anopticlabs.gravel.notebook.backup

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/** How the screen-lock prompt ended: confirmed, turned away by the user, or could not be shown or failed. */
enum class LockAnswer { CONFIRMED, CANCELED, FAILED }

/**
 * Why setup runs: first setup with a new key, setup with the key a restore was opened with, a
 * new folder, or a new key in the same folder for a user who no longer has theirs.
 */
enum class SetupPurpose { NEW_KEY, RESTORED_KEY, CHANGE_FOLDER, REPLACE_KEY }

sealed interface SetupState {
    data object Idle : SetupState

    /** The phone's screen lock is to be confirmed before setup goes on; [opened] once [AutoBackupSetup.claimLockPrompt] handed the prompt out. */
    data class ConfirmLock(val opened: Boolean, val purpose: SetupPurpose = SetupPurpose.NEW_KEY) : SetupState

    /** What automatic backups do, before the folder picker. */
    data object Explain : SetupState

    /** The folder picker is to open; [opened] once [AutoBackupSetup.claimPicker] handed it out. */
    data class PickFolder(val opened: Boolean) : SetupState

    /** The new key, shown during setup; [warning] while the warning before copying it is open. [display] is a String, not wiped. */
    data class ShowKey(val display: String, val warning: Boolean) : SetupState

    data class TypeBack(val wrong: Boolean) : SetupState

    data class Saving(val purpose: SetupPurpose = SetupPurpose.NEW_KEY) : SetupState

    data class Failed(val problem: AutoBackupProblem, val purpose: SetupPurpose = SetupPurpose.NEW_KEY) : SetupState

    data object NoPicker : SetupState

    /** Android could not ask for the screen lock, so setup did not start. */
    data object LockFailed : SetupState

    data class Done(val entry: AutoBackupEntry, val created: CreatedBackup, val purpose: SetupPurpose = SetupPurpose.NEW_KEY) : SetupState
}

/**
 * The steps of setting up automatic backups, process-wide like the manual flow. On a phone with a
 * screen lock ([screenLocked]), every setup starts by confirming it, and goes no further without
 * that. A new key is shown and must be typed back before the first backup; nothing is stored
 * until that backup has been written and checked (a new setup over damaged files runs [setAsideFirst]
 * when it saves, before that backup), and a failed one keeps the key and the folder
 * for Try again or another folder. Access to a folder picked but not kept is released when setup
 * ends in this process.
 */
class AutoBackupSetup<H>(
    private val runner: AutoBackupRunner<H>,
    private val store: AutoBackupStore,
    private val folders: BackupFolderOps<H>,
    private val scheduler: AutoBackupScheduler,
    private val pickedFolders: PickedFolders,
    private val refresh: suspend () -> Unit,
    private val screenLocked: () -> Boolean,
    /** Runs before a new key's first backup is written: [AutoBackupStore.setAsideForNewSetup] on damaged automatic backup files. */
    private val setAsideFirst: suspend () -> Unit = {},
    private val newKey: () -> CharArray = { RecoveryKey.generate(SecureRandom()::nextInt) },
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Logger.withTag("AutoBackupSetup").w(e) { "setup step failed" } },
    ),
) {
    private val lock = Any()
    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    // One setup's holdings, under [lock].
    private var purpose = SetupPurpose.NEW_KEY
    private var key: CharArray? = null
    private var confirmed = false
    private var picked: String? = null
    private var unheld = false
    private var lastProblem: AutoBackupProblem? = null
    private var afterLock: (() -> SetupState)? = null
    private var lockPrompt = 0

    fun startNew() = start(SetupPurpose.NEW_KEY, null, { SetupState.Explain })

    /** Takes [chars], a recovery key a restore opened with, and goes straight to the folder picker. */
    fun startWithKey(chars: CharArray) = start(SetupPurpose.RESTORED_KEY, chars, { SetupState.PickFolder(opened = false) })

    fun startChangeFolder() = start(SetupPurpose.CHANGE_FOLDER, null, { SetupState.PickFolder(opened = false) })

    /** A new key for the backups in [folder], the stored folder; made once the screen lock is confirmed, then shown and typed back like a first key. */
    fun startReplaceKey(folder: String) = start(SetupPurpose.REPLACE_KEY, null, {
        val k = newKey().also { key = it }
        SetupState.ShowKey(RecoveryKey.display(k), warning = false)
    }, folder)

    /** [first] runs under [lock], once the screen lock is confirmed where there is one. */
    private fun start(next: SetupPurpose, given: CharArray?, first: () -> SetupState, folder: String? = null) {
        // A lock that cannot be read counts as one, so the prompt decides.
        val locked = try {
            screenLocked()
        } catch (e: Exception) {
            true
        }
        val started = synchronized(lock) {
            val s = _state.value
            // A prompt that never answered gives way to a new start.
            if (s is SetupState.ConfirmLock && s.opened) endLocked() else if (s != SetupState.Idle) return@synchronized false
            key?.fill('\u0000')
            purpose = next
            key = given
            confirmed = next == SetupPurpose.RESTORED_KEY || next == SetupPurpose.CHANGE_FOLDER
            picked = folder
            lastProblem = null
            if (locked) {
                afterLock = first
                _state.value = SetupState.ConfirmLock(opened = false, purpose = next)
            } else {
                _state.value = first()
            }
            true
        }
        if (!started) given?.fill('\u0000')
    }

    /** The number of the screen-lock prompt to show, once per prompt the setup waits on; null when there is none. */
    fun claimLockPrompt(): Int? = synchronized(lock) {
        val s = _state.value
        if (s is SetupState.ConfirmLock && !s.opened) {
            _state.value = s.copy(opened = true)
            ++lockPrompt
        } else {
            null
        }
    }

    /** How prompt [prompt] ended; an answer to any prompt but the one shown now changes nothing. */
    fun lockAnswered(prompt: Int, answer: LockAnswer) = synchronized(lock) {
        val s = _state.value
        if (s !is SetupState.ConfirmLock || !s.opened || prompt != lockPrompt) return@synchronized
        if (answer == LockAnswer.CONFIRMED) {
            val next = afterLock
            afterLock = null
            _state.value = next?.invoke() ?: SetupState.Idle
        } else {
            endLocked()
            _state.value = if (answer == LockAnswer.FAILED) SetupState.LockFailed else SetupState.Idle
        }
    }

    fun explained() = move(SetupState.Explain, SetupState.PickFolder(opened = false))

    /** True once per picker the setup waits on: the caller then opens it. */
    fun claimPicker(): Boolean = synchronized(lock) {
        val s = _state.value
        if (s is SetupState.PickFolder && !s.opened) {
            _state.value = s.copy(opened = true)
            true
        } else {
            false
        }
    }

    fun pickerUnavailable() = synchronized(lock) {
        if (_state.value is SetupState.PickFolder) {
            endLocked()
            _state.value = SetupState.NoPicker
        }
    }

    /** The host is in front again with the picker this setup opened unanswered: as if the user backed out. */
    fun returned() = synchronized(lock) {
        val s = _state.value
        if (s is SetupState.PickFolder && s.opened) backedOut()
    }

    /** The folder picker's answer, null when the user backed out. */
    fun folderChosen(folder: String?) = synchronized(lock) {
        val s = _state.value
        if (s !is SetupState.PickFolder || !s.opened) return@synchronized
        if (folder == null) {
            backedOut()
            return@synchronized
        }
        picked?.takeIf { it != folder }?.let(::releaseUnlessActive)
        picked = folder
        pickedFolders.add(folder)
        unheld = !folders.holdFolder(folder)
        if (unheld) {
            fail(AutoBackupProblem.NO_ACCESS)
            return@synchronized
        }
        if (confirmed) {
            save()
        } else {
            val k = key ?: newKey().also { key = it }
            _state.value = SetupState.ShowKey(RecoveryKey.display(k), warning = false)
        }
    }

    fun askToCopy() = synchronized(lock) {
        (_state.value as? SetupState.ShowKey)?.let { _state.value = it.copy(warning = true) }
    }

    /** Closes the warning, handing [copy] the key as shown when the warning was up. */
    fun copyAfterWarning(copy: (String) -> Unit) = synchronized(lock) {
        val s = _state.value as? SetupState.ShowKey ?: return@synchronized
        if (s.warning) copy(s.display)
        _state.value = s.copy(warning = false)
    }

    fun closeWarning() = synchronized(lock) {
        (_state.value as? SetupState.ShowKey)?.let { _state.value = it.copy(warning = false) }
    }

    fun keyNoted() = synchronized(lock) {
        if (_state.value is SetupState.ShowKey) _state.value = SetupState.TypeBack(wrong = false)
    }

    fun showKeyAgain() = synchronized(lock) {
        val k = key
        if (_state.value is SetupState.TypeBack && k != null) _state.value = SetupState.ShowKey(RecoveryKey.display(k), warning = false)
    }

    /** [chars], zeroed here, must be the shown key in any form [RecoveryKey.parse] reads. */
    fun typedBack(chars: CharArray) = synchronized(lock) {
        val typed = RecoveryKey.parse(java.nio.CharBuffer.wrap(chars))
        chars.fill('\u0000')
        val k = key
        if (_state.value !is SetupState.TypeBack || k == null) {
            typed?.fill('\u0000')
            return@synchronized
        }
        val matches = typed != null && typed.contentEquals(k)
        typed?.fill('\u0000')
        if (matches) {
            confirmed = true
            save()
        } else {
            _state.value = SetupState.TypeBack(wrong = true)
        }
    }

    /** Saves again, or opens the picker again where the picked folder's access could not be kept. */
    fun tryAgain() = synchronized(lock) {
        if (_state.value !is SetupState.Failed || picked == null) return@synchronized
        if (confirmed && !unheld) save() else _state.value = SetupState.PickFolder(opened = false)
    }

    /** Not offered for a new key, which stays with the stored folder. */
    fun pickAnother() = synchronized(lock) {
        if (_state.value is SetupState.Failed && purpose != SetupPurpose.REPLACE_KEY) _state.value = SetupState.PickFolder(opened = false)
    }

    /** Ends the setup except while its backup is being written. */
    fun close() = synchronized(lock) {
        if (_state.value is SetupState.Saving) return@synchronized
        endLocked()
        _state.value = SetupState.Idle
    }

    /** Under [lock]. */
    private fun save() {
        val folder = picked ?: return
        val given = key
        val thePurpose = purpose
        _state.value = SetupState.Saving(thePurpose)
        scope.launch {
            val setAside = if (thePurpose != SetupPurpose.NEW_KEY) null else try {
                setAsideFirst()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                autoBackupProblemOf(e)
            }
            val previous = try {
                store.readState(strict = true).folder
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            var keyProblem = AutoBackupProblem.KEY_MISSING
            val stored = if (given != null) {
                null
            } else {
                try {
                    store.readKey()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    keyProblem = autoBackupProblemOf(e)
                    null
                }
            }
            val k = given?.copyOf() ?: stored?.chars
            val result = if (setAside != null) {
                k?.fill('\u0000')
                AutoRunResult.Failed(setAside, retry = false)
            } else if (k == null) {
                AutoRunResult.Failed(keyProblem, retry = false)
            } else {
                try {
                    runner.firstBackup(folder, k, thePurpose, stored?.createdAtMillis)
                } finally {
                    k.fill('\u0000')
                }
            }
            if (result is AutoRunResult.Saved) {
                if (runCatching { store.readState(strict = true).enabled }.getOrDefault(true)) scheduler.schedulePeriodic()
                if (previous != null && previous != folder) folders.releaseFolder(previous)
                pickedFolders.remove(folder)
            }
            refresh()
            synchronized(lock) {
                if (_state.value !is SetupState.Saving) return@synchronized
                when (result) {
                    is AutoRunResult.Saved -> {
                        picked = null
                        endLocked()
                        _state.value = SetupState.Done(result.entry, result.created, thePurpose)
                    }
                    is AutoRunResult.Failed -> fail(result.problem)
                    else -> fail(AutoBackupProblem.NOT_SAVED)
                }
            }
        }
    }

    /** Under [lock]. */
    private fun fail(problem: AutoBackupProblem) {
        lastProblem = problem
        _state.value = SetupState.Failed(problem, purpose)
    }

    /** Under [lock]: back to the failed step when the picker was opened for another folder, else the end. */
    private fun backedOut() {
        val problem = lastProblem
        if (problem != null && picked != null) {
            _state.value = SetupState.Failed(problem, purpose)
        } else {
            endLocked()
            _state.value = SetupState.Idle
        }
    }

    /** Under [lock]: wipes the key and lets go of a folder picked but not kept. */
    private fun endLocked() {
        key?.fill('\u0000')
        key = null
        afterLock = null
        picked?.let(::releaseUnlessActive)
        picked = null
        unheld = false
        lastProblem = null
    }

    /** Lets go of [folder] unless the stored state names it; when the state cannot be read, keeps it for the next start's sweep. */
    private fun releaseUnlessActive(folder: String) {
        scope.launch {
            val active = runCatching { store.readState(strict = true).folder }.getOrElse { return@launch }
            if (folder != active) folders.releaseFolder(folder)
            pickedFolders.remove(folder)
        }
    }

    private fun move(from: SetupState, to: SetupState) = synchronized(lock) {
        if (_state.value == from) _state.value = to
    }
}

/**
 * The folders setup took lasting access to and has not yet kept or let go of, in one small file,
 * so the next process can let go of those an ended one left. No call throws.
 */
class PickedFolders(private val file: File) {
    private val lock = Any()

    fun all(): List<String> = synchronized(lock) {
        try {
            if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(folder: String) = rewrite { it + folder }

    fun remove(folder: String) = rewrite { it - folder }

    private fun rewrite(change: (List<String>) -> List<String>) = synchronized(lock) {
        try {
            val next = change(all()).distinct()
            if (next.isEmpty()) {
                file.delete()
            } else {
                val staged = File(file.parentFile, file.name + ".new")
                staged.writeText(next.joinToString("\n"))
                staged.renameTo(file)
            }
        } catch (_: Exception) {
        }
        Unit
    }
}
