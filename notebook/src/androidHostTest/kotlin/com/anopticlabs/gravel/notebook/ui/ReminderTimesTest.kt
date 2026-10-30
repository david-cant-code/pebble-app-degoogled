package com.anopticlabs.gravel.notebook.ui

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReminderTimesTest {

    private val newYork = ZoneId.of("America/New_York")
    private val saved = TimeZone.getDefault()

    @BeforeTest
    fun westOfUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
    }

    @AfterTest
    fun restore() {
        TimeZone.setDefault(saved)
    }

    private fun millis(at: LocalDateTime) = at.atZone(newYork).toInstant().toEpochMilli()

    @Test
    fun aLateEveningTimeIsPickedOnItsLocalDayAndMovesToAnotherDayKeepingItsTime() {
        // 22:00 in New York is already the next day in UTC.
        val evening = millis(LocalDateTime.of(2026, 3, 10, 22, 0))
        val picked = ReminderTimes.pickerDate(evening)
        assertEquals(LocalDateTime.of(2026, 3, 10, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), picked)
        val twelfth = LocalDateTime.of(2026, 3, 12, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(millis(LocalDateTime.of(2026, 3, 12, 22, 0)), ReminderTimes.withDate(evening, twelfth))
    }

    @Test
    fun aNewTimeStaysOnTheLocalDayOnAWholeMinute() {
        val evening = millis(LocalDateTime.of(2026, 3, 10, 22, 0, 30))
        assertEquals(millis(LocalDateTime.of(2026, 3, 10, 7, 15)), ReminderTimes.withTime(evening, 7, 15))
    }

    @Test
    fun aTimeInTheSpringForwardGapMovesLaterByTheGap() {
        val morning = millis(LocalDateTime.of(2026, 3, 8, 9, 0))
        assertEquals(millis(LocalDateTime.of(2026, 3, 8, 3, 30)), ReminderTimes.withTime(morning, 2, 30))
    }
}
