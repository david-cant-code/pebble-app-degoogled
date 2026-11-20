package com.anopticlabs.gravel.ui

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import coredevices.pebble.ui.CalendarEntryRow
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Calendar Settings' row for one calendar: its checkbox reports the new state, and the not-synced notice shows for a
 * calendar that is on and that Android does not sync or does not show.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.ui.CalendarEntryRowComposeTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class CalendarEntryRowComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun calendar(enabled: Boolean, syncEvents: Boolean = true, visible: Boolean = true) = CalendarEntity(
        platformId = "1", name = "Work", ownerName = "Owner", ownerId = "1", color = 0,
        enabled = enabled, syncEvents = syncEvents, visible = visible,
    )

    private fun notice() = compose.onNodeWithText("Not synced by Android", substring = true)

    @Test
    fun tappingACalendarThatIsOnReportsOff() = assertTapReports(enabled = true, expected = false)

    @Test
    fun tappingACalendarThatIsOffReportsOn() = assertTapReports(enabled = false, expected = true)

    private fun assertTapReports(enabled: Boolean, expected: Boolean) {
        val taps = mutableListOf<Boolean>()
        compose.setContent { CalendarEntryRow(calendar(enabled = enabled)) { taps += it } }
        compose.onNode(isToggleable()).performClick()
        assertEquals(listOf(expected), taps)
    }

    @Test
    fun aCalendarAndroidDoesNotSyncShowsTheNotice() {
        compose.setContent { CalendarEntryRow(calendar(enabled = true, syncEvents = false)) {} }
        notice().assertExists()
    }

    @Test
    fun aCalendarAndroidDoesNotShowShowsTheNotice() {
        compose.setContent { CalendarEntryRow(calendar(enabled = true, visible = false)) {} }
        notice().assertExists()
    }

    @Test
    fun aSyncedCalendarShowsNoNotice() {
        compose.setContent { CalendarEntryRow(calendar(enabled = true)) {} }
        notice().assertDoesNotExist()
    }

    @Test
    fun aCalendarThatIsOffShowsNoNotice() {
        compose.setContent { CalendarEntryRow(calendar(enabled = false, syncEvents = false, visible = false)) {} }
        notice().assertDoesNotExist()
    }
}
