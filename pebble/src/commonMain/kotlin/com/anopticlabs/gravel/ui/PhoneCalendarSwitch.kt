package com.anopticlabs.gravel.ui

import coredevices.util.PermissionResult

/** What turning on "Show in Notebook" under Settings > Phone Calendar does first. */
enum class PhoneCalendarFirstStep { SHOW, ASK_ABOUT_WATCH, ASK_ACCESS }

/**
 * Without calendar access, the watch question comes first while the watch's Enable Calendar is on: once access is
 * granted, libpebble3 builds watch pins from the phone's calendars while `calendarPins` is on
 * (`PhoneCalendarSyncer.syncDeviceCalendarsToDb`, run by `handlePermissionsGranted`).
 */
fun phoneCalendarFirstStep(hasAccess: Boolean, watchCalendarOn: Boolean): PhoneCalendarFirstStep = when {
    hasAccess -> PhoneCalendarFirstStep.SHOW
    watchCalendarOn -> PhoneCalendarFirstStep.ASK_ABOUT_WATCH
    else -> PhoneCalendarFirstStep.ASK_ACCESS
}

/** What to tell the user after asking for calendar access; null when it was granted. A null [result] was never asked. */
fun phoneCalendarRefusalText(result: PermissionResult?): String? = when (result) {
    PermissionResult.Granted -> null
    PermissionResult.Rejected -> "Showing your phone's calendar in the Notebook needs calendar access."
    PermissionResult.RejectedForever -> "Calendar access wasn't allowed. You can allow it in the app settings."
    PermissionResult.Error, PermissionResult.Unknown, null -> "Calendar access couldn't be requested here."
}
