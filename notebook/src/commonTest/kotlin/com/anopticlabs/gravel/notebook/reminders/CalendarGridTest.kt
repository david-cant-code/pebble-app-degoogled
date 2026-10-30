package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

class CalendarGridTest {

    @Test
    fun aGridIsSixWeeksStartingOnTheGivenDay() {
        val sunday = monthCells(LocalDate(2026, 9, 1), DayOfWeek.SUNDAY)
        assertEquals(CALENDAR_CELLS, sunday.size)
        assertEquals(LocalDate(2026, 8, 30), sunday.first())
        assertEquals(LocalDate(2026, 10, 10), sunday.last())
        assertEquals(LocalDate(2026, 8, 31), monthCells(LocalDate(2026, 9, 1), DayOfWeek.MONDAY).first())
        assertEquals(LocalDate(2026, 9, 1), monthCells(LocalDate(2026, 9, 1), DayOfWeek.TUESDAY).first())
        assertEquals(LocalDate(2026, 8, 26), monthCells(LocalDate(2026, 9, 1), DayOfWeek.WEDNESDAY).first())
    }

    @Test
    fun februaryInALeapYearAndInAnotherYear() {
        val leap = monthCells(LocalDate(2028, 2, 1), DayOfWeek.MONDAY)
        assertTrue(LocalDate(2028, 2, 29) in leap)
        assertEquals(LocalDate(2028, 3, 1), leap[leap.indexOf(LocalDate(2028, 2, 29)) + 1])
        val plain = monthCells(LocalDate(2026, 2, 1), DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 3, 1), plain[plain.indexOf(LocalDate(2026, 2, 28)) + 1])
    }

    @Test
    fun theGridDaysHoldEveryCellWhateverDayTheWeekStartsOn() {
        for (month in listOf(LocalDate(2026, 2, 1), LocalDate(2026, 8, 1), LocalDate(2026, 9, 1))) {
            val range = calendarGridDays(month)
            for (first in DayOfWeek.entries) assertTrue(monthCells(month, first).all { it in range }, "$month from $first")
        }
    }
}
