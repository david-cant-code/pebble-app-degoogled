package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

class PhoneCalendarTest {

    private val la = TimeZone.of("America/Los_Angeles")
    private val september = LocalDate(2026, 9, 1)..LocalDate(2026, 9, 30)

    private fun utcMidnight(day: LocalDate) = day.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, zone: TimeZone = la) =
        LocalDateTime(y, m, d, h, min).toInstant(zone).toEpochMilliseconds()

    private fun allDay(first: LocalDate, endExclusive: LocalDate, title: String = "all day") =
        PhoneEvent(title, utcMidnight(first), utcMidnight(endExclusive), allDay = true, calendarName = "Home")

    private fun timed(begin: Long, end: Long, title: String = "timed") = PhoneEvent(title, begin, end, allDay = false, calendarName = "Work")

    @Test
    fun anAllDayEventIsOnItsUtcDateInAnyZone() {
        val event = allDay(LocalDate(2026, 9, 20), LocalDate(2026, 9, 21))
        for (zone in listOf(la, TimeZone.of("Pacific/Kiritimati"), TimeZone.of("Etc/GMT+12"), TimeZone.UTC)) {
            assertEquals(mapOf(LocalDate(2026, 9, 20) to listOf(event)), phoneEventsByDay(listOf(event), september, zone), zone.id)
        }
    }

    @Test
    fun aSeveralDayAllDayEventIsOnEachOfItsDays() {
        val event = allDay(LocalDate(2026, 9, 28), LocalDate(2026, 10, 1))
        val byDay = phoneEventsByDay(listOf(event), september, la)
        assertEquals(listOf(LocalDate(2026, 9, 28), LocalDate(2026, 9, 29), LocalDate(2026, 9, 30)), byDay.keys.sorted())
    }

    @Test
    fun aTimedEventIsOnItsLocalDaysAndAnEndAtMidnightStaysOnTheDayBefore() {
        val overnight = timed(at(2026, 9, 10, 23), at(2026, 9, 11, 1))
        val toMidnight = timed(at(2026, 9, 12, 22), at(2026, 9, 13, 0))
        val instant = timed(at(2026, 9, 14, 9), at(2026, 9, 14, 9))
        val byDay = phoneEventsByDay(listOf(overnight, toMidnight, instant), september, la)
        assertEquals(
            mapOf(
                LocalDate(2026, 9, 10) to listOf(overnight),
                LocalDate(2026, 9, 11) to listOf(overnight),
                LocalDate(2026, 9, 12) to listOf(toMidnight),
                LocalDate(2026, 9, 14) to listOf(instant),
            ),
            byDay,
        )
        // 23:00 in Los Angeles is the next day in UTC.
        assertEquals(setOf(LocalDate(2026, 9, 11)), phoneEventsByDay(listOf(overnight), september, TimeZone.UTC).keys)
    }

    @Test
    fun onlyTheDaysAskedForAreFilled() {
        val year = allDay(LocalDate(2026, 1, 1), LocalDate(2027, 1, 1))
        val before = timed(at(2026, 8, 31, 10), at(2026, 8, 31, 11))
        val byDay = phoneEventsByDay(listOf(year, before), september, la)
        assertEquals(30, byDay.size)
        assertEquals(september.start, byDay.keys.min())
        assertEquals(september.endInclusive, byDay.keys.max())
    }

    @Test
    fun aDayListsAllDayEventsFirstThenByStartThenTitle() {
        val day = LocalDate(2026, 9, 15)
        val tokyo = TimeZone.of("Asia/Tokyo")
        // 01:00 in Tokyo starts before the all-day event's UTC midnight.
        val first = timed(at(2026, 9, 15, 1, zone = tokyo), at(2026, 9, 15, 2, zone = tokyo), "first")
        val earlyB = timed(at(2026, 9, 15, 9, zone = tokyo), at(2026, 9, 15, 10, zone = tokyo), "b")
        val earlyA = timed(at(2026, 9, 15, 9, zone = tokyo), at(2026, 9, 15, 11, zone = tokyo), "a")
        val whole = allDay(day, LocalDate(2026, 9, 16))
        assertEquals(listOf(whole, first, earlyA, earlyB), phoneEventsByDay(listOf(earlyB, whole, first, earlyA), september, tokyo)[day])
    }

    @Test
    fun theQueryWindowFindsAllDayEventsOnTheFirstAndLastDayInAnyZone() {
        for (zone in listOf(la, TimeZone.of("Pacific/Kiritimati"), TimeZone.of("Etc/GMT+12"), TimeZone.UTC)) {
            val window = phoneQueryWindow(september, zone)
            for (event in listOf(allDay(september.start, LocalDate(2026, 9, 2)), allDay(september.endInclusive, LocalDate(2026, 10, 1)))) {
                // The calendar provider's own test: an instance is in a window it overlaps.
                assertTrue(event.beginMillis <= window.last && event.endMillis >= window.first, "${zone.id} ${event.beginMillis}")
            }
            assertEquals(september.start.atStartOfDayIn(zone).toEpochMilliseconds(), window.first)
            assertEquals(LocalDate(2026, 10, 1).atStartOfDayIn(zone).toEpochMilliseconds() - 1, window.last)
        }
    }

    @Test
    fun theShownPhoneCalendarWidensPagingToAYearEitherSide() {
        val today = LocalDate(2026, 9, 22)
        val narrow = LocalDate(2026, 6, 1)..LocalDate(2026, 10, 1)
        assertEquals(LocalDate(2025, 9, 1)..LocalDate(2027, 9, 1), withPhoneCalendarMonths(narrow, today))
        val wide = LocalDate(2024, 1, 1)..LocalDate(2028, 1, 1)
        assertEquals(wide, withPhoneCalendarMonths(wide, today))
    }

    @Test
    fun aChoiceIsActiveOnlyWhenShownWithACalendarPicked() {
        assertTrue(PhoneCalendarChoice(shown = true, calendars = setOf("k")).active)
        assertFalse(PhoneCalendarChoice(shown = true, calendars = emptySet()).active)
        assertFalse(PhoneCalendarChoice(shown = false, calendars = setOf("k")).active)
    }

    @Test
    fun aCalendarKeyNamesTheCalendarNotItsRowUnlessNothingElseDoes() {
        val work = calendarKey("com.example", "me@example.com", "work", "sync-1", 7)
        assertEquals(work, calendarKey("com.example", "me@example.com", "work", "sync-1", 12))
        assertNotEquals(work, calendarKey("com.example", "you@example.com", "work", "sync-1", 7))
        assertNotEquals(work, calendarKey("org.other", "me@example.com", "work", "sync-1", 7))
        assertNotEquals(work, calendarKey("com.example", "me@example.com", "home", "sync-1", 7))
        assertNotEquals(work, calendarKey("com.example", "me@example.com", "work", "sync-2", 7))
        assertEquals(calendarKey("t", "a", null, "s", 1), calendarKey("t", "a", null, "s", 2))
        assertNotEquals(calendarKey("t", "a", null, null, 1), calendarKey("t", "a", null, null, 2))
        // Fields cannot run into each other.
        assertNotEquals(calendarKey("a\",b", "c", "n", null, 1), calendarKey("a", "b\",c", "n", null, 1))
        assertNotEquals(calendarKey("a", null, "n", null, 1), calendarKey("a", "null", "n", null, 1))
    }
}
