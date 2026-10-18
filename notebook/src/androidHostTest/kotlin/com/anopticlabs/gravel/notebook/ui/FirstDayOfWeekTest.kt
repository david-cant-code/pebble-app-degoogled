package com.anopticlabs.gravel.notebook.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.DayOfWeek

class FirstDayOfWeekTest {

    @Test
    fun theLocalesWeekStartsOnItsOwnFirstDayOrOnTheChosenOne() {
        assertEquals(DayOfWeek.SUNDAY, firstDayOfWeek(Locale.forLanguageTag("en-US")))
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeek(Locale.forLanguageTag("de-DE")))
        assertEquals(DayOfWeek.SATURDAY, firstDayOfWeek(Locale.forLanguageTag("fa-IR")))
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeek(Locale.forLanguageTag("en-US-u-fw-mon")))
        assertEquals(DayOfWeek.SATURDAY, firstDayOfWeek(Locale.forLanguageTag("de-DE-u-fw-sat")))
        for ((tag, day) in listOf("tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sun" to DayOfWeek.SUNDAY)) {
            assertEquals(day, firstDayOfWeek(Locale.forLanguageTag("de-DE-u-fw-$tag")), tag)
        }
    }
}
