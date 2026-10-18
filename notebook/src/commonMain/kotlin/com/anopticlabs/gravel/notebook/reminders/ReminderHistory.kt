package com.anopticlabs.gravel.notebook.reminders

import com.anopticlabs.gravel.notebook.storage.isStoredInstant
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Lifecycle of one occurrence: a fired one is OVERDUE until it lapses at [overdueLapseMillis] or a
 * later logged occurrence of its reminder comes due, then MISSED ([statusFor]).
 */
enum class OccurrenceStatus { PENDING, OVERDUE, COMPLETED, MISSED }

/** Which of a reminder's times a calendar row stands for, which decides what can be done with it. */
enum class CalendarRowKind {
    /** An occurrence in the event log. */
    LOGGED,

    /** A stored reminder's next time, not logged yet. */
    NEXT,

    /** A repeating reminder's time after its next one. */
    LATER,
}

/**
 * One row of a day: [occurrenceMillis] is the due time, [atMillis] the instant that puts the row
 * on its day (a completion's time or the due time); [event] is set for [CalendarRowKind.LOGGED].
 * [onStored]: a stored reminder still sits on this time, so acting on it moves that reminder on;
 * [repeats]: the stored reminder the row belongs to repeats.
 */
data class CalendarOccurrence(
    val reminderId: Int,
    val occurrenceMillis: Long,
    val message: String,
    val status: OccurrenceStatus,
    val kind: CalendarRowKind = CalendarRowKind.LOGGED,
    val atMillis: Long = occurrenceMillis,
    val event: ReminderEvent? = null,
    val repeats: Boolean = false,
    val onStored: Boolean = false,
) {
    val key: ReminderEventKey get() = ReminderEventKey(reminderId, occurrenceMillis)
}

/**
 * The rows of each day in [days], sorted by [CalendarOccurrence.atMillis]: every logged event on
 * the day [placedAt] gives it, with its [statusFor] status, but those whose [retentionMillis] is
 * before [retentionCutoffMillis]; every stored reminder's next time the log does not hold,
 * PENDING; and a repeating reminder's later times after now up to [laterUntilMillis], PENDING.
 */
fun buildCalendarOccurrences(
    events: List<ReminderEvent>,
    activeReminders: List<ReminderItem>,
    nowMillis: Long,
    zone: TimeZone,
    days: ClosedRange<LocalDate>,
    retentionCutoffMillis: Long? = null,
    laterUntilMillis: Long? = null,
): Map<LocalDate, List<CalendarOccurrence>> {
    val byDay = mutableMapOf<LocalDate, MutableList<CalendarOccurrence>>()
    fun add(day: LocalDate, row: CalendarOccurrence) {
        if (day in days) byDay.getOrPut(day) { mutableListOf() }.add(row)
    }
    // A row lands on its due day, its completion's day or the day before, so a day's margin either side keeps every one.
    val from = days.start.minus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
    val until = days.endInclusive.plus(2, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
    fun near(millis: Long?) = millis != null && millis in from until until
    val latestDue = latestDueByReminder(events, nowMillis)
    val storedOn = activeReminders.associateBy { ReminderEventKey(it.id, it.remindAtMillis) }
    for (event in events) {
        if (retentionCutoffMillis != null && event.retentionMillis < retentionCutoffMillis) continue
        if (!near(event.originalFireMillis) && !near(event.completedAt)) continue
        val status = statusFor(event, nowMillis, zone, latestDue[event.reminderId])
        val placed = placedAt(event, status, zone)
        val stored = storedOn[event.key]
        add(
            localDate(placed, zone),
            CalendarOccurrence(
                event.reminderId, event.originalFireMillis, event.message, status, CalendarRowKind.LOGGED, placed, event,
                repeats = stored?.recurrence != null, onStored = stored != null,
            ),
        )
    }
    val logged = events.mapTo(HashSet()) { it.key }
    for (reminder in activeReminders) {
        val repeats = reminder.recurrence != null
        fun row(at: Long, kind: CalendarRowKind) =
            CalendarOccurrence(reminder.id, at, reminder.message, OccurrenceStatus.PENDING, kind, repeats = repeats, onStored = kind == CalendarRowKind.NEXT)
        if (ReminderEventKey(reminder.id, reminder.remindAtMillis) !in logged) {
            add(localDate(reminder.remindAtMillis, zone), row(reminder.remindAtMillis, CalendarRowKind.NEXT))
        }
        val rule = reminder.recurrence?.takeIf { Recurrence.isValid(it) } ?: continue
        val end = minOf(laterUntilMillis ?: continue, until)
        var at = Recurrence.nextAfter(rule, maxOf(reminder.remindAtMillis, nowMillis), zone)
        while (at <= end && isStoredInstant(at)) {
            if (ReminderEventKey(reminder.id, at) !in logged) add(localDate(at, zone), row(at, CalendarRowKind.LATER))
            at = Recurrence.nextAfter(rule, at, zone)
        }
    }
    return byDay.mapValues { (_, list) -> list.sortedWith(compareBy({ it.atMillis }, { it.reminderId })) }
}

/** The end of the day one calendar month after today: later repeats are shown up to it. */
fun laterRepeatsUntil(nowMillis: Long, zone: TimeZone): Long =
    localDate(nowMillis, zone).plus(1, DateTimeUnit.MONTH).plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() - 1

/**
 * The first days of the first and last months the calendar pages to: back to the month of
 * [retentionCutoffMillis] (kept forever: the oldest logged time), forward to the month of
 * [laterUntilMillis] or of the furthest stored or logged time, whichever is later.
 */
fun calendarMonths(
    events: List<ReminderEvent>,
    activeReminders: List<ReminderItem>,
    nowMillis: Long,
    zone: TimeZone,
    retentionCutoffMillis: Long?,
    laterUntilMillis: Long,
): ClosedRange<LocalDate> {
    val oldest = retentionCutoffMillis ?: events.minOfOrNull { minOf(it.originalFireMillis, it.completedAt ?: Long.MAX_VALUE) } ?: nowMillis
    val furthest = maxOf(
        laterUntilMillis,
        activeReminders.maxOfOrNull { it.remindAtMillis } ?: Long.MIN_VALUE,
        events.maxOfOrNull { maxOf(it.originalFireMillis, it.completedAt ?: Long.MIN_VALUE) } ?: Long.MIN_VALUE,
    )
    return firstOfMonth(localDate(minOf(oldest, nowMillis), zone))..firstOfMonth(localDate(furthest, zone))
}

private fun localDate(millis: Long, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

/**
 * The instant that puts a logged occurrence on its day: a completion not set in the calendar
 * counts on its [completionDay] (one counted on the day before sorts last in it); everything
 * else, a status switched in the calendar included, on its due day.
 */
private fun placedAt(event: ReminderEvent, status: OccurrenceStatus, zone: TimeZone): Long {
    val completed = event.completedAt
    if (status != OccurrenceStatus.COMPLETED || completed == null || event.setInCalendar) return event.originalFireMillis
    val day = localDate(completed, zone)
    return if (completionDay(event, completed, zone) == day) completed else day.atStartOfDayIn(zone).toEpochMilliseconds() - 1
}

/**
 * The day a completion at [completedAtMillis] counts on: the day before when it is before 02:00
 * and the occurrence went off at or after 22:00 that day (its due time, its latest snooze or
 * [ReminderEvent.lateNightFireAt]); otherwise the completion's own day.
 */
internal fun completionDay(event: ReminderEvent, completedAtMillis: Long, zone: TimeZone): LocalDate {
    val done = Instant.fromEpochMilliseconds(completedAtMillis).toLocalDateTime(zone)
    if (done.time >= LocalTime(GRACE_HOURS, 0)) return done.date
    val dayBefore = done.date.minus(1, DateTimeUnit.DAY)
    val wentOffLate = listOfNotNull(event.originalFireMillis, event.latestSnoozeFireAt, event.lateNightFireAt)
        .any { localDate(it, zone) == dayBefore && isLateNight(it, zone) }
    return if (wentOffLate) dayBefore else done.date
}

/**
 * Precedence: completed, then canceled, then a later logged occurrence of the same reminder that has
 * come due ([latestDueMillis], from [latestDueByReminder]), then the latest fire (a pending snooze
 * counts) against now.
 */
internal fun statusFor(event: ReminderEvent, nowMillis: Long, zone: TimeZone, latestDueMillis: Long?): OccurrenceStatus {
    if (event.completedAt != null) return OccurrenceStatus.COMPLETED
    if (event.cancelledAt != null) return OccurrenceStatus.MISSED
    if (latestDueMillis != null && latestDueMillis > event.originalFireMillis) return OccurrenceStatus.MISSED
    val latestFire = event.latestSnoozeFireAt ?: event.originalFireMillis
    if (latestFire > nowMillis) return OccurrenceStatus.PENDING
    return if (nowMillis < overdueLapseMillis(latestFire, zone)) OccurrenceStatus.OVERDUE else OccurrenceStatus.MISSED
}

private const val GRACE_HOURS = 2

/** At or after 22:00 local, when a fire gets until 02:00 instead of midnight. */
internal fun isLateNight(millis: Long, zone: TimeZone): Boolean =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).time >= LocalTime(24 - GRACE_HOURS, 0)

/**
 * When a fired occurrence stops being overdue: the next local midnight, or 02:00 the next day
 * for a fire at or after 22:00 local. Both are wall-clock times, so a DST change between the
 * fire and midnight does not move the 22:00 boundary.
 */
internal fun overdueLapseMillis(latestFireMillis: Long, zone: TimeZone): Long {
    val fired = Instant.fromEpochMilliseconds(latestFireMillis).toLocalDateTime(zone)
    val nextDay = fired.date.plus(1, DateTimeUnit.DAY)
    val lapse = if (isLateNight(latestFireMillis, zone)) {
        nextDay.atTime(GRACE_HOURS, 0).toInstant(zone)
    } else {
        nextDay.atStartOfDayIn(zone)
    }
    return lapse.toEpochMilliseconds()
}

/** Per reminder, the latest logged occurrence whose scheduled time has come. */
internal fun latestDueByReminder(events: List<ReminderEvent>, nowMillis: Long): Map<Int, Long> {
    val latest = mutableMapOf<Int, Long>()
    for (event in events) {
        if (event.originalFireMillis > nowMillis) continue
        val seen = latest[event.reminderId]
        if (seen == null || event.originalFireMillis > seen) latest[event.reminderId] = event.originalFireMillis
    }
    return latest
}

/** [event]'s status, [events] being the whole log, so a later occurrence of its reminder counts. */
internal fun statusIn(events: List<ReminderEvent>, event: ReminderEvent, nowMillis: Long, zone: TimeZone): OccurrenceStatus =
    statusFor(event, nowMillis, zone, latestDueByReminder(events, nowMillis)[event.reminderId])

/**
 * When the next open occurrence turns MISSED: the earliest of their cutoffs and of the scheduled
 * times of later logged occurrences of their reminders still to come; null when none is open.
 */
internal fun nextLapseMillis(events: List<ReminderEvent>, nowMillis: Long, zone: TimeZone): Long? {
    val latestDue = latestDueByReminder(events, nowMillis)
    val open = events.filter { !it.isResolved && statusFor(it, nowMillis, zone, latestDue[it.reminderId]) != OccurrenceStatus.MISSED }
    val fires = events.groupBy({ it.reminderId }, { it.originalFireMillis })
    return open.minOfOrNull { event ->
        val later = fires.getValue(event.reminderId).filter { it > event.originalFireMillis && it > nowMillis }.minOrNull()
        minOf(overdueLapseMillis(event.latestSnoozeFireAt ?: event.originalFireMillis, zone), later ?: Long.MAX_VALUE)
    }
}

/**
 * The posted occurrence notifications to take down: those whose logged occurrence is completed or
 * MISSED. One the log does not hold yet stays, because a fire posts before it logs.
 */
internal fun notificationsToClear(
    posted: Collection<ReminderEventKey>,
    events: List<ReminderEvent>,
    nowMillis: Long,
    zone: TimeZone,
): List<ReminderEventKey> {
    val byKey = events.associateBy { it.key }
    val latestDue = latestDueByReminder(events, nowMillis)
    return posted.filter { key ->
        val event = byKey[key] ?: return@filter false
        statusFor(event, nowMillis, zone, latestDue[key.reminderId]).let { it == OccurrenceStatus.COMPLETED || it == OccurrenceStatus.MISSED }
    }
}

/** The fired, unacted occurrences still inside their lapse window, newest fire first. */
fun overdueOccurrences(events: List<ReminderEvent>, nowMillis: Long, zone: TimeZone): List<CalendarOccurrence> =
    events.filter { statusIn(events, it, nowMillis, zone) == OccurrenceStatus.OVERDUE }
        .map { CalendarOccurrence(it.reminderId, it.originalFireMillis, it.message, OccurrenceStatus.OVERDUE) }
        .sortedByDescending { it.occurrenceMillis }

/** A snooze re-fire is pending delivery until [snoozeDeliveredAt] has caught up with [latestSnoozeFireAt]. */
fun isSnoozePendingDelivery(latestSnoozeFireAt: Long?, snoozeDeliveredAt: Long?): Boolean {
    if (latestSnoozeFireAt == null) return false
    return snoozeDeliveredAt == null || snoozeDeliveredAt < latestSnoozeFireAt
}

data class DayStatusSummary(val pending: Int, val overdue: Int, val completed: Int, val missed: Int) {
    val total: Int get() = pending + overdue + completed + missed
}

fun summarizeDay(occurrences: List<CalendarOccurrence>): DayStatusSummary {
    var p = 0; var o = 0; var c = 0; var m = 0
    for (occ in occurrences) when (occ.status) {
        OccurrenceStatus.PENDING -> p++
        OccurrenceStatus.OVERDUE -> o++
        OccurrenceStatus.COMPLETED -> c++
        OccurrenceStatus.MISSED -> m++
    }
    return DayStatusSummary(p, o, c, m)
}

/** How the active item changes when an upcoming occurrence is marked complete ahead of its fire. */
sealed class EarlyCompletionOutcome {
    /** The occurrence fired between rendering and the tap; its event exists and only gets stamped. */
    data object AlreadyFired : EarlyCompletionOutcome()

    /** A one-shot, or a recurring item with an invalid rule: cancel its alarm and remove it. */
    data object RemoveOneShot : EarlyCompletionOutcome()

    /** A recurring item advanced to its next fire, to persist and reschedule. */
    data class AdvanceRecurring(val updated: ReminderItem) : EarlyCompletionOutcome()
}

/**
 * [snapshot] is the occurrence the user tapped and [current] the item re-read from storage; a
 * missing or already-advanced item means the occurrence fired in between. A recurring item
 * advances from the later of now and its fire, the basis the fire path uses too.
 */
fun planEarlyCompletion(
    snapshot: ReminderItem,
    current: ReminderItem?,
    nowMillis: Long,
    zone: TimeZone,
): EarlyCompletionOutcome {
    if (current == null) return EarlyCompletionOutcome.AlreadyFired
    if (current.remindAtMillis != snapshot.remindAtMillis) return EarlyCompletionOutcome.AlreadyFired
    val rule = current.recurrence
    if (rule == null || !Recurrence.isValid(rule)) return EarlyCompletionOutcome.RemoveOneShot
    val basis = maxOf(nowMillis, current.remindAtMillis)
    return EarlyCompletionOutcome.AdvanceRecurring(current.copy(remindAtMillis = Recurrence.nextAfter(rule, basis, zone)))
}
