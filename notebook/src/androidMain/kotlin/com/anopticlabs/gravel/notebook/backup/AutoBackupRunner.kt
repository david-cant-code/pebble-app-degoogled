package com.anopticlabs.gravel.notebook.backup

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.ui.StorageProblem
import com.anopticlabs.gravel.notebook.ui.storageProblemOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.io.IOException
import java.io.InputStream
import java.nio.CharBuffer
import java.security.DigestInputStream
import java.security.MessageDigest

/** What one automatic run did. */
sealed interface AutoRunResult {
    /** Automatic backups are off or not set up. */
    data object Off : AutoRunResult

    /** Another backup run holds the Notebook. */
    data object Busy : AutoRunResult

    /** Nothing changed since the newest backup, and it read back whole. */
    data object Confirmed : AutoRunResult

    data class Saved(val entry: AutoBackupEntry, val created: CreatedBackup) : AutoRunResult

    /** [retry] when another try may pass and the user has not been told yet. */
    data class Failed(val problem: AutoBackupProblem, val retry: Boolean) : AutoRunResult
}

/** What a key check found; [newest] is how the newest backup opened with the typed key, and [backupsOn] whether a run will write a new one. */
sealed interface KeyCheckResult {
    data class Right(val newest: NewestOpened, val backupsOn: Boolean = true) : KeyCheckResult

    data object Wrong : KeyCheckResult

    /** Neither the newest backup nor the stored key could be read to compare with; [settings] when the state could not be read. */
    data class NotChecked(val problem: AutoBackupProblem, val settings: Boolean = false) : KeyCheckResult
}

enum class NewestOpened { OPENED, NONE, NOT_READ, DAMAGED, MISSING }

/** What the user is told by notification about automatic backup runs, and about the checks that do not wait on them. */
interface AutoBackupNotices {
    fun failed(problem: AutoBackupProblem)

    /** A backup was saved without notes the Notebook lists but could not read, notes too large to restore, or unlisted note files. */
    fun shortfall(created: CreatedBackup)

    fun clearShortfall()

    fun clearFailure()

    /** [after] holds far fewer notes ([notes]) or upcoming reminders ([remindersGone]) than [before], which thinning now keeps. */
    fun farFewer(before: AutoBackupEntry, after: AutoBackupEntry, notes: Boolean, remindersGone: Int)

    /** [count] kept backups no longer read back as written, or not at all for a week; they stay in the folder. [backupsOn]: a run will write a new one. */
    fun damaged(count: Int, backupsOn: Boolean)

    /** [count] kept backups are no longer in the folder. */
    fun missing(count: Int, backupsOn: Boolean)

    fun stale(lastConfirmedAtMillis: Long)

    fun clearStale()

    fun keyCheckDue()

    fun clearKeyCheck()
}

/**
 * Automatic backups into the folder the user granted, each sealed under the stored recovery key
 * and holding notes and reminders only. A run writes nothing when [NotebookBackup.fingerprint]
 * equals the newest backup's and that file reads back whole with the key; otherwise it builds
 * the archive in staging, writes it to a new `.part` document, reads it back through
 * [NotebookBackup.verify], renames it, hashes the renamed file against what was written, and
 * records it. Each step that touches the folder is first noted in [note], so the next run
 * removes what a run ended by its process left. Failures a retry cannot fix are reported at
 * once, others after [MAX_RETRIES] more failed runs in a row.
 */
class AutoBackupRunner<H>(
    private val backup: NotebookBackup,
    private val store: AutoBackupStore,
    private val folder: BackupFolderOps<H>,
    private val note: AutoBackupRunNote,
    private val gate: BackupGate,
    private val notices: AutoBackupNotices,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
    /** Moves with every write to the stores a backup holds; a run that saw it move asks [followUp] for another. */
    private val changeCount: () -> Long = { 0L },
    private val followUp: (slot: String) -> Unit = {},
) {
    private val _running = MutableStateFlow<String?>(null)

    /** The slot of the run under way, null between runs. */
    val running: StateFlow<String?> = _running.asStateFlow()

    /**
     * One run for [slot]. [force] writes a new backup even when nothing changed. An [interactive]
     * run waits for the gate and shows its failure in Settings at once, notifying one the record
     * could not take when the state reads but cannot be written; otherwise a held gate returns
     * [AutoRunResult.Busy]. [attempt] is how often the caller ran this before, used when the state
     * cannot be read or written to count in.
     */
    suspend fun run(slot: String, force: Boolean = false, interactive: Boolean = false, attempt: Int = 0): AutoRunResult =
        locked(slot, wait = interactive) {
            val changes = changeCount()
            runLocked(force, interactive, attempt).also { result ->
                // A change whose own request found this slot still running would otherwise wait for the periodic run.
                if (result != AutoRunResult.Off && changeCount() != changes) followUp(slot)
            }
        } ?: AutoRunResult.Busy

    /**
     * Setup's first backup into [target] with [key], which becomes the folder; the record starts
     * over unless [target] was it and the key stays. A new or restored key is stored and turns
     * backups on, a replaced one is stored, and a folder change uses the stored key made at
     * [storedKeyCreatedAtMillis]. An earlier folder's access is the caller's to release. Waits for the gate.
     */
    suspend fun firstBackup(target: String, key: CharArray, purpose: SetupPurpose, storedKeyCreatedAtMillis: Long? = null): AutoRunResult =
        locked(SLOT_SETUP, wait = true) { firstLocked(target, key, purpose, storedKeyCreatedAtMillis) }!!

    /**
     * Whether [typed], in any form [RecoveryKey.parse] reads, is the recovery key: it opens the
     * newest backup, or failing that equals the stored key. [typed] is the caller's to wipe.
     * Waits for the gate.
     */
    suspend fun checkKey(typed: CharArray): KeyCheckResult = gate.withLock { withContext(Dispatchers.IO) { checkKeyLocked(typed) } }

    private suspend fun <T> locked(slot: String, wait: Boolean, block: suspend () -> T): T? {
        if (wait) {
            return gate.withLock { inSlot(slot, block) }
        }
        if (!gate.tryLock()) return null
        try {
            return inSlot(slot, block)
        } finally {
            gate.unlock()
        }
    }

    private suspend fun <T> inSlot(slot: String, block: suspend () -> T): T {
        _running.value = slot
        try {
            return withContext(Dispatchers.IO) { block() }
        } finally {
            _running.value = null
        }
    }

    private suspend fun runLocked(force: Boolean, interactive: Boolean, attempt: Int): AutoRunResult {
        var state = try {
            store.readState(strict = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "automatic backup state unreadable: ${e::class.simpleName}" }
            // Nothing can be recorded, so the caller's attempt count decides when the user hears of it.
            val problem = problemOf(e)
            val report = problem.permanent || interactive || attempt >= MAX_RETRIES
            if (report && !interactive) notices.failed(problem)
            return AutoRunResult.Failed(problem, retry = !report)
        }
        val target = state.folder ?: return AutoRunResult.Off
        if (!state.enabled) return AutoRunResult.Off
        val stored = try {
            store.readKey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(state.failure, problemOf(e), interactive, attempt)
        } ?: return fail(state.failure, AutoBackupProblem.KEY_MISSING, interactive, attempt)
        val key = stored.chars
        try {
            accessProblem(target)?.let { return fail(state.failure, it, interactive, attempt) }
            // A document an ended run left that cannot be removed now holds the run, so its note is not lost to this one's.
            if (!recover(target)) return fail(state.failure, AutoBackupProblem.NOT_SAVED, interactive, attempt)
            val fingerprint = try {
                state = forKey(state, stored.createdAtMillis)
                backup.fingerprint()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return fail(state.failure, problemOf(e), interactive, attempt)
            }
            val newest = state.backups.lastOrNull { !it.damaged }
            if (!force && !state.writeNext && newest != null && newest.fingerprint == fingerprint) {
                val read = readBack(newest, key)
                if (read == ReadBack.OK) {
                    try {
                        val now = clock()
                        store.updateState { s ->
                            val checked = s.backups.map { if (it.uri == newest.uri) it.copy(checkedAtMillis = now, unreadSinceMillis = null) else it }
                            s.copy(lastConfirmedAtMillis = now, failure = null, backups = checked)
                        }
                        notices.clearFailure()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return fail(state.failure, problemOf(e), interactive, attempt)
                    }
                    upkeep(target, key, newest)
                    return AutoRunResult.Confirmed
                }
                recordReadBacks(target, mapOf(newest to read))
            }
            val written = try {
                write(target, key, fingerprint, stored.createdAtMillis)
            } catch (e: FolderException) {
                return fail(state.failure, e.problem, interactive, attempt)
            }
            // The file is in the folder under its final name: record it, and keep the backup before a drop, even when the run is being stopped.
            val result = withContext(NonCancellable) {
                val drop = newest?.let { farFewer(it, written.entry) }
                try {
                    store.updateState { s ->
                        // Only the newest entry keeps its reminder ids, the next comparison's base.
                        val kept = s.backups.map { if (drop != null && it.uri == newest?.uri) it.copy(keep = true, upcomingIds = null) else it.copy(upcomingIds = null) }
                        // Told before the write: a notice whose write then fails comes again with the next run's comparison.
                        if (drop != null && newest != null) notices.farFewer(newest, written.entry, drop.first, drop.second)
                        s.copy(backups = kept + written.entry, lastConfirmedAtMillis = written.entry.createdAtMillis, failure = null, writeNext = false)
                    }
                    saved(written)
                } catch (e: Exception) {
                    logger.w { "automatic backup ${written.entry.name} saved but not recorded: ${e::class.simpleName}" }
                    fail(state.failure, AutoBackupProblem.NOT_RECORDED, interactive, attempt)
                } finally {
                    note.clear()
                }
            }
            if (result is AutoRunResult.Saved) upkeep(target, key, written.entry)
            return result
        } finally {
            key.fill('\u0000')
        }
    }

    /** Drops from the record backups sealed with a key other than the stored one, made at [stamp], and restarts its reminders; this completes an interrupted key change. */
    private suspend fun forKey(state: AutoBackupState, stamp: Long): AutoBackupState {
        if (state.key.createdAtMillis == stamp && state.backups.all { it.keyCreatedAtMillis == stamp }) return state
        return store.updateState { s ->
            s.copy(backups = s.backups.filter { it.keyCreatedAtMillis == stamp }, key = if (s.key.createdAtMillis == stamp) s.key else KeyChecks(createdAtMillis = stamp))
        }
    }

    /** Far fewer notes, and how many of [before]'s upcoming reminders are neither upcoming in [after] nor in the history log when far fewer remain; null when neither. */
    private suspend fun farFewer(before: AutoBackupEntry, after: AutoBackupEntry): Pair<Boolean, Int>? {
        val notes = isFarFewer(before.notes, after.notes)
        val earlier = before.upcomingIds?.toSet()
        val gone = if (earlier == null) {
            0
        } else {
            try {
                val stillThere = earlier.intersect(after.upcomingIds.orEmpty().toSet())
                val wentOff = backup.withHistory(earlier - stillThere)
                (earlier.size - stillThere.size - wentOff.size).takeIf { isFarFewer(earlier.size, earlier.size - it) } ?: 0
            } catch (e: Exception) {
                logger.w { "history unreadable for the drop check: ${e::class.simpleName}" }
                0
            }
        }
        return if (notes || gone > 0) notes to gone else null
    }

    /**
     * After a run that wrote or confirmed [newest]: reads back the newest earlier backup and those
     * unread for [RECHECK_MILLIS], then thins only when that one passed and every read had an answer.
     */
    private suspend fun upkeep(target: String, key: CharArray, newest: AutoBackupEntry) {
        try {
            val now = clock()
            val state = store.readState(strict = true)
            val earlier = state.backups.lastOrNull { !it.damaged && it.uri != newest.uri }
            val due = state.backups.filter {
                it.uri != newest.uri && (it == earlier || now - it.checkedAtMillis >= RECHECK_MILLIS || it.checkedAtMillis > now)
            }
            val results = due.associateWith { readBack(it, key) }
            val unanswered = recordReadBacks(target, results)?.unanswered ?: return
            if (earlier != null && results[earlier] != ReadBack.OK) return
            if (unanswered.isNotEmpty()) return
            store.updateState { s ->
                val thin = backupsToThin(s.backups, now, zone(), current = newest.uri)
                if (thin.isEmpty()) s else s.copy(backups = s.backups.filterNot { it in thin }, deleting = s.deleting + thin)
            }
            removeThinned(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "automatic backup upkeep stopped: ${e::class.simpleName}" }
        }
    }

    /**
     * Records [results]; a read without an answer for [UNREAD_AS_DAMAGED_MILLIS] counts as damage,
     * and new damage and gone files make the next run write. Null when nothing could be recorded.
     */
    private suspend fun recordReadBacks(target: String, results: Map<AutoBackupEntry, ReadBack>): Recorded? {
        if (results.isEmpty()) return Recorded(emptyMap(), emptySet())
        return try {
            recordReadBacksOrThrow(target, results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "automatic backup read-backs not recorded: ${e::class.simpleName}" }
            null
        }
    }

    /** What [recordReadBacks] recorded: a verdict per handle ([ReadBack.MISSING] only for a file gone from the folder) and the handles still without an answer. */
    private class Recorded(val verdicts: Map<String, ReadBack>, val unanswered: Set<String>)

    private suspend fun recordReadBacksOrThrow(target: String, results: Map<AutoBackupEntry, ReadBack>): Recorded {
        val now = clock()
        val missing = results.filterValues { it == ReadBack.MISSING }.keys
        val gone = if (missing.isEmpty() || folder.access(target) != FolderAccess.OK) {
            emptySet()
        } else {
            val names = folder.children(target).mapTo(HashSet()) { it.name }
            // A listing must show some other file of the record before an absence in it counts.
            val listed = store.readState(strict = true).backups.any { it !in missing && it.name in names }
            if (listed) missing.filter { it.name !in names }.mapTo(HashSet()) { it.uri } else emptySet()
        }
        // A read without an answer for long enough counts as damage, so it is told and stops holding thinning.
        val verdicts = results.mapKeys { it.key.uri }.mapValues { (uri, r) ->
            val unanswered = r == ReadBack.NOT_READ || (r == ReadBack.MISSING && uri !in gone)
            val since = results.keys.first { it.uri == uri }.unreadSinceMillis
            when {
                unanswered && since != null && now - since >= UNREAD_AS_DAMAGED_MILLIS -> ReadBack.DAMAGED
                unanswered -> ReadBack.NOT_READ
                else -> r
            }
        }
        val newlyDamaged = results.count { (e, _) -> verdicts[e.uri] == ReadBack.DAMAGED && !e.damaged }
        val unanswered = HashSet<String>()
        store.updateState { s ->
            // Told before the write: a notice whose write then fails is told again by the next read.
            if (newlyDamaged > 0) notices.damaged(newlyDamaged, s.enabled)
            if (gone.isNotEmpty()) notices.missing(gone.size, s.enabled)
            s.copy(
                backups = s.backups.mapNotNull { e ->
                    when (verdicts[e.uri]) {
                        ReadBack.OK -> e.copy(checkedAtMillis = now, damaged = false, unreadSinceMillis = null)
                        ReadBack.DAMAGED -> e.copy(checkedAtMillis = now, damaged = true, unreadSinceMillis = null)
                        ReadBack.MISSING -> null
                        ReadBack.NOT_READ -> e.copy(unreadSinceMillis = e.unreadSinceMillis?.coerceAtMost(now) ?: now).also { unanswered += e.uri }
                        null -> e
                    }
                },
                writeNext = s.writeNext || newlyDamaged > 0 || gone.isNotEmpty(),
            )
        }
        return Recorded(verdicts, unanswered)
    }

    /**
     * Deletes the files thinning took off the record while their name, and size where reported, are
     * as written; one the folder app does not describe is let go of once a listing lacks its name.
     */
    private suspend fun removeThinned(target: String) {
        val state = store.readState(strict = true)
        val pending = state.deleting
        if (pending.isEmpty()) return
        val names by lazy {
            if (folder.access(target) != FolderAccess.OK) null else folder.children(target).mapTo(HashSet()) { it.name }.takeIf { listed ->
                state.backups.any { it.name in listed }
            }
        }
        val settled = pending.filter { e ->
            val handle = folder.decode(e.uri)
            val info = folder.describe(handle)
            when {
                info == null -> names?.let { e.name !in it } == true
                info.displayName != e.name || (info.sizeBytes != null && info.sizeBytes != e.sizeBytes) -> true
                else -> folder.delete(handle)
            }
        }.mapTo(HashSet()) { it.uri }
        if (settled.isNotEmpty()) store.updateState { s -> s.copy(deleting = s.deleting.filterNot { it.uri in settled }) }
    }

    private suspend fun checkKeyLocked(typed: CharArray): KeyCheckResult {
        val parsed = RecoveryKey.parse(CharBuffer.wrap(typed)) ?: return KeyCheckResult.Wrong
        var stored: StoredKey? = null
        try {
            val state = try {
                store.readState(strict = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return KeyCheckResult.NotChecked(problemOf(e), settings = true)
            }
            var keyProblem = AutoBackupProblem.KEY_MISSING
            stored = try {
                store.readKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                keyProblem = problemOf(e)
                null
            }
            // The record may still list backups of a key replaced while automatic backups were off.
            val current = stored?.let { s ->
                try {
                    forKey(state, s.createdAtMillis)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state
                }
            } ?: state
            val newest = current.backups.lastOrNull { !it.damaged }
            val read = newest?.let { readBack(it, parsed) }
            val ofStoredKey = stored == null || newest?.keyCreatedAtMillis == stored.createdAtMillis
            if (read == ReadBack.OK && ofStoredKey) {
                current.folder?.let { recordReadBacks(it, mapOf(newest to read)) }
                return KeyCheckResult.Right(NewestOpened.OPENED, current.enabled)
            }
            val known = stored ?: return KeyCheckResult.NotChecked(keyProblem)
            if (!known.chars.contentEquals(parsed)) return KeyCheckResult.Wrong
            // The right key did not open it: a backup sealed with this key is damaged, gone, or not readable now.
            val verdict = if (newest != null && read != null && newest.keyCreatedAtMillis == known.createdAtMillis) {
                current.folder?.let { recordReadBacks(it, mapOf(newest to read)) }?.verdicts?.get(newest.uri) ?: ReadBack.NOT_READ
            } else {
                read
            }
            return KeyCheckResult.Right(
                when (verdict) {
                    null -> NewestOpened.NONE
                    ReadBack.OK -> NewestOpened.OPENED
                    ReadBack.NOT_READ -> NewestOpened.NOT_READ
                    ReadBack.MISSING -> NewestOpened.MISSING
                    ReadBack.DAMAGED -> NewestOpened.DAMAGED
                },
                current.enabled,
            )
        } finally {
            parsed.fill('\u0000')
            stored?.chars?.fill('\u0000')
        }
    }

    private suspend fun firstLocked(target: String, key: CharArray, purpose: SetupPurpose, storedKeyCreatedAtMillis: Long?): AutoRunResult {
        if (purpose != SetupPurpose.CHANGE_FOLDER) {
            val setUp = try {
                store.readState(strict = true).setUp
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return AutoRunResult.Failed(problemOf(e), retry = false)
            }
            // NEW_KEY and RESTORED_KEY are refused while set up, and REPLACE_KEY unless set up.
            if (purpose == SetupPurpose.REPLACE_KEY && !setUp) return AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = false)
            if (purpose != SetupPurpose.REPLACE_KEY && setUp) return AutoRunResult.Failed(AutoBackupProblem.ALREADY_SET_UP, retry = false)
        }
        val stamp = if (purpose == SetupPurpose.CHANGE_FOLDER) {
            storedKeyCreatedAtMillis ?: return AutoRunResult.Failed(AutoBackupProblem.KEY_MISSING, retry = false)
        } else {
            clock()
        }
        accessProblem(target)?.let { return AutoRunResult.Failed(it, retry = false) }
        if (!recover(target)) {
            logger.w { "an ended run's document could not be removed; setup goes on" }
            note.clear()
        }
        val written = try {
            write(target, key, backup.fingerprint(), stamp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FolderException) {
            return AutoRunResult.Failed(e.problem, retry = false)
        } catch (e: Exception) {
            return AutoRunResult.Failed(problemOf(e), retry = false)
        }
        return withContext(NonCancellable) {
            try {
                if (purpose != SetupPurpose.CHANGE_FOLDER) store.saveKey(key, stamp)
                val name = folder.folderName(target)
                val before = store.readState(strict = true).backups.lastOrNull { !it.damaged && it.keyCreatedAtMillis == stamp }
                val drop = before?.let { farFewer(it, written.entry) }
                store.updateState {
                    // Backups sealed with a replaced key stay in the folder, out of the record.
                    val keepsRecord = it.folder == target && purpose != SetupPurpose.REPLACE_KEY
                    val kept = it.backups.map { e -> if (drop != null && e.uri == before.uri) e.copy(keep = true, upcomingIds = null) else e.copy(upcomingIds = null) }
                    if (keepsRecord && drop != null) notices.farFewer(before, written.entry, drop.first, drop.second)
                    it.copy(
                        enabled = purpose == SetupPurpose.NEW_KEY || purpose == SetupPurpose.RESTORED_KEY || it.enabled,
                        folder = target,
                        folderName = name,
                        backups = if (keepsRecord) kept + written.entry else listOf(written.entry),
                        deleting = if (keepsRecord) it.deleting else emptyList(),
                        lastConfirmedAtMillis = written.entry.createdAtMillis,
                        failure = null,
                        writeNext = false,
                        staleSinceMillis = null,
                        staleWarnedAtMillis = null,
                        key = when (purpose) {
                            SetupPurpose.NEW_KEY, SetupPurpose.REPLACE_KEY -> KeyChecks(createdAtMillis = stamp)
                            // The user typed it to restore, which counts as a check.
                            SetupPurpose.RESTORED_KEY -> KeyChecks(createdAtMillis = stamp, checkedAtMillis = stamp, checks = 1)
                            SetupPurpose.CHANGE_FOLDER -> it.key
                        },
                    )
                }
                // Setup shows the gaps itself.
                saved(written, noticeGaps = false)
            } catch (e: Exception) {
                logger.w { "first automatic backup ${written.entry.name} saved but not recorded: ${e::class.simpleName}" }
                AutoRunResult.Failed(AutoBackupProblem.NOT_RECORDED, retry = false)
            } finally {
                note.clear()
            }
        }
    }

    private fun saved(written: Written, noticeGaps: Boolean = true): AutoRunResult.Saved {
        val c = written.created
        val gaps = c.notesMissing > 0 || c.notesTooLarge > 0 || c.notesUnlisted > 0
        if (!gaps) notices.clearShortfall() else if (noticeGaps) notices.shortfall(c)
        notices.clearFailure()
        return AutoRunResult.Saved(written.entry, c)
    }

    private fun accessProblem(target: String): AutoBackupProblem? = when (folder.access(target)) {
        FolderAccess.OK -> null
        FolderAccess.NO_ACCESS -> AutoBackupProblem.NO_ACCESS
        FolderAccess.GONE -> AutoBackupProblem.FOLDER_GONE
        FolderAccess.UNAVAILABLE -> AutoBackupProblem.NOT_SAVED
    }

    /** Records a failed run after [previous] and tells the user once it is permanent or the retries are spent, counted also by [attempt] when it cannot be recorded. */
    private suspend fun fail(previous: AutoBackupFailure?, problem: AutoBackupProblem, interactive: Boolean, attempt: Int): AutoRunResult.Failed {
        val tries = (previous?.tries ?: 0) + 1
        val byRecord = problem.permanent || interactive || tries > MAX_RETRIES
        val failure = AutoBackupFailure(problem, clock(), tries, reported = byRecord || previous?.reported == true)
        val recorded = try {
            store.updateState { it.copy(failure = failure) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "automatic backup failure not recorded: ${e::class.simpleName}" }
            false
        }
        val report = byRecord || (!recorded && attempt >= MAX_RETRIES)
        // Settings shows a watched run's failure from the record, so one that was not recorded is notified too.
        if (report && (!interactive || !recorded)) notices.failed(problem)
        return AutoRunResult.Failed(problem, retry = !report)
    }

    private class Written(val entry: AutoBackupEntry, val created: CreatedBackup)

    /** Writes, checks and names one backup in [target]; every failure throws [FolderException], with the run's document removed where it can be. */
    private suspend fun write(target: String, key: CharArray, fingerprint: String, keyStamp: Long): Written {
        val staged = try {
            backup.createAutomatic(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StagingSpaceException) {
            throw FolderException(AutoBackupProblem.NO_SPACE, e)
        } catch (e: StagingException) {
            throw FolderException(AutoBackupProblem.NOT_SAVED, e)
        } catch (e: Exception) {
            throw FolderException(problemOf(e), e)
        }
        // Taken again now the archive is read: a change in between, even one undone since, makes the next run write.
        val stable = try {
            backup.fingerprint() == fingerprint
        } catch (e: CancellationException) {
            staged.close()
            throw e
        } catch (e: Exception) {
            false
        }
        staged.use {
            val now = clock()
            val name = freeName(target, automaticBackupName(now, zone()))
            val partName = name + PARTIAL_SUFFIX
            if (!note.write(AutoRunStep.Creating(partName))) throw FolderException(AutoBackupProblem.NOT_SAVED)
            val part = try {
                folder.create(target, partName)
            } catch (e: FolderException) {
                note.clear()
                throw e
            }
            val partText = folder.encode(part)
            // A document that would not go keeps its note, so the next run's recovery tries again.
            fun abandon(handle: H, cause: Throwable? = null): Nothing {
                if (gone(handle)) note.clear()
                throw FolderException(AutoBackupProblem.NOT_SAVED, cause)
            }
            if (!note.write(AutoRunStep.Writing(partText))) abandon(part)
            val digest = MessageDigest.getInstance("SHA-256")
            val source = BackupSource(staged.staged.size) { DigestInputStream(staged.staged.openRead(), digest) }
            try {
                BackupPublisher.publish(folder, part, source) { backup.verify(it, key, staged) }
            } catch (e: Exception) {
                // The publisher tries to delete a document it wrote to; one it refused or never opened is still this run's.
                if (e is BackupPublishException && e.removed == true) {
                    note.clear()
                    throw FolderException(AutoBackupProblem.NOT_SAVED, e)
                }
                abandon(part, e)
            }
            val sha256 = digest.digest().toHex()
            if (!note.write(AutoRunStep.Renaming(partText, name))) abandon(part)
            val final = try {
                folder.rename(part, name)
            } catch (e: IOException) {
                abandon(part, e)
            }
            val read = try {
                folder.openRead(final).use(::hashOf)
            } catch (e: Exception) {
                abandon(final, e)
            }
            if (read.first != sha256 || read.second != staged.staged.size) abandon(final)
            val c = staged.created.counts
            val entry = AutoBackupEntry(
                uri = folder.encode(final),
                name = folder.describe(final)?.displayName ?: name,
                createdAtMillis = now,
                sizeBytes = read.second,
                sha256 = sha256,
                notes = c.notes,
                reminders = c.reminders,
                events = c.events,
                fingerprint = if (stable) fingerprint else "",
                keyCreatedAtMillis = keyStamp,
                checkedAtMillis = now,
                upcomingIds = staged.created.reminderIds,
            )
            return Written(entry, staged.created)
        }
    }

    private enum class ReadBack { OK, DAMAGED, MISSING, NOT_READ }

    /** Reads [entry]'s file whole with [key]: MISSING when it cannot be opened and the folder app no longer knows it, NOT_READ for any other failed read. */
    private fun readBack(entry: AutoBackupEntry, key: CharArray): ReadBack {
        val handle = folder.decode(entry.uri)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val checked = folder.openRead(handle).use { backup.check(DigestInputStream(it, digest), key) }
            val c = checked.counts
            if (digest.digest().toHex() == entry.sha256 && c.notes == entry.notes && c.reminders == entry.reminders && c.events == entry.events) {
                ReadBack.OK
            } else {
                ReadBack.DAMAGED
            }
        } catch (e: InvalidBackupException) {
            ReadBack.DAMAGED
        } catch (e: IOException) {
            if (folder.describe(handle) == null) ReadBack.MISSING else ReadBack.NOT_READ
        } catch (e: Exception) {
            logger.w { "automatic backup ${entry.name} not read back: ${e::class.simpleName}" }
            ReadBack.NOT_READ
        }
    }

    /**
     * Removes the document of a run its process ended, from the step [note] holds; true once
     * nothing is left to remove and the note is cleared. An empty document made under the noted
     * name is looked for in [target].
     */
    private fun recover(target: String): Boolean {
        val step = note.read() ?: return true
        val done = when (step) {
            is AutoRunStep.Creating -> {
                folder.children(target).filter { it.name == step.name && (it.sizeBytes ?: 0L) == 0L }.forEach { folder.delete(it.handle) }
                true
            }
            is AutoRunStep.Writing -> gone(folder.decode(step.handle))
            // Under its final name it is a checked backup, left in place; a folder app may keep its handle across the rename.
            is AutoRunStep.Renaming -> folder.decode(step.handle).let {
                folder.describe(it)?.displayName?.endsWith(PARTIAL_SUFFIX) != true || gone(it)
            }
        }
        if (done) note.clear()
        return done
    }

    private fun gone(handle: H): Boolean = folder.delete(handle) || folder.describe(handle) == null

    /** [name], or with `-1`, `-2` ... before its extension when the folder already holds it or its partial name. */
    private fun freeName(target: String, name: String): String {
        val taken = folder.children(target).mapTo(HashSet()) { it.name }
        val base = name.removeSuffix(".$BACKUP_EXTENSION")
        return generateSequence(0) { it + 1 }.map { if (it == 0) name else "$base-$it.$BACKUP_EXTENSION" }
            .first { it !in taken && it + PARTIAL_SUFFIX !in taken }
    }

    private fun problemOf(e: Throwable): AutoBackupProblem = autoBackupProblemOf(e)

    companion object {
        /** Failed runs after the first before the user is told of one a retry might fix. */
        const val MAX_RETRIES = 3

        const val SLOT_CHANGE = "change"
        const val SLOT_NEXT = "next"
        const val SLOT_PERIODIC = "periodic"
        const val SLOT_NOW = "now"
        const val SLOT_SETUP = "setup"
        const val SLOT_CHECK = "check"

        /** How long a kept backup goes before it is read back again. */
        const val RECHECK_MILLIS = 7 * 24 * 3_600_000L

        /** How long reads of a backup may go without an answer before it counts as damaged. */
        const val UNREAD_AS_DAMAGED_MILLIS = 7 * 24 * 3_600_000L
        private val logger = Logger.withTag("AutoBackupRunner")
    }
}

/** The failure a store or key read that threw stands for. */
internal fun autoBackupProblemOf(e: Throwable): AutoBackupProblem = when (storageProblemOf(e)) {
    StorageProblem.KEY_MISSING -> AutoBackupProblem.KEY_MISSING
    StorageProblem.DAMAGED -> AutoBackupProblem.DAMAGED
    StorageProblem.UNREADABLE -> AutoBackupProblem.NOT_READ
}

private fun hashOf(input: InputStream): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
        total += n
    }
    return digest.digest().toHex() to total
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
