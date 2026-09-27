package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The update notification's tap opens the build picker when its extras carry the watch and this
 * process's token (DESIGN_NOTES, "PebbleOS changelog list"). The notification and MainActivity are
 * upstream-owned; FirmwareNotificationTokenTest covers the route decision itself. Checked as text,
 * comment lines left out.
 */
class FirmwareNotificationSentinelTest {

    private fun code(path: String) = TrackedTree.file(path).readLines()
        .filterNot { it.trim().startsWith("//") }
        .joinToString("\n")

    @Test
    fun theNotificationCarriesTheWatchAndTheProcessToken() {
        val tracker = code("pebble/src/androidMain/kotlin/coredevices/pebble/firmware/FirmwareUpdateUiTracker.android.kt")
        assertTrue("viewIntent?.putExtra(EXTRA_FIRMWARE_PICKER_WATCH, identifier.asString)" in tracker)
        assertTrue("viewIntent?.putExtra(EXTRA_FIRMWARE_PICKER_TOKEN, FirmwareNotificationToken.value)" in tracker)
        assertTrue(
            Regex(
                """PendingIntent\.getActivity\(\s*context,\s*key,\s*viewIntent,\s*PendingIntent\.FLAG_IMMUTABLE or PendingIntent\.FLAG_UPDATE_CURRENT,?\s*\)""",
            ).containsMatchIn(tracker),
            "the notification's PendingIntent no longer uses the per-watch key with FLAG_UPDATE_CURRENT",
        )
    }

    @Test
    fun mainActivityReadsTheExtrasWithTheDefaultToken() {
        val activity = code("composeApp/src/androidMain/kotlin/coredevices/coreapp/MainActivity.kt")
        assertTrue(
            Regex(
                """firmwarePickerRouteForNotification\(\s*readExtra = intent::getStringExtra,\s*launchedFromHistory = intent\.flags and Intent\.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,""",
            ).containsMatchIn(activity),
        )
        assertFalse("expectedToken" in activity, "MainActivity passes its own token")
    }
}
