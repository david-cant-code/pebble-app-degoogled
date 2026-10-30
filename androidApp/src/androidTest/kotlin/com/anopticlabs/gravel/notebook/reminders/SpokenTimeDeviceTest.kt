package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Test

/**
 * The reader on the device's own regex engine, which refuses some patterns the JVM's accepts.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.reminders.SpokenTimeDeviceTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class SpokenTimeDeviceTest {

    @Test
    fun digitsOfOtherScriptsAreNotRead() {
        val anchor = Instant.fromEpochMilliseconds(1_792_280_061_000L)
        val zone = TimeZone.of("America/Los_Angeles")
        for (text in listOf("Remind me in ١.٥ hours", "Remind me in ٥ minutes", "Remind me at ٣")) {
            assertEquals(SpokenTime.None, SpokenTimeReader.read(text, anchor, zone), text)
        }
        assertEquals("Call Sam in ١.٥ hours", SpokenTimeReader.withoutTimeWords("Call Sam in ١.٥ hours"))
    }

    @Test
    fun theRulesReadAndStripOnTheDevice() {
        val zone = TimeZone.of("America/Los_Angeles")
        val anchor = LocalDateTime.parse("2026-10-17T16:34:21").toInstant(zone)
        assertEquals(SpokenTime.Read(anchor.toEpochMilliseconds() + 300_000), SpokenTimeReader.read("Remind me in 5 minutes", anchor, zone))
        assertEquals(SpokenTime.Unread, SpokenTimeReader.read("Remind me at 3, no, make that 4", anchor, zone))
        for ((message, stripped) in listOf(
            "Call Sam {tomorrow}" to "Call Sam", "Call Sam \"tomorrow\"" to "Call Sam", "Call Sam “tomorrow”" to "Call Sam",
            "Call Sam (tomorrow)" to "Call Sam", "Call Sam - 5pm - about the car" to "Call Sam - about the car",
        )) {
            assertEquals(stripped, SpokenTimeReader.withoutTimeWords(message), message)
        }
    }
}
