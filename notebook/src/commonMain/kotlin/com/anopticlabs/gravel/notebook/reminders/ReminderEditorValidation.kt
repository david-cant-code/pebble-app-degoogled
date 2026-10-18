package com.anopticlabs.gravel.notebook.reminders

/** A one-shot must be strictly in the future; a recurring anchor may be in the past. */
fun reminderTimeSubmittable(selectedMillis: Long, nowMillis: Long, recurring: Boolean): Boolean =
    recurring || selectedMillis > nowMillis

fun reminderEditorCanSave(
    messageBlank: Boolean,
    selectedMillis: Long,
    nowMillis: Long,
    recurring: Boolean,
    intervalValid: Boolean,
): Boolean =
    !messageBlank &&
        reminderTimeSubmittable(selectedMillis, nowMillis, recurring) &&
        (!recurring || intervalValid)
