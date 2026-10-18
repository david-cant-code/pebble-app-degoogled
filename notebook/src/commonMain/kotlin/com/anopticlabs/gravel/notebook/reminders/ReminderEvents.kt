package com.anopticlabs.gravel.notebook.reminders

import kotlinx.datetime.TimeZone

/** Adds [event] unless its key is present; a replayed fire never resets an occurrence's stamps or chain. */
fun appendFire(events: List<ReminderEvent>, event: ReminderEvent): List<ReminderEvent> =
    if (events.any { it.key == event.key }) events else events + event

/** The first stamp stays; neither terminal stamp excludes the other. */
fun stampCompleted(events: List<ReminderEvent>, key: ReminderEventKey, completedAtMillis: Long): List<ReminderEvent> =
    events.map { if (it.key == key && it.completedAt == null) it.copy(completedAt = completedAtMillis) else it }

fun stampCancelled(events: List<ReminderEvent>, key: ReminderEventKey, cancelledAtMillis: Long): List<ReminderEvent> =
    events.map { if (it.key == key && it.cancelledAt == null) it.copy(cancelledAt = cancelledAtMillis) else it }

/** An absent key gets an event fired and completed at [nowMillis]; a present one is stamped as [stampCompleted]. */
fun completeAhead(events: List<ReminderEvent>, key: ReminderEventKey, message: String, nowMillis: Long): List<ReminderEvent> =
    if (events.none { it.key == key }) {
        events + ReminderEvent(key.reminderId, key.originalFireMillis, message, firedAt = nowMillis, completedAt = nowMillis)
    } else {
        stampCompleted(events, key, nowMillis)
    }

/** The cancel twin of [completeAhead]. */
fun cancelAhead(events: List<ReminderEvent>, key: ReminderEventKey, message: String, nowMillis: Long): List<ReminderEvent> =
    if (events.none { it.key == key }) {
        events + ReminderEvent(key.reminderId, key.originalFireMillis, message, firedAt = nowMillis, cancelledAt = nowMillis)
    } else {
        stampCancelled(events, key, nowMillis)
    }

/** The list and the updated event, or the list unchanged and null when there is no open occurrence under the key. */
data class SnoozeRecorded(val events: List<ReminderEvent>, val updated: ReminderEvent?)

val ReminderEvent.isResolved: Boolean get() = completedAt != null || cancelledAt != null

/**
 * Sets the pending re-fire time of an unresolved occurrence; [bumpChain] counts it toward the
 * passive-snooze cap, which the in-app picker does not consume.
 */
fun recordSnooze(
    events: List<ReminderEvent>,
    key: ReminderEventKey,
    nextFireAtMillis: Long,
    bumpChain: Boolean,
    nowMillis: Long,
    zone: TimeZone,
): SnoozeRecorded {
    val index = events.indexOfFirst { it.key == key }
    if (index < 0 || events[index].isResolved) return SnoozeRecorded(events, null)
    val current = events[index]
    val chain = if (bumpChain && current.snoozeChainCount < Int.MAX_VALUE) current.snoozeChainCount + 1 else current.snoozeChainCount
    val updated = current.keepingLateNightFire(nowMillis, zone).copy(latestSnoozeFireAt = nextFireAtMillis, snoozeChainCount = chain)
    return SnoozeRecorded(events.toMutableList().also { it[index] = updated }, updated)
}

/** The list with every open occurrence of [reminderId] no longer waiting for a re-fire, and the keys that were. */
fun clearPendingSnoozes(
    events: List<ReminderEvent>,
    reminderId: Int,
    nowMillis: Long,
    zone: TimeZone,
): Pair<List<ReminderEvent>, List<ReminderEventKey>> {
    val cleared = mutableListOf<ReminderEventKey>()
    val updated = events.map {
        if (it.reminderId == reminderId && !it.isResolved && it.latestSnoozeFireAt != null) {
            cleared += it.key
            it.withoutPendingSnooze(nowMillis, zone)
        } else {
            it
        }
    }
    return updated to cleared
}

/** [this] no longer waiting for a re-fire, a passed late-night one kept as a replaced one is. */
private fun ReminderEvent.withoutPendingSnooze(nowMillis: Long, zone: TimeZone): ReminderEvent =
    keepingLateNightFire(nowMillis, zone).copy(latestSnoozeFireAt = null)

/** Keeps a passed late-night re-fire in [ReminderEvent.lateNightFireAt] before [ReminderEvent.latestSnoozeFireAt] is replaced. */
private fun ReminderEvent.keepingLateNightFire(nowMillis: Long, zone: TimeZone): ReminderEvent {
    val refire = latestSnoozeFireAt ?: return this
    if (refire > nowMillis || !isLateNight(refire, zone)) return this
    return copy(lateNightFireAt = refire)
}

/**
 * A calendar edit of an occurrence whose status is at rest: to completed sets completedAt and
 * clears cancelledAt, to missed the reverse, and either marks it [ReminderEvent.setInCalendar].
 * Null, for no write, when [key] is not logged, not COMPLETED or MISSED, or already [completed]'s status.
 */
fun switchStatus(events: List<ReminderEvent>, key: ReminderEventKey, completed: Boolean, nowMillis: Long, zone: TimeZone): List<ReminderEvent>? {
    val index = events.indexOfFirst { it.key == key }
    if (index < 0) return null
    val event = events[index]
    val target = if (completed) OccurrenceStatus.COMPLETED else OccurrenceStatus.MISSED
    val status = statusIn(events, event, nowMillis, zone)
    if (status == target || (status != OccurrenceStatus.COMPLETED && status != OccurrenceStatus.MISSED)) return null
    val updated = if (completed) {
        event.copy(completedAt = nowMillis, cancelledAt = null, setInCalendar = true)
    } else {
        event.copy(completedAt = null, cancelledAt = nowMillis, setInCalendar = true)
    }
    return events.toMutableList().also { it[index] = updated }
}

/**
 * [events] without [key]. An earlier open occurrence of the same reminder that is MISSED only
 * because [key] came due is stamped missed at [key]'s due time, so the delete leaves it MISSED.
 */
fun removeOccurrence(events: List<ReminderEvent>, key: ReminderEventKey, nowMillis: Long, zone: TimeZone): List<ReminderEvent> {
    if (events.none { it.key == key }) return events
    val kept = events.filterNot { it.key == key }
    val dueBefore = latestDueByReminder(events, nowMillis)[key.reminderId]
    val dueAfter = latestDueByReminder(kept, nowMillis)[key.reminderId]
    return kept.map {
        val onlyByKey = it.reminderId == key.reminderId && !it.isResolved && it.originalFireMillis < key.originalFireMillis &&
            statusFor(it, nowMillis, zone, dueBefore) == OccurrenceStatus.MISSED &&
            statusFor(it, nowMillis, zone, dueAfter) != OccurrenceStatus.MISSED
        if (onlyByKey) it.copy(cancelledAt = key.originalFireMillis) else it
    }
}

/** The marker only moves forward and never onto a resolved occurrence. */
fun markSnoozeDelivered(events: List<ReminderEvent>, key: ReminderEventKey, deliveredAtMillis: Long): List<ReminderEvent> =
    events.map {
        val covered = it.snoozeDeliveredAt?.let { seen -> seen >= deliveredAtMillis } == true
        if (it.key == key && !it.isResolved && !covered) it.copy(snoozeDeliveredAt = deliveredAtMillis) else it
    }

/** The latest instant an event is shown or waited for: its scheduled fire, its actual fire, a pending snooze or its completion. */
val ReminderEvent.retentionMillis: Long
    get() = maxOf(originalFireMillis, firedAt, latestSnoozeFireAt ?: Long.MIN_VALUE, completedAt ?: Long.MIN_VALUE)

fun pruneEventsBefore(events: List<ReminderEvent>, cutoffMillis: Long): List<ReminderEvent> =
    events.filter { it.retentionMillis >= cutoffMillis }
