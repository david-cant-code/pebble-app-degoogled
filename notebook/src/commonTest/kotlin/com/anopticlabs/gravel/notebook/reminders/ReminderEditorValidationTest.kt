package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderEditorValidationTest {

    @Test
    fun oneShotMustBeStrictlyFuture() {
        val selected = 1_000_000L
        assertTrue(reminderTimeSubmittable(selected, selected - 60_000, recurring = false))
        assertFalse(reminderTimeSubmittable(selected, selected, recurring = false))
        assertFalse(reminderTimeSubmittable(selected, selected + 1, recurring = false))
        assertTrue(reminderTimeSubmittable(selected, selected + 999_999, recurring = true))
    }

    @Test
    fun canSaveNeedsAMessageATimeAndAnInterval() {
        val now = 10_000L
        val future = now + 60_000
        assertTrue(reminderEditorCanSave(messageBlank = false, future, now, recurring = false, intervalValid = true))
        assertFalse(reminderEditorCanSave(messageBlank = true, future, now, recurring = false, intervalValid = true))
        assertFalse(reminderEditorCanSave(messageBlank = false, now - 1, now, recurring = false, intervalValid = true))
        assertFalse(reminderEditorCanSave(messageBlank = false, now - 1, now, recurring = true, intervalValid = false))
        assertTrue(reminderEditorCanSave(messageBlank = false, now - 1, now, recurring = true, intervalValid = true))
        assertFalse(reminderEditorCanSave(messageBlank = true, future, now, recurring = true, intervalValid = true))
    }
}
