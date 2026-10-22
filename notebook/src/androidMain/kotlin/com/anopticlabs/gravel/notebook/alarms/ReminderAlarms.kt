package com.anopticlabs.gravel.notebook.alarms

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.reminders.BootRearm
import com.anopticlabs.gravel.notebook.reminders.FiredReminderPlan
import com.anopticlabs.gravel.notebook.reminders.OccurrenceStatus
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings
import com.anopticlabs.gravel.notebook.reminders.SnoozeRearm
import com.anopticlabs.gravel.notebook.reminders.SoftSnoozePlan
import com.anopticlabs.gravel.notebook.reminders.isResolved
import com.anopticlabs.gravel.notebook.reminders.isSnoozePendingDelivery
import com.anopticlabs.gravel.notebook.reminders.latestDueByReminder
import com.anopticlabs.gravel.notebook.reminders.nextLapseMillis
import com.anopticlabs.gravel.notebook.reminders.notificationsToClear
import com.anopticlabs.gravel.notebook.reminders.planBootRearm
import com.anopticlabs.gravel.notebook.reminders.planFiredReminder
import com.anopticlabs.gravel.notebook.reminders.planSnoozeRearm
import com.anopticlabs.gravel.notebook.reminders.planSoftSnooze
import com.anopticlabs.gravel.notebook.reminders.retentionCutoffMillis
import com.anopticlabs.gravel.notebook.reminders.statusFor
import com.anopticlabs.gravel.notebook.reminders.statusIn
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone

/** What [ReminderAlarms.complete], [ReminderAlarms.cancel] or [ReminderAlarms.deleteOccurrence] did. */
enum class Resolution {
    RECORDED,

    /** The reminder moved past the occurrence, but the record of it could not be written. */
    MOVED_ONLY,

    UNCHANGED,
}

/**
 * What happens between the stores, the alarms and the notifications when a reminder fires, the
 * user acts on an occurrence, or the alarms have to be re-armed. A fire logs its occurrence, then
 * moves the item under the file's lock and only while it still sits on that occurrence; a failed
 * write of the reminders ends the path with nothing newly armed, to be re-armed from disk by the
 * next [rearmAll]. This class changes a stored reminder, with any alarm call that goes with the
 * change, under [arming]; a new reminder is armed by its caller once it is stored ([arm]).
 */
class ReminderAlarms(
    private val reminders: ReminderStore,
    private val events: ReminderEventStore,
    private val scheduler: ReminderAlarmScheduler,
    private val notifier: ReminderNotifier,
    private val settings: ReminderSettings,
    private val refresh: ReminderRefreshSignal,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
) {

    private val arming = Mutex()

    /** Arms [item], which the caller has already stored; a re-arm replaces the reminder's fire alarm. */
    fun arm(item: ReminderItem) = scheduler.scheduleFire(item)

    /**
     * Applies [transform] to stored reminder [id] and arms the result; null when no reminder has the id. An
     * unmarked result takes down the set notice, also when the alarm call fails.
     */
    suspend fun edit(id: Int, transform: (ReminderItem) -> ReminderItem): ReminderItem? = arming.withLock {
        val stored = reminders.update(id, transform) ?: return@withLock null
        try {
            scheduler.scheduleFire(stored)
        } finally {
            if (!stored.timeToCheck) takeDownSetNotice(id)
        }
        stored
    }

    /**
     * Clears reminder [id]'s pending snoozes, removes it with its fire alarm, then takes down its
     * notifications ([takeDown]); false when a write failed, which leaves the reminder stored.
     */
    suspend fun delete(id: Int): Boolean {
        try {
            for (key in events.clearPendingSnoozes(id, now(), zone())) scheduler.cancelSnooze(key)
            arming.withLock {
                reminders.remove(id)
                scheduler.cancelFire(id)
            }
        } catch (e: Exception) {
            logger.w(e) { "reminder $id not deleted" }
            refresh.emit()
            return false
        }
        takeDownNotifications(id)
        refresh.emit()
        return true
    }

    /** Drops [ids]' fire alarms and [snoozed]'s snooze alarms, and takes down the notifications of [ids] and [occurrences] ([takeDown]); [rearmAll] sets the current ones again. */
    fun disarmFormer(ids: Collection<Int>, occurrences: Collection<ReminderEventKey>, snoozed: Collection<ReminderEventKey>) {
        for (id in ids) {
            scheduler.cancelFire(id)
            takeDownNotifications(id)
        }
        for (key in occurrences) takeDown("notification of reminder ${key.reminderId}") { notifier.cancelFire(key) }
        for (key in snoozed) scheduler.cancelSnooze(key)
    }

    /** Drops reminder [id]'s fire alarm, notifications and pending snoozes, for a caller that removes the item itself. */
    suspend fun disarm(id: Int) {
        scheduler.cancelFire(id)
        notifier.cancelFires(id)
        notifier.cancelSetNotice(id)
        try {
            for (key in events.clearPendingSnoozes(id, now(), zone())) scheduler.cancelSnooze(key)
            refresh.emit()
        } catch (e: Exception) {
            logger.w(e) { "pending snoozes of reminder $id not cleared" }
        }
    }

    fun postFire(key: ReminderEventKey, message: String): Boolean = notifier.postFire(key, message)

    suspend fun recordFire(key: ReminderEventKey, message: String) {
        val logged = try {
            events.appendFire(ReminderEvent(key.reminderId, key.originalFireMillis, message, firedAt = now()))
            true
        } catch (e: Exception) {
            logger.w(e) { "fire of reminder ${key.reminderId} not logged" }
            false
        }
        settle(key, logged)
        takeDownSetNotice(key.reminderId)
        closeMissed(key)
        sweepLapsed()
        refresh.emit()
    }

    /** Takes down the notifications of occurrences that turned MISSED, then sets the sweep for the next cutoff. */
    fun sweepLapsed() {
        val all = try {
            events.readAll(strict = true)
        } catch (e: Exception) {
            logger.w(e) { "event log unreadable; lapsed notifications left up" }
            return
        }
        for (key in notificationsToClear(notifier.postedFires(), all, now(), zone())) notifier.cancelFire(key)
        armLapseSweep(all)
    }

    /** [atMillis] is when it was done, now unless the caller knows better. */
    suspend fun complete(key: ReminderEventKey, message: String, atMillis: Long? = null): Resolution =
        resolve(key, atMillis, { at -> events.markCompleted(key, at) }, { at -> events.completeAhead(key, message, at) })

    suspend fun cancel(key: ReminderEventKey, message: String): Resolution =
        resolve(key, null, { at -> events.markCancelled(key, at) }, { at -> events.cancelAhead(key, message, at) })

    /**
     * Deletes one occurrence for good: a reminder still sitting on it moves past it first (a
     * repeating one to its next time, a one-shot removed), so no re-arm logs it again, then its
     * record goes ([ReminderEventStore.removeOccurrence]). UNCHANGED when the move failed;
     * MOVED_ONLY when the log could not be written after it. The notification and snooze alarm go
     * with either write.
     */
    suspend fun deleteOccurrence(key: ReminderEventKey): Resolution {
        val settled = settle(key)
        if (settled == Settle.FAILED) return Resolution.UNCHANGED
        val removed = try {
            events.removeOccurrence(key, now(), zone())
            true
        } catch (e: Exception) {
            logger.w(e) { "occurrence of reminder ${key.reminderId} not deleted" }
            false
        }
        if (!removed && settled != Settle.MOVED) return Resolution.UNCHANGED
        notifier.cancelFire(key)
        scheduler.cancelSnooze(key)
        sweepLapsed()
        refresh.emit()
        return if (removed) Resolution.RECORDED else Resolution.MOVED_ONLY
    }

    /** The calendar's switch of an occurrence at rest ([ReminderEventStore.switchStatus]); false when it did not apply or was not written. */
    suspend fun switchStatus(key: ReminderEventKey, completed: Boolean): Boolean {
        val switched = try {
            events.switchStatus(key, completed, now(), zone())
        } catch (e: Exception) {
            logger.w(e) { "occurrence of reminder ${key.reminderId} not switched" }
            false
        }
        if (!switched) return false
        notifier.cancelFire(key)
        scheduler.cancelSnooze(key)
        scheduleLapseSweep()
        refresh.emit()
        return true
    }

    /** The swipe: an unreadable log still arms the re-fire, uncounted, rather than lose the occurrence. */
    suspend fun softSnooze(key: ReminderEventKey, message: String) {
        val all = try {
            events.readAll(strict = true)
        } catch (e: Exception) {
            logger.w(e) { "swipe on reminder ${key.reminderId} armed unrecorded: event log unreadable" }
            val plan = planSoftSnooze(ReminderEvent(key.reminderId, key.originalFireMillis, message, now()), now(), settings.swipeSnoozeMinutes)
            if (plan is SoftSnoozePlan.Snooze) scheduler.scheduleSnooze(key, message, plan.fireAtMillis)
            return
        }
        val event = all.firstOrNull { it.key == key }
        val missed = event != null && statusIn(all, event, now(), zone()) == OccurrenceStatus.MISSED
        when (val plan = planSoftSnooze(event, now(), settings.swipeSnoozeMinutes, missed)) {
            is SoftSnoozePlan.Snooze -> snooze(key, message, plan.fireAtMillis, bumpChain = true)
            SoftSnoozePlan.ChainCapped -> {
                logger.i { "reminder ${key.reminderId} reached the snooze chain cap" }
                refresh.emit()
            }
            SoftSnoozePlan.NoOccurrence, SoftSnoozePlan.Resolved, SoftSnoozePlan.Missed ->
                logger.i { "swipe on reminder ${key.reminderId} dropped: $plan" }
        }
    }

    /**
     * The picker's choice: the chain cap does not apply and the snooze does not count toward it.
     * The notification goes only once the snooze is recorded and armed, which is what it returns.
     */
    suspend fun snoozeConfirmed(key: ReminderEventKey, message: String, offsetMillis: Long): Boolean {
        if (!snooze(key, message, now() + offsetMillis, bumpChain = false)) return false
        notifier.cancelFire(key)
        return true
    }

    suspend fun snoozedFire(key: ReminderEventKey, message: String) {
        val all = try {
            events.readAll(strict = true)
        } catch (e: Exception) {
            // The alarm was set for a snooze of this occurrence; the delivery mark waits for a readable log.
            logger.w(e) { "re-fire of reminder ${key.reminderId} posted without its event: log unreadable" }
            notifier.postFire(key, message)
            return
        }
        val logged = all.firstOrNull { it.key == key }
        val pending = logged?.latestSnoozeFireAt?.takeIf { isSnoozePendingDelivery(it, logged.snoozeDeliveredAt) }
        // No snooze awaiting delivery in the log, for example after it was set aside or restored: the re-fire counts as a snooze to now.
        val refireAt = pending ?: now()
        val event = logged?.copy(latestSnoozeFireAt = refireAt)
            ?: ReminderEvent(key.reminderId, key.originalFireMillis, message, firedAt = key.originalFireMillis, latestSnoozeFireAt = refireAt)
        if (event.isResolved || statusIn(all, event, now(), zone()) == OccurrenceStatus.MISSED) {
            logger.i { "re-fire of reminder ${key.reminderId} dropped: resolved or missed" }
            return
        }
        val posted = notifier.postFire(key, message)
        try {
            when {
                logged == null -> events.appendFire(event.copy(snoozeDeliveredAt = refireAt.takeIf { posted }))
                pending == null -> events.recordSnooze(key, refireAt, bumpChain = false, now(), zone())
            }
            if (logged != null && posted) events.markSnoozeDelivered(key, refireAt)
        } catch (e: Exception) {
            logger.w(e) { "re-fire of reminder ${key.reminderId} not logged" }
        }
        scheduleLapseSweep()
        refresh.emit()
    }

    /** Re-arms everything from the stores; idempotent, so any caller may run it again. */
    suspend fun rearmAll() {
        // Listed before the store is read, so a notice posted for a reminder made during the re-arm is not swept.
        val posted = try {
            notifier.postedSetNotices()
        } catch (e: Exception) {
            logger.w(e) { "set notices not listed; none swept" }
            null
        }
        val read = rearmReminders()
        rearmSnoozes()
        pruneHistory()
        sweepLapsed()
        if (posted != null && read != null) sweepSetNotices(posted, read)
        refresh.emit()
    }

    /**
     * Takes down the [posted] set notices whose reminder is gone from [read] or whose form does not match its
     * mark there, as after a reset or a restore; one the re-arm dropped meanwhile was taken down when it was dropped,
     * or stays up until the next re-arm when that cancel failed.
     */
    private fun sweepSetNotices(posted: List<PostedSetNotice>, read: List<ReminderItem>) {
        val marks = read.associate { it.id to it.timeToCheck }
        for (notice in posted) if (marks[notice.reminderId] != notice.toCheck) takeDownSetNotice(notice.reminderId)
    }

    /**
     * An occurrence the item still sits on is moved past first, so a fire cannot show it again once
     * it is stamped. When that move fails, only an occurrence the log already holds is stamped
     * ([mark]); one it does not hold may still fire, so it gets no event ([ahead]). The
     * notification and the snooze go once the stamp has landed.
     */
    private suspend fun resolve(key: ReminderEventKey, atMillis: Long?, mark: suspend (Long) -> Boolean, ahead: suspend (Long) -> Unit): Resolution {
        val settled = settle(key)
        val stamped = try {
            val at = atMillis ?: now()
            mark(at) || (settled != Settle.FAILED && run { ahead(at); true })
        } catch (e: Exception) {
            logger.w(e) { "occurrence of reminder ${key.reminderId} not stamped" }
            false
        }
        if (stamped) {
            notifier.cancelFire(key)
            scheduler.cancelSnooze(key)
            scheduleLapseSweep()
            logger.i { "occurrence of reminder ${key.reminderId} resolved" }
        }
        if (stamped || settled == Settle.MOVED) refresh.emit()
        return when {
            stamped -> Resolution.RECORDED
            settled == Settle.MOVED -> Resolution.MOVED_ONLY
            else -> Resolution.UNCHANGED
        }
    }

    private suspend fun snooze(key: ReminderEventKey, message: String, fireAtMillis: Long, bumpChain: Boolean): Boolean {
        try {
            val all = events.readAll(strict = true)
            val event = all.firstOrNull { it.key == key }
            if (event != null && statusIn(all, event, now(), zone()) == OccurrenceStatus.MISSED) {
                logger.i { "snooze of reminder ${key.reminderId} dropped: missed" }
                return false
            }
            if (events.recordSnooze(key, fireAtMillis, bumpChain, now(), zone()) == null) {
                logger.i { "snooze of reminder ${key.reminderId} dropped: no open occurrence" }
                return false
            }
            scheduler.scheduleSnooze(key, message, fireAtMillis)
            refresh.emit()
            return true
        } catch (e: Exception) {
            logger.w(e) { "snooze of reminder ${key.reminderId} not recorded" }
            return false
        }
    }

    /**
     * After a fire, the open occurrences of its reminder up to [key] that are MISSED by the log lose
     * their notification and snooze alarm; [key] itself, when it landed already MISSED, goes to the notice.
     */
    private fun closeMissed(key: ReminderEventKey) {
        val all = try {
            events.readAll(strict = true)
        } catch (e: Exception) {
            logger.w(e) { "event log unreadable; missed occurrences of reminder ${key.reminderId} left up" }
            return
        }
        val latestDue = latestDueByReminder(all, now())[key.reminderId]
        for (event in all) {
            if (event.reminderId != key.reminderId || event.isResolved || event.originalFireMillis > key.originalFireMillis) continue
            if (statusFor(event, now(), zone(), latestDue) != OccurrenceStatus.MISSED) continue
            notifier.cancelFire(event.key)
            scheduler.cancelSnooze(event.key)
            if (event.key == key) notifier.postMissedWhileOff(listOf(event))
        }
    }

    private fun scheduleLapseSweep() {
        try {
            armLapseSweep(events.readAll(strict = true))
        } catch (e: Exception) {
            logger.w(e) { "event log unreadable; lapse sweep not set" }
        }
    }

    private fun armLapseSweep(all: List<ReminderEvent>) {
        val next = nextLapseMillis(all, now(), zone())
        if (next == null) scheduler.cancelLapseSweep() else scheduler.scheduleLapseSweep(next)
    }

    private enum class Settle { MOVED, LEFT, FAILED }

    /** Moves the item past [key] under the lock: a one-shot goes with its fire alarm unless its fire is not [logged], a recurring item's next fire is armed. */
    private suspend fun settle(key: ReminderEventKey, logged: Boolean = true): Settle = arming.withLock {
        var removed = false
        val settled = try {
            var plan: FiredReminderPlan = FiredReminderPlan.Stale
            reminders.update(key.reminderId) { current ->
                plan = planFiredReminder(current, key.originalFireMillis, now(), zone())
                    .let { if (it == FiredReminderPlan.Remove && !logged) FiredReminderPlan.Stale else it }
                when (val decided = plan) {
                    FiredReminderPlan.Stale -> current
                    FiredReminderPlan.Remove -> null
                    is FiredReminderPlan.Advance -> decided.updated
                }
            }
            when (val decided = plan) {
                FiredReminderPlan.Stale -> Settle.LEFT
                FiredReminderPlan.Remove -> Settle.MOVED.also {
                    removed = true
                    scheduler.cancelFire(key.reminderId)
                }
                is FiredReminderPlan.Advance -> Settle.MOVED.also { scheduler.scheduleFire(decided.updated) }
            }
        } catch (e: Exception) {
            logger.w(e) { "reminder ${key.reminderId} not settled; the next re-arm moves it on" }
            Settle.FAILED
        }
        if (removed) takeDownSetNotice(key.reminderId)
        settled
    }

    /** Runs [cancel] and logs a failure instead of throwing it; what it failed to take down stays up. */
    private inline fun takeDown(what: String, cancel: () -> Unit) {
        try {
            cancel()
        } catch (e: Exception) {
            logger.w(e) { "$what left up" }
        }
    }

    private fun takeDownSetNotice(id: Int) = takeDown("set notice of reminder $id") { notifier.cancelSetNotice(id) }

    private fun takeDownNotifications(id: Int) {
        takeDown("notifications of reminder $id") { notifier.cancelFires(id) }
        takeDownSetNotice(id)
    }

    /** Re-arms every reminder, logging the fires that passed unlogged, and shows what it logged; returns the reminders it read, or null when they could not be read. */
    private suspend fun rearmReminders(): List<ReminderItem>? {
        val all = try {
            reminders.readAll(strict = true)
        } catch (e: Exception) {
            logger.w(e) { "reminders unreadable; nothing re-armed" }
            return null
        }
        val log = try {
            events.readAll(strict = true)
        } catch (e: Exception) {
            logger.w(e) { "event log unreadable; past-due reminders count as missed" }
            emptyList()
        }
        val firedKeys = log.mapTo(HashSet()) { it.key }
        val logged = mutableListOf<ReminderEvent>()
        val at = now()
        for (item in all) {
            try {
                logged += arming.withLock { rearm(item, firedKeys, at) }
            } catch (e: Exception) {
                logger.w(e) { "reminder ${item.id} not re-armed" }
            }
        }
        showPassed(log + logged, logged)
        return all
    }

    /**
     * Logs [item]'s passed fires, newest first so a full log keeps the latest, then arms, drops or
     * advances it per [planBootRearm]; returns what it logged, also when the item's write fails.
     */
    private suspend fun rearm(item: ReminderItem, firedKeys: Set<ReminderEventKey>, at: Long): List<ReminderEvent> {
        val plan = planBootRearm(listOf(item), firedKeys, at, zone(), retentionCutoffMillis(at, settings.historyRetentionDays)).single()
        val passed = plan.missedFires.map { ReminderEvent(item.id, it, item.message, firedAt = it) }
        val added = if (passed.isEmpty()) {
            emptySet()
        } else {
            val result = events.addAllSkippingExistingKeys(passed.asReversed())
            if (result.dropped > 0) logger.w { "event log full; ${result.dropped} passed fires of reminder ${item.id} not logged" }
            result.added.toSet()
        }
        try {
            when (plan) {
                is BootRearm.Arm -> {
                    // The sweep read it before the lock: an edit, delete or fire since has set or dropped the alarm; one that cannot be read again is armed as read.
                    val stored = try {
                        reminders.update(plan.item.id) { it }
                    } catch (e: Exception) {
                        logger.w(e) { "reminder ${item.id} not read again; armed as the sweep read it" }
                        plan.item
                    }
                    if (stored == plan.item) scheduler.scheduleFire(plan.item)
                }
                is BootRearm.Drop -> if (reminders.update(plan.item.id) { current -> if (current == plan.item) null else current } == null) {
                    notifier.cancelSetNotice(plan.item.id)
                }
                is BootRearm.Advance -> {
                    val stored = reminders.update(plan.item.id) { current -> if (current == plan.item) plan.updated else current }
                    if (stored == plan.updated) scheduler.scheduleFire(stored)
                }
            }
        } catch (e: Exception) {
            logger.w(e) { "reminder ${item.id} not moved; the next re-arm retries" }
        }
        return passed.filter { it.key in added }
    }

    /** Of [occurrences], one still due is posted as a fire and the MISSED ones share one notice; [log] decides which. */
    private fun showPassed(log: List<ReminderEvent>, occurrences: List<ReminderEvent>) {
        if (occurrences.isEmpty()) return
        val at = now()
        val latestDue = latestDueByReminder(log, at)
        val (due, missed) = occurrences.partition { statusFor(it, at, zone(), latestDue[it.reminderId]) == OccurrenceStatus.OVERDUE }
        for (event in due) notifier.postFire(event.key, event.message)
        notifier.postMissedWhileOff(missed)
    }

    private suspend fun rearmSnoozes() {
        val all = try {
            events.readAll(strict = true)
        } catch (e: Exception) {
            logger.w(e) { "event log unreadable; no snooze re-armed" }
            return
        }
        val at = now()
        val latestDue = latestDueByReminder(all, at)
        for (event in all) {
            val missed = !event.isResolved && statusFor(event, at, zone(), latestDue[event.reminderId]) == OccurrenceStatus.MISSED
            try {
                when (val plan = planSnoozeRearm(event, now(), missed)) {
                    SnoozeRearm.Skip -> Unit
                    is SnoozeRearm.Reschedule -> scheduler.scheduleSnooze(event.key, event.message, plan.fireAtMillis)
                    is SnoozeRearm.RetroPost -> if (notifier.postFire(event.key, event.message)) {
                        events.markSnoozeDelivered(event.key, plan.fireAtMillis)
                    }
                }
            } catch (e: Exception) {
                logger.w(e) { "snooze of reminder ${event.reminderId} not re-armed" }
            }
        }
    }

    private suspend fun pruneHistory() {
        val cutoff = retentionCutoffMillis(now(), settings.historyRetentionDays) ?: return
        try {
            val pruned = events.pruneBefore(cutoff)
            if (pruned > 0) logger.i { "pruned $pruned reminder events" }
        } catch (e: Exception) {
            logger.w(e) { "reminder events not pruned" }
        }
    }

    private companion object {
        val logger = Logger.withTag("ReminderAlarms")
    }
}
