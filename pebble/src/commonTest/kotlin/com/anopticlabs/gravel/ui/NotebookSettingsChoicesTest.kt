package com.anopticlabs.gravel.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NotebookSettingsChoicesTest {

    @Test
    fun aValueAmongTheChoicesAddsNothing() {
        assertEquals(NotebookSettingsChoices.swipeSnoozeMinutes, NotebookSettingsChoices.withCurrent(NotebookSettingsChoices.swipeSnoozeMinutes, 10))
        assertEquals(NotebookSettingsChoices.quickRemindMinutes, NotebookSettingsChoices.withCurrent(NotebookSettingsChoices.quickRemindMinutes, 30))
        assertEquals(NotebookSettingsChoices.historyRetentionDays, NotebookSettingsChoices.withCurrent(NotebookSettingsChoices.historyRetentionDays, 90))
    }

    @Test
    fun aStoredValueOutsideTheChoicesIsListedInOrderWithForeverLast() {
        assertEquals(listOf(5, 10, 15, 30, 45, 60, 120), NotebookSettingsChoices.withCurrent(NotebookSettingsChoices.swipeSnoozeMinutes, 45))
        assertEquals(listOf(7, 30, 90, 180, 365, 0), NotebookSettingsChoices.withCurrent(NotebookSettingsChoices.historyRetentionDays, 7))
    }

    @Test
    fun valuesReadAsMinutesHoursDaysOrForever() {
        assertEquals("1 minute", NotebookSettingsChoices.minutesText(1))
        assertEquals("45 minutes", NotebookSettingsChoices.minutesText(45))
        assertEquals("90 minutes", NotebookSettingsChoices.minutesText(90))
        assertEquals("1 hour", NotebookSettingsChoices.minutesText(60))
        assertEquals("4 hours", NotebookSettingsChoices.minutesText(240))
        assertEquals("30 days", NotebookSettingsChoices.daysText(30))
        assertEquals("1 year", NotebookSettingsChoices.daysText(365))
        assertEquals("Forever", NotebookSettingsChoices.daysText(0))
    }
}
