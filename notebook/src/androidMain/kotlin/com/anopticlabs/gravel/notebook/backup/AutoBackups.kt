package com.anopticlabs.gravel.notebook.backup

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Automatic backups as Settings shows them; [failure] only once the user has been told of it. */
data class AutoBackupStatus(
    /** The stored state has been read at least once; until then the rest is a placeholder. */
    val loaded: Boolean = false,
    val setUp: Boolean = false,
    val enabled: Boolean = false,
    val running: Boolean = false,
    val folderName: String? = null,
    val newest: AutoBackupEntry? = null,
    val lastConfirmedAtMillis: Long? = null,
    val failure: AutoBackupFailure? = null,
    /** The stored state could not be read, so the rest of this status is stale. */
    val unreadable: Boolean = false,
    /** The user has been warned that no backup was confirmed for a day, and none has been since. */
    val stale: Boolean = false,
    /** A recovery key check reminder is due. */
    val keyDue: Boolean = false,
    /** The state file is damaged, or a run failed on a damaged or gone key file, so only a new setup brings automatic backups back. */
    val setUpAgain: Boolean = false,
    val keyCheckedAtMillis: Long? = null,
)

/**
 * Automatic backups for the app: [status] for Settings, the switch, Back up now, and the runs a
 * change in the Notebook ([changes]) asks for while they are on. One per process.
 */
class AutoBackups<H>(
    private val store: AutoBackupStore,
    private val runner: AutoBackupRunner<H>,
    private val scheduler: AutoBackupScheduler,
    private val folders: BackupFolderOps<H>,
    private val picked: PickedFolders,
    private val changes: List<StateFlow<*>>,
    /** Asks [AutoBackupChecks] for a check. */
    private val requestChecks: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _status = MutableStateFlow(AutoBackupStatus())
    val status: StateFlow<AutoBackupStatus> = _status.asStateFlow()
    private val started = AtomicBoolean(false)
    private val nowRequested = AtomicBoolean(false)
    private val reading = Mutex()
    private val switching = Mutex()

    /**
     * Once per process, before the stores are written: starts watching for changes, reads the
     * state, keeps the periodic run scheduled while on, and lets go of folders other than the
     * stored one, which a setup ended with its process left held.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Each is subscribed before this returns; until the state is read a change asks for a run anyway.
        for (flow in changes) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                flow.drop(1).collect {
                    val s = _status.value
                    if (s.enabled || !s.loaded || s.unreadable) scheduler.afterChange(runner.running.value)
                }
            }
        }
        for (flow in listOf(store.revision, runner.running)) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { flow.drop(1).collect { scope.launch { refresh() } } }
        }
        scope.launch {
            val state = refresh()
            if (state != null && state.enabled && state.setUp) scheduler.schedulePeriodic()
            if (state != null) {
                for (folder in picked.all()) {
                    if (folder != state.folder) folders.releaseFolder(folder)
                    picked.remove(folder)
                }
            }
            requestChecks()
        }
    }

    /** Off keeps the key, the folder access and the record of backups, so on resumes where it stopped. */
    fun setEnabled(on: Boolean) {
        scope.launch {
            switching.withLock {
                val written = try {
                    store.updateState { it.copy(enabled = on && it.setUp) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w { "automatic backup switch not saved: ${e::class.simpleName}" }
                    null
                }
                if (written != null) {
                    if (written.enabled) scheduler.schedulePeriodic() else scheduler.cancel()
                }
            }
            refresh()
            requestChecks()
        }
    }

    /** A new backup at once, whether or not anything changed; ignored while one is under way or asked for. */
    fun backUpNow() {
        if (runner.running.value != null || !nowRequested.compareAndSet(false, true)) return
        scope.launch {
            try {
                runner.run(AutoBackupRunner.SLOT_NOW, force = true, interactive = true)
            } finally {
                nowRequested.set(false)
            }
            refresh()
            requestChecks()
        }
    }

    /**
     * After the state and key files were deleted or set aside, by a Notebook start over or a new setup:
     * cancels the scheduled runs, re-reads the state, and lets go of every folder grant the app
     * holds that neither that state nor a setup under way uses.
     */
    suspend fun afterFilesSetAside() {
        scheduler.cancel()
        // Grants first: a folder a setup picks after this listing is not in it, so it stays held.
        val held = folders.heldFolders()
        val state = refresh() ?: return
        val keep = setOfNotNull(state.folder) + picked.all()
        for (folder in held) if (folder !in keep) folders.releaseFolder(folder)
    }

    /** For a new setup's save: [AutoBackupStore.setAsideForNewSetup] on damaged state and key files, then [afterFilesSetAside]. */
    suspend fun setAsideForNewSetup() {
        if (store.setAsideForNewSetup()) afterFilesSetAside()
    }

    /** Re-reads the stored state into [status]; null when it could not be read. */
    suspend fun refresh(): AutoBackupState? = reading.withLock {
        try {
            store.readState(strict = true).also { s ->
                _status.value = AutoBackupStatus(
                    loaded = true,
                    setUp = s.setUp,
                    enabled = s.enabled && s.setUp,
                    running = runner.running.value != null,
                    folderName = s.folderName,
                    newest = s.backups.lastOrNull { !it.damaged },
                    lastConfirmedAtMillis = s.lastConfirmedAtMillis,
                    failure = s.failure?.takeIf { it.reported },
                    stale = s.enabled && staleWarned(s),
                    keyDue = s.setUp && keyCheckPointsAround(s.key, clock()).first != null,
                    keyCheckedAtMillis = s.key.checkedAtMillis,
                    setUpAgain = s.failure?.problem.let { it == AutoBackupProblem.DAMAGED || it == AutoBackupProblem.KEY_MISSING } && store.needsNewSetup(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "automatic backup state unreadable: ${e::class.simpleName}" }
            _status.value = _status.value.copy(running = runner.running.value != null, unreadable = true, setUpAgain = store.needsNewSetup())
            null
        }
    }

    private fun staleWarned(s: AutoBackupState): Boolean {
        val last = s.lastConfirmedAtMillis ?: return false
        return (s.staleWarnedAtMillis ?: return false) >= last + STALE_AFTER_MILLIS
    }

    private companion object {
        val logger = Logger.withTag("AutoBackups")
    }
}
