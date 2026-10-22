package com.anopticlabs.gravel.notebook.backup

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface KeyCheckState {
    data object Idle : KeyCheckState

    /** [notNow] offers Not now, which a due reminder has not used yet; [wrong] after a key that is not the recovery key. */
    data class Enter(val wrong: Boolean, val notNow: Boolean) : KeyCheckState

    data object Checking : KeyCheckState

    /** [backupsOn]: a run will write a new backup when [newest] is damaged or gone. */
    data class Passed(val newest: NewestOpened, val backupsOn: Boolean = true) : KeyCheckState

    data class NotChecked(val problem: AutoBackupProblem, val settings: Boolean = false) : KeyCheckState

    /** What a new key means for the backups made so far, before setup makes one. */
    data object NewKey : KeyCheckState
}

/**
 * The recovery key check in Settings: the user types the key, and a right one counts as a check
 * for the reminders. A user without the key can have setup make a new one for the same folder.
 */
class RecoveryKeyCheck<H>(
    private val runner: AutoBackupRunner<H>,
    private val checks: AutoBackupChecks,
    private val store: AutoBackupStore,
    private val setup: AutoBackupSetup<H>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Logger.withTag("RecoveryKeyCheck").w(e) { "key check step failed" } },
    ),
) {
    private val lock = Any()
    private val _state = MutableStateFlow<KeyCheckState>(KeyCheckState.Idle)
    val state: StateFlow<KeyCheckState> = _state.asStateFlow()
    private var notNowOffered = false

    fun start() {
        scope.launch {
            val offered = try {
                val key = store.readState(strict = true).key
                val point = keyCheckPointsAround(key, clock()).first
                point != null && key.notNowPointMillis != point
            } catch (e: Exception) {
                false
            }
            synchronized(lock) {
                if (_state.value != KeyCheckState.Idle) return@synchronized
                notNowOffered = offered
                _state.value = KeyCheckState.Enter(wrong = false, notNow = offered)
            }
        }
    }

    /** [chars], zeroed once checked, in any form [RecoveryKey.parse] reads. */
    fun submit(chars: CharArray) {
        val taken = synchronized(lock) {
            if (_state.value !is KeyCheckState.Enter) return@synchronized false
            _state.value = KeyCheckState.Checking
            true
        }
        if (!taken) {
            chars.fill('\u0000')
            return
        }
        scope.launch {
            val result = try {
                runner.checkKey(chars)
            } catch (e: Exception) {
                KeyCheckResult.NotChecked(autoBackupProblemOf(e), settings = true)
            } finally {
                chars.fill('\u0000')
            }
            try {
                if (result is KeyCheckResult.Right) checks.keyChecked()
            } finally {
                settle(result)
            }
        }
    }

    private fun settle(result: KeyCheckResult) = synchronized(lock) {
        if (_state.value != KeyCheckState.Checking) return@synchronized
        _state.value = when (result) {
            is KeyCheckResult.Right -> KeyCheckState.Passed(result.newest, result.backupsOn)
            KeyCheckResult.Wrong -> KeyCheckState.Enter(wrong = true, notNow = notNowOffered)
            is KeyCheckResult.NotChecked -> KeyCheckState.NotChecked(result.problem, result.settings)
        }
    }

    fun notNow() {
        val taken = synchronized(lock) {
            val s = _state.value
            if (s !is KeyCheckState.Enter || !s.notNow) return@synchronized false
            _state.value = KeyCheckState.Idle
            true
        }
        if (taken) scope.launch { checks.notNow() }
    }

    fun noKey() = synchronized(lock) {
        if (_state.value is KeyCheckState.Enter) _state.value = KeyCheckState.NewKey
    }

    /** Hands over to setup, which shows the new key for the stored folder. */
    fun makeNewKey() {
        val taken = synchronized(lock) {
            if (_state.value != KeyCheckState.NewKey) return@synchronized false
            _state.value = KeyCheckState.Idle
            true
        }
        if (!taken) return
        scope.launch {
            val folder = try {
                store.readState(strict = true).folder
            } catch (e: Exception) {
                null
            } ?: return@launch
            setup.startReplaceKey(folder)
        }
    }

    /** Ends the check except while a key is being checked. */
    fun close() = synchronized(lock) {
        if (_state.value != KeyCheckState.Checking) _state.value = KeyCheckState.Idle
    }
}
