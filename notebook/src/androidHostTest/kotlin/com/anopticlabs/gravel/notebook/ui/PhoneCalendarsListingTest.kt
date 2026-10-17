package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.reminders.PhoneCalendarInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneCalendarsListingTest {

    @Test
    fun thePickerShowsNoAccessAndAFailedListingInsteadOfFailing() {
        val home = PhoneCalendarInfo("k", "Home", "me")
        assertEquals(CalendarListing.Listed(listOf(home)), listPhoneCalendars { listOf(home) })
        assertEquals(CalendarListing.NoAccess, listPhoneCalendars { null })
        assertEquals(CalendarListing.NotRead, listPhoneCalendars { throw IllegalStateException("provider") })
    }
}
