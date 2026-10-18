package com.anopticlabs.gravel.notebook.reminders

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The phone's own calendars, named by [calendarKey]; both reads block and return null without calendar access.
 */
interface PhoneCalendar {
    fun calendars(): List<PhoneCalendarInfo>?

    /** The instances of the [calendars] still on the phone overlapping [window], canceled ones and ones the user declined left out. */
    fun events(calendars: Set<String>, window: LongRange): List<PhoneEvent>?
}

data class PhoneCalendarInfo(val key: String, val name: String, val account: String)

/**
 * A calendar's identity across provider row ids, which a restore onto another phone or a re-created calendar changes;
 * the row [id] counts only for a calendar with neither a [name] nor a [syncId].
 */
fun calendarKey(accountType: String?, accountName: String?, name: String?, syncId: String?, id: Long): String =
    JsonArray(
        listOf(accountType, accountName, name, syncId, if (name == null && syncId == null) id.toString() else null)
            .map(::JsonPrimitive),
    ).toString()

/** One instance of a phone calendar event; an all-day one runs from midnight to midnight UTC. */
data class PhoneEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarName: String,
)

/** Settings > Phone Calendar's choice for the Notebook: whether it is shown, and the [calendarKey]s picked. */
data class PhoneCalendarChoice(val shown: Boolean, val calendars: Set<String>) {
    val active: Boolean get() = shown && calendars.isNotEmpty()
}

/** How many months either side of today's the Calendar section pages to while the phone's calendar is shown. */
const val PHONE_CALENDAR_PAGING_MONTHS = 12

/** [months] widened to [PHONE_CALENDAR_PAGING_MONTHS] months either side of [today]'s month. */
fun withPhoneCalendarMonths(months: ClosedRange<LocalDate>, today: LocalDate): ClosedRange<LocalDate> {
    val month = firstOfMonth(today)
    return minOf(months.start, month.minus(PHONE_CALENDAR_PAGING_MONTHS, DateTimeUnit.MONTH))..
        maxOf(months.endInclusive, month.plus(PHONE_CALENDAR_PAGING_MONTHS, DateTimeUnit.MONTH))
}

/**
 * The instants of [days] in [zone]. An all-day event on one of them, kept in UTC, overlaps it too: no zone is a whole
 * day off UTC.
 */
fun phoneQueryWindow(days: ClosedRange<LocalDate>, zone: TimeZone): LongRange =
    days.start.atStartOfDayIn(zone).toEpochMilliseconds() until days.endInclusive.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()

/**
 * [events] on each day of [days] they cover, all-day ones first, then by start: an all-day event on its UTC dates, a
 * timed one on its dates in [zone]. An end at midnight does not reach into the next day.
 */
fun phoneEventsByDay(events: List<PhoneEvent>, days: ClosedRange<LocalDate>, zone: TimeZone): Map<LocalDate, List<PhoneEvent>> {
    val byDay = HashMap<LocalDate, MutableList<PhoneEvent>>()
    for (event in events) {
        val dayZone = if (event.allDay) TimeZone.UTC else zone
        val first = dateOf(event.beginMillis, dayZone)
        val last = if (event.endMillis > event.beginMillis) dateOf(event.endMillis - 1, dayZone) else first
        var day = maxOf(first, days.start)
        val end = minOf(last, days.endInclusive)
        while (day <= end) {
            byDay.getOrPut(day) { mutableListOf() }.add(event)
            day = day.plus(1, DateTimeUnit.DAY)
        }
    }
    return byDay.mapValues { (_, onDay) -> onDay.sortedWith(compareBy({ !it.allDay }, { it.beginMillis }, { it.title })) }
}

private fun dateOf(millis: Long, zone: TimeZone): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
