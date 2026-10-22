package com.anopticlabs.gravel.ui

import coredevices.util.PermissionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PhoneCalendarSwitchTest {

    @Test
    fun theWatchQuestionComesOnlyWhenAccessIsMissingAndTheWatchCalendarIsOn() {
        assertEquals(PhoneCalendarFirstStep.SHOW, phoneCalendarFirstStep(hasAccess = true, watchCalendarOn = true))
        assertEquals(PhoneCalendarFirstStep.SHOW, phoneCalendarFirstStep(hasAccess = true, watchCalendarOn = false))
        assertEquals(PhoneCalendarFirstStep.ASK_ABOUT_WATCH, phoneCalendarFirstStep(hasAccess = false, watchCalendarOn = true))
        assertEquals(PhoneCalendarFirstStep.ASK_ACCESS, phoneCalendarFirstStep(hasAccess = false, watchCalendarOn = false))
    }

    @Test
    fun everyAskThatDidNotGrantAccessIsExplained() {
        assertNull(phoneCalendarRefusalText(PermissionResult.Granted))
        val texts = listOf(PermissionResult.Rejected, PermissionResult.RejectedForever, PermissionResult.Error, PermissionResult.Unknown, null)
            .map { assertNotNull(phoneCalendarRefusalText(it)) }
        assertEquals(3, texts.toSet().size)
    }
}
