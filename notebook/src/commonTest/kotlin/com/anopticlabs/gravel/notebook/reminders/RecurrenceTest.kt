package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

class RecurrenceTest {

    private val utc = TimeZone.UTC
    private val newYork = TimeZone.of("America/New_York")

    private fun millis(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int, min: Int = 0): Long =
        LocalDateTime(y, mo, d, h, min).toInstant(zone).toEpochMilliseconds()

    private fun local(millis: Long, zone: TimeZone): LocalDateTime =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)

    private fun next(freq: RecurrenceFrequency, interval: Int, anchor: Long, after: Long, zone: TimeZone): LocalDateTime =
        local(Recurrence.nextAfter(RecurrenceRule(freq, interval, anchor), after, zone), zone)

    @Test
    fun dailySteps() {
        val anchor = millis(utc, 2026, 4, 10, 10)
        assertEquals(LocalDateTime(2026, 4, 11, 10, 0), next(RecurrenceFrequency.DAILY, 1, anchor, anchor + 1, utc))
        val jan1 = millis(utc, 2026, 1, 1, 9)
        assertEquals(LocalDateTime(2026, 1, 13, 9, 0), next(RecurrenceFrequency.DAILY, 3, jan1, millis(utc, 2026, 1, 11, 9), utc))
    }

    @Test
    fun weeklyStaysOnTheAnchorWeekday() {
        val tuesday = millis(utc, 2026, 4, 14, 12)
        val n = next(RecurrenceFrequency.WEEKLY, 1, tuesday, millis(utc, 2026, 4, 17, 12), utc)
        assertEquals(LocalDateTime(2026, 4, 21, 12, 0), n)
        assertEquals(DayOfWeek.TUESDAY, n.dayOfWeek)
    }

    @Test
    fun weeklyKeepsNoonAcrossDstChanges() {
        val beforeSpring = millis(newYork, 2025, 3, 4, 12)
        assertEquals(LocalDateTime(2025, 3, 11, 12, 0), next(RecurrenceFrequency.WEEKLY, 1, beforeSpring, beforeSpring + 1, newYork))
        val beforeFall = millis(newYork, 2025, 10, 28, 12)
        assertEquals(LocalDateTime(2025, 11, 4, 12, 0), next(RecurrenceFrequency.WEEKLY, 1, beforeFall, beforeFall + 1, newYork))
    }

    @Test
    fun dailyAcrossASpringForwardGapShiftsThatDayOnly() {
        // 02:30 does not exist on 2026-03-08 in New York; that fire lands at 03:30.
        val anchor = millis(newYork, 2026, 3, 7, 2, 30)
        assertEquals(LocalDateTime(2026, 3, 8, 3, 30), next(RecurrenceFrequency.DAILY, 1, anchor, anchor + 1, newYork))
        assertEquals(LocalDateTime(2026, 3, 9, 2, 30), next(RecurrenceFrequency.DAILY, 1, anchor, millis(newYork, 2026, 3, 8, 12), newYork))
    }

    @Test
    fun monthlyClampsToTheEndOfShorterMonths() {
        val jan31of2025 = millis(utc, 2025, 1, 31, 9)
        assertEquals(LocalDateTime(2025, 2, 28, 9, 0), next(RecurrenceFrequency.MONTHLY, 1, jan31of2025, jan31of2025 + 1, utc))
        val jan31of2024 = millis(utc, 2024, 1, 31, 9)
        assertEquals(LocalDateTime(2024, 2, 29, 9, 0), next(RecurrenceFrequency.MONTHLY, 1, jan31of2024, jan31of2024 + 1, utc))
    }

    @Test
    fun monthlyReturnsToTheAnchorDayAfterAClamp() {
        val jan31 = millis(utc, 2025, 1, 31, 9)
        val fires = generateSequence(jan31) { Recurrence.nextAfter(RecurrenceRule(RecurrenceFrequency.MONTHLY, 1, jan31), it, utc) }
            .drop(1).take(4).map { local(it, utc) }.toList()
        assertEquals(
            listOf(LocalDateTime(2025, 2, 28, 9, 0), LocalDateTime(2025, 3, 31, 9, 0), LocalDateTime(2025, 4, 30, 9, 0), LocalDateTime(2025, 5, 31, 9, 0)),
            fires,
        )
        assertEquals(LocalDateTime(2025, 11, 30, 9, 0), next(RecurrenceFrequency.MONTHLY, 2, jan31, millis(utc, 2025, 9, 30, 9), utc))
        assertEquals(LocalDateTime(2026, 1, 31, 9, 0), next(RecurrenceFrequency.MONTHLY, 2, jan31, millis(utc, 2025, 11, 30, 9), utc))
    }

    @Test
    fun yearlyFromALeapDayReturnsOnTheNextLeapYear() {
        val leapDay = millis(utc, 2024, 2, 29, 8)
        assertEquals(LocalDateTime(2027, 2, 28, 8, 0), next(RecurrenceFrequency.YEARLY, 1, leapDay, millis(utc, 2026, 2, 28, 8), utc))
        assertEquals(LocalDateTime(2028, 2, 29, 8, 0), next(RecurrenceFrequency.YEARLY, 1, leapDay, millis(utc, 2027, 2, 28, 8), utc))
    }

    @Test
    fun yearlyFromALeapDay() {
        val leapDay = millis(utc, 2024, 2, 29, 8)
        assertEquals(LocalDateTime(2025, 2, 28, 8, 0), next(RecurrenceFrequency.YEARLY, 1, leapDay, leapDay + 1, utc))
        assertEquals(LocalDateTime(2028, 2, 29, 8, 0), next(RecurrenceFrequency.YEARLY, 4, leapDay, leapDay + 1, utc))
    }

    @Test
    fun aLargeGapGivesTheNextOccurrenceForEachFrequency() {
        assertEquals(LocalDateTime(2026, 1, 2, 9, 0), next(RecurrenceFrequency.DAILY, 1, millis(utc, 2010, 1, 1, 9), millis(utc, 2026, 1, 1, 9), utc))
        assertEquals(LocalDateTime(2199, 6, 4, 9, 0), next(RecurrenceFrequency.WEEKLY, 1, millis(utc, 1971, 1, 5, 9), millis(utc, 2199, 6, 1, 9), utc))
        assertEquals(LocalDateTime(2025, 1, 31, 9, 0), next(RecurrenceFrequency.MONTHLY, 1, millis(utc, 2020, 1, 31, 9), millis(utc, 2025, 1, 1, 9), utc))
        assertEquals(LocalDateTime(2151, 2, 28, 9, 0), next(RecurrenceFrequency.YEARLY, 1, millis(utc, 1972, 2, 29, 9), millis(utc, 2150, 3, 1, 9), utc))
    }

    @Test
    fun aJumpAcrossAFallBackHourLandsOnTheNextOccurrence() {
        // 2026-11-01 is 25 hours long in New York, so two days after the anchor is past `after` here.
        val anchor = millis(newYork, 2026, 10, 31, 12)
        assertEquals(LocalDateTime(2026, 11, 2, 12, 0), next(RecurrenceFrequency.DAILY, 1, anchor, millis(newYork, 2026, 11, 2, 11, 30), newYork))
        assertEquals(LocalDateTime(2026, 11, 1, 12, 0), next(RecurrenceFrequency.DAILY, 1, anchor, millis(newYork, 2026, 11, 1, 11, 30), newYork))
    }

    @Test
    fun aFutureAnchorIsTheFirstOccurrence() {
        val anchor = millis(utc, 2030, 1, 1, 9)
        val rule = RecurrenceRule(RecurrenceFrequency.WEEKLY, 1, anchor)
        assertEquals(anchor, Recurrence.nextAfter(rule, millis(utc, 2026, 1, 1, 9), utc))
    }

    @Test
    fun anAnchorInTheRepeatedFallBackHourIsKeptExactly() {
        // The second 01:30 of 2026-11-01 in New York (EST); its wall-clock time also names the EDT instant an hour earlier.
        val secondOneThirty = millis(utc, 2026, 11, 1, 6, 30)
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, secondOneThirty)
        assertEquals(secondOneThirty, Recurrence.nextAfter(rule, secondOneThirty - 2 * 3_600_000L, newYork))
    }

    @Test
    fun nonPositiveIntervalsCountAsOne() {
        val anchor = millis(utc, 2026, 4, 10, 10)
        for (interval in listOf(0, -5)) {
            assertEquals(LocalDateTime(2026, 4, 11, 10, 0), next(RecurrenceFrequency.DAILY, interval, anchor, anchor + 1, utc))
        }
    }

    @Test
    fun validityNeedsAnAnchorInTheStoredWindowAndABoundedInterval() {
        assertTrue(Recurrence.isValid(RecurrenceRule(RecurrenceFrequency.DAILY, 1, 1L)))
        assertTrue(Recurrence.isValid(RecurrenceRule(RecurrenceFrequency.YEARLY, Recurrence.MAX_INTERVAL, 1L)))
        assertTrue(!Recurrence.isValid(RecurrenceRule(RecurrenceFrequency.DAILY, 1, 0L)))
        assertTrue(!Recurrence.isValid(RecurrenceRule(RecurrenceFrequency.DAILY, 0, 1L)))
        assertTrue(!Recurrence.isValid(RecurrenceRule(RecurrenceFrequency.YEARLY, Recurrence.MAX_INTERVAL + 1, 1L)))
        assertTrue(!Recurrence.isValid(RecurrenceRule(RecurrenceFrequency.YEARLY, Int.MAX_VALUE, 1L)))
        assertTrue(!Recurrence.isValid(RecurrenceRule(RecurrenceFrequency.DAILY, 1, Long.MAX_VALUE)))
    }
}
