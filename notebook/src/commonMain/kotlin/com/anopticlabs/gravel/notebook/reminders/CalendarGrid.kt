package com.anopticlabs.gravel.notebook.reminders

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

const val CALENDAR_CELLS = 42

fun firstOfMonth(day: LocalDate): LocalDate = LocalDate(day.year, day.month, 1)

/** The [CALENDAR_CELLS] days of the grid of the month starting on [month], its weeks starting on [firstDayOfWeek]. */
fun monthCells(month: LocalDate, firstDayOfWeek: DayOfWeek): List<LocalDate> {
    val lead = (month.dayOfWeek.isoDayNumber - firstDayOfWeek.isoDayNumber + 7) % 7
    val start = month.minus(lead, DateTimeUnit.DAY)
    return List(CALENDAR_CELLS) { start.plus(it, DateTimeUnit.DAY) }
}

/** Every day [monthCells] can return for [month], whichever day the weeks start on. */
fun calendarGridDays(month: LocalDate): ClosedRange<LocalDate> =
    month.minus(6, DateTimeUnit.DAY)..month.plus(CALENDAR_CELLS - 1, DateTimeUnit.DAY)
