package com.anopticlabs.gravel.notebook.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.alarms.runAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/** Without a confirmed backup for this long, a check asks for a run, and warns [STALE_GRACE_MILLIS] later. */
const val STALE_AFTER_MILLIS = DAY_MILLIS
const val STALE_GRACE_MILLIS = HOUR_MILLIS

/** How often the warning comes back while no backup is confirmed. */
const val STALE_REPEAT_MILLIS = DAY_MILLIS

/** The gaps between recovery key reminder points: the first, the second and every later one; a first check restarts at the second, a later one at the last. */
val KEY_CHECK_GAPS_MILLIS = longArrayOf(14 * DAY_MILLIS, 91 * DAY_MILLIS, 182 * DAY_MILLIS)
const val KEY_NOT_NOW_MILLIS = 7 * DAY_MILLIS

/**
 * The points at which the user is reminded to check the recovery key, from the last check or,
 * without one, from when the key was made; null when no key is recorded.
 */
fun keyCheckPoints(k: KeyChecks): Sequence<Long>? {
    val checked = k.checkedAtMillis
    val anchor = checked ?: k.createdAtMillis ?: return null
    val first = if (checked != null) k.checks.coerceIn(1, KEY_CHECK_GAPS_MILLIS.lastIndex) else 0
    return generateSequence(first) { minOf(it + 1, KEY_CHECK_GAPS_MILLIS.lastIndex) }
        .runningFold(anchor) { at, gap -> at + KEY_CHECK_GAPS_MILLIS[gap] }
        .drop(1)
}

/** The latest point at or before [now] and the first one after it; an anchor later than [now] is taken as [now]. */
fun keyCheckPointsAround(k: KeyChecks, now: Long): Pair<Long?, Long?> {
    val points = keyCheckPoints(k.copy(createdAtMillis = k.createdAtMillis?.coerceAtMost(now), checkedAtMillis = k.checkedAtMillis?.coerceAtMost(now)))
        ?: return null to null
    var last: Long? = null
    for (p in points) {
        if (p > now) return last to p
        last = p
    }
    return last to null
}

/** What a check at one moment changes and does; [state] is the input with the check's fields updated. */
data class CheckPlan(
    val state: AutoBackupState,
    val warnStale: Boolean = false,
    val staleActive: Boolean = false,
    val remindKey: Boolean = false,
    val keyDue: Boolean = false,
    val runSoon: Boolean = false,
    val alarmAtMillis: Long? = null,
    /** The stretch has no stored mark and is past its grace by the backup's age; used by a check whose write fails. */
    val warnUnmarked: Boolean = false,
)

/**
 * One check of [input] at [now]. While on, a day without a confirmed backup asks for a run at once
 * and warns an hour later if none came, then once a day while none does. While set up, each key
 * check point posts one reminder, and Not now on a point posts one more a week later.
 */
fun planChecks(input: AutoBackupState, now: Long): CheckPlan {
    if (!input.setUp) return CheckPlan(input.copy(staleSinceMillis = null, staleWarnedAtMillis = null))
    // A time later than now, left by a clock since moved back, is taken as now and stored so.
    // key.createdAtMillis stays as setup wrote it: the record matches backups to the key by it.
    val s = input.copy(
        lastConfirmedAtMillis = input.lastConfirmedAtMillis?.coerceAtMost(now),
        staleSinceMillis = input.staleSinceMillis?.coerceAtMost(now),
        staleWarnedAtMillis = input.staleWarnedAtMillis?.coerceAtMost(now),
        key = input.key.copy(checkedAtMillis = input.key.checkedAtMillis?.coerceAtMost(now)),
    )
    val wakes = mutableListOf<Long>()
    var state = s
    var warn = false
    var staleActive = false
    var runSoon = false
    var warnUnmarked = false
    val last = s.lastConfirmedAtMillis
    if (s.enabled && last != null && now >= last + STALE_AFTER_MILLIS) {
        val staleAt = last + STALE_AFTER_MILLIS
        // Marks from before the last confirmed backup belong to an earlier stretch.
        val since = s.staleSinceMillis?.takeIf { it >= staleAt }
        val warned = s.staleWarnedAtMillis?.takeIf { it >= staleAt }
        when {
            since == null -> {
                state = state.copy(staleSinceMillis = now, staleWarnedAtMillis = null)
                runSoon = true
                wakes += now + STALE_GRACE_MILLIS
                warnUnmarked = now >= staleAt + STALE_GRACE_MILLIS
            }
            now < since + STALE_GRACE_MILLIS -> wakes += since + STALE_GRACE_MILLIS
            warned == null || now >= warned + STALE_REPEAT_MILLIS -> {
                state = state.copy(staleWarnedAtMillis = now)
                warn = true
                staleActive = true
                runSoon = true
                wakes += now + STALE_REPEAT_MILLIS
            }
            else -> {
                staleActive = true
                wakes += warned + STALE_REPEAT_MILLIS
            }
        }
    } else {
        state = state.copy(staleSinceMillis = null, staleWarnedAtMillis = null)
        if (s.enabled && last != null) wakes += last + STALE_AFTER_MILLIS
    }

    val (point, next) = keyCheckPointsAround(s.key, now)
    var remind = false
    if (point != null && point != s.key.noticedPointMillis) {
        state = state.copy(key = state.key.copy(noticedPointMillis = point))
        remind = true
    }
    val until = s.key.notNowUntilMillis
    if (point != null && s.key.notNowPointMillis == point && !s.key.notNowNoticed && until != null) {
        if (now >= until) {
            state = state.copy(key = state.key.copy(notNowNoticed = true))
            remind = true
        } else {
            wakes += until
        }
    }
    next?.let(wakes::add)
    return CheckPlan(state, warn, staleActive, remind, keyDue = point != null, runSoon = runSoon, alarmAtMillis = wakes.minOrNull(), warnUnmarked = warnUnmarked)
}

/** Wakes the app at a time to run [AutoBackupChecks.check] again, independent of the backup work. */
interface CheckAlarm {
    fun set(atMillis: Long)

    fun cancel()
}

/**
 * The checks of automatic backups that do not wait on their work: the warning when no backup was
 * confirmed for a day, and the recovery key reminders. Run at process start, when the app comes
 * to the front, at boot, when a reminder goes off, at the end of every automatic run, and by
 * [alarm].
 */
class AutoBackupChecks(
    private val store: AutoBackupStore,
    private val notices: AutoBackupNotices,
    private val scheduler: AutoBackupScheduler,
    private val alarm: CheckAlarm,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logger.w(e) { "check failed" } }),
) {
    private val mutex = Mutex()

    fun request() {
        scope.launch { check() }
    }

    suspend fun check() = mutex.withLock {
        val now = clock()
        var plan: CheckPlan? = null
        try {
            store.updateState { s ->
                planChecks(s, now).also {
                    plan = it
                    // Told before the write: a notice whose write then fails is told again by the next check.
                    tell(it, now)
                }.state
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "automatic backup checks skipped: ${e::class.simpleName}" }
            plan?.let { p ->
                if (p.runSoon) safely { scheduler.runSoon() }
                if (p.warnUnmarked) safely { notices.stale(p.state.lastConfirmedAtMillis ?: now) }
            }
            safely { alarm.set(now + RETRY_MILLIS) }
            return@withLock
        }
        val p = plan ?: return@withLock
        if (p.runSoon) safely { scheduler.runSoon() }
        safely { p.alarmAtMillis?.let(alarm::set) ?: alarm.cancel() }
    }

    private fun tell(p: CheckPlan, now: Long) = safely {
        if (p.warnStale) notices.stale(p.state.lastConfirmedAtMillis ?: now) else if (!p.staleActive) notices.clearStale()
        if (p.remindKey) notices.keyCheckDue() else if (!p.keyDue) notices.clearKeyCheck()
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.w { "automatic backup check step failed: ${e::class.simpleName}" }
        }
    }

    /** Not now, once per reminder point: one more reminder a week later. */
    suspend fun notNow() {
        val now = clock()
        try {
            store.updateState { s ->
                val point = keyCheckPointsAround(s.key, now).first
                if (point == null || s.key.notNowPointMillis == point) {
                    s
                } else {
                    s.copy(key = s.key.copy(notNowPointMillis = point, notNowUntilMillis = now + KEY_NOT_NOW_MILLIS, notNowNoticed = false))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "not now not saved: ${e::class.simpleName}" }
        }
        safely { notices.clearKeyCheck() }
        check()
    }

    /** The user typed the right key: the next reminder counts from now. */
    suspend fun keyChecked() {
        val now = clock()
        try {
            store.updateState { s ->
                s.copy(key = s.key.copy(checkedAtMillis = now, checks = s.key.checks + 1, notNowPointMillis = null, notNowUntilMillis = null, notNowNoticed = false))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "key check not saved: ${e::class.simpleName}" }
        }
        check()
    }

    private companion object {
        val logger = Logger.withTag("AutoBackupChecks")

        /** When the state could not be read or written, the next check comes this much later. */
        const val RETRY_MILLIS = HOUR_MILLIS
    }
}

/** [CheckAlarm] as an inexact alarm, allowed while idle, to [AutoBackupCheckReceiver]. */
class AndroidCheckAlarm(private val context: Context) : CheckAlarm {
    private fun intent() = PendingIntent.getBroadcast(
        context, 0, Intent(context, AutoBackupCheckReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun set(atMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = intent()
        // Canceled first for the reason given at AndroidReminderAlarms.set.
        alarms.cancel(pending)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    override fun cancel() {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent())
    }
}

/** Runs the checks when [AndroidCheckAlarm] goes off; reached only by that pending intent, so unexported. */
class AutoBackupCheckReceiver : BroadcastReceiver(), KoinComponent {
    private val checks: AutoBackupChecks by inject()

    override fun onReceive(context: Context, intent: Intent) {
        runAsync("AutoBackupCheckReceiver") { checks.check() }
    }
}
