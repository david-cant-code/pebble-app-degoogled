package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.backup.AutoBackupStatus
import com.anopticlabs.gravel.notebook.storage.DeviceStateFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.days

/** The user's answers to the backup reminder: never again, or Not now until [afterMillis]. */
data class BackupReminderAnswers(val never: Boolean = false, val afterMillis: Long = 0L)

interface BackupReminderStore {
    fun read(): BackupReminderAnswers

    fun write(answers: BackupReminderAnswers): Boolean
}

/** The answers on this phone only, as "never;afterMillis": the reminder is about this phone's backups. */
class BackupReminderFile(private val file: DeviceStateFile) : BackupReminderStore {
    override fun read(): BackupReminderAnswers {
        val parts = file.read()?.split(';') ?: return BackupReminderAnswers()
        val after = parts.getOrNull(1)?.toLongOrNull() ?: return BackupReminderAnswers()
        return BackupReminderAnswers(never = parts[0] == "1", afterMillis = after)
    }

    override fun write(answers: BackupReminderAnswers): Boolean =
        file.write("${if (answers.never) "1" else "0"};${answers.afterMillis}")
}

enum class BackupReminderAction { SET_UP, TURN_ON, SET_UP_AGAIN }

/**
 * What the Notebook tab's backup reminder offers while automatic backups are not on, or null to hide it: after Never,
 * during a Not now (a snooze reaching past [snoozeMillis] from now, left by a clock set back, counts as run out),
 * while backups are on, and while their state is not read yet or cannot be read, unless it needs a new setup.
 */
fun backupReminderFor(
    status: AutoBackupStatus,
    never: Boolean,
    afterMillis: Long,
    nowMillis: Long,
    snoozeMillis: Long = BackupReminder.SNOOZE.inWholeMilliseconds,
): BackupReminderAction? {
    if (never || (nowMillis < afterMillis && afterMillis - nowMillis <= snoozeMillis)) return null
    return when {
        status.setUpAgain -> BackupReminderAction.SET_UP_AGAIN
        !status.loaded || status.unreadable -> null
        status.setUp && status.enabled -> null
        status.setUp -> BackupReminderAction.TURN_ON
        else -> BackupReminderAction.SET_UP
    }
}

class BackupReminder(
    private val store: BackupReminderStore,
    private val controls: AutoBackupControls,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + io),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val answers = MutableStateFlow<BackupReminderAnswers?>(null)
    private val writes = Mutex()

    // Read by the first collector off the main thread; an answer given before then wins over the read.
    private val loaded: Flow<BackupReminderAnswers> = flow {
        if (answers.value == null) answers.compareAndSet(null, withContext(io) { store.read() })
        emitAll(answers.filterNotNull())
    }

    /** Takes the time at each change, at each new collector, and once more when a Not now's time is reached while collected. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val action: Flow<BackupReminderAction?> = combine(controls.status, loaded) { status, a -> status to a }
        .transformLatest { (status, a) ->
            while (true) {
                val now = clock()
                emit(backupReminderFor(status, a.never, a.afterMillis, now))
                val wait = a.afterMillis - now
                if (a.never || wait <= 0 || wait > SNOOZE.inWholeMilliseconds) break
                sleep(wait)
            }
        }
        .distinctUntilChanged()

    /** Also undoes an earlier Never: a Not now asks to be reminded. */
    fun notNow() = save(BackupReminderAnswers(never = false, afterMillis = clock() + SNOOZE.inWholeMilliseconds))

    fun never() = save(BackupReminderAnswers(never = true, afterMillis = answers.value?.afterMillis ?: 0L))

    // Each write saves the answers current at its turn, so writes finishing out of order still leave the latest.
    private fun save(new: BackupReminderAnswers) {
        answers.value = new
        scope.launch { writes.withLock { answers.value?.let(store::write) } }
    }

    fun act(action: BackupReminderAction) = when (action) {
        BackupReminderAction.SET_UP -> controls.setUp()
        BackupReminderAction.TURN_ON -> controls.setEnabled(true)
        BackupReminderAction.SET_UP_AGAIN -> controls.setUpAgain()
    }

    companion object {
        val SNOOZE = 7.days
    }
}
