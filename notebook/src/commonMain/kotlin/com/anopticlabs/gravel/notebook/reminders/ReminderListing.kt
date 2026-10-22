package com.anopticlabs.gravel.notebook.reminders

import kotlinx.datetime.TimeZone

/**
 * The Reminders section's groups. [dueNow]: fired, unresolved occurrences still inside their
 * overdue window, latest fire first. [snoozed]: unresolved occurrences waiting on a snooze, soonest
 * first. [upcoming]: every stored reminder by its next fire, one that has passed included, except
 * one still sitting on an occurrence listed above. [wentOff]: the ids of upcoming reminders still
 * sitting on an occurrence the log holds, which fired and has not been moved past yet.
 */
data class ReminderListing(
    val dueNow: List<ReminderEvent>,
    val snoozed: List<ReminderEvent>,
    val upcoming: List<ReminderItem>,
    val wentOff: Set<Int> = emptySet(),
)

fun reminderListing(items: List<ReminderItem>, events: List<ReminderEvent>, nowMillis: Long, zone: TimeZone): ReminderListing {
    val dueNow = mutableListOf<ReminderEvent>()
    val snoozed = mutableListOf<ReminderEvent>()
    val latestDue = latestDueByReminder(events, nowMillis)
    for (event in events) {
        if (event.isResolved) continue
        when (statusFor(event, nowMillis, zone, latestDue[event.reminderId])) {
            OccurrenceStatus.PENDING -> if (event.latestSnoozeFireAt != null) snoozed += event else dueNow += event
            OccurrenceStatus.OVERDUE -> dueNow += event
            OccurrenceStatus.COMPLETED, OccurrenceStatus.MISSED -> Unit
        }
    }
    val listed = (dueNow + snoozed).mapTo(HashSet()) { it.key }
    val logged = events.mapTo(HashSet()) { it.key }
    val upcoming = items.filterNot { ReminderEventKey(it.id, it.remindAtMillis) in listed }.sortedBy { it.remindAtMillis }
    return ReminderListing(
        dueNow = dueNow.sortedByDescending { it.latestSnoozeFireAt ?: it.originalFireMillis },
        snoozed = snoozed.sortedBy { it.latestSnoozeFireAt },
        upcoming = upcoming,
        wentOff = upcoming.filter { ReminderEventKey(it.id, it.remindAtMillis) in logged }.mapTo(HashSet()) { it.id },
    )
}
