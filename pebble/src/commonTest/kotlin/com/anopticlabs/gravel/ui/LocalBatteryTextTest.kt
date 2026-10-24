package com.anopticlabs.gravel.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalBatteryTextTest {
    @Test
    fun timeLeftIsRoundedDown() {
        assertEquals("About 5 days left", daysLeftText(5.9))
        assertEquals("About 1 day left", daysLeftText(1.2))
        assertEquals("About 12 hours left", daysLeftText(0.5))
        assertEquals("About 1 hour left", daysLeftText(1 / 24.0))
        assertEquals("Less than an hour left", daysLeftText(0.03))
    }

    @Test
    fun drainPerDay() {
        assertEquals("Uses about 12 % a day", perDayText(12.4))
        assertEquals("Uses less than 1 % a day", perDayText(0.4))
    }

    @Test
    fun aFullCharge() {
        assertEquals("A full charge lasts about 8 days", perChargeText(8.9))
        assertEquals("A full charge lasts about 1 day", perChargeText(1.5))
        assertEquals("A full charge lasts less than a day", perChargeText(0.5))
    }
}
