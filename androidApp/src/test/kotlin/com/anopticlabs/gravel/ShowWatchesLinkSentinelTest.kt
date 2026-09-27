package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `pebble://show-watches` link only opens the Devices tab (DESIGN_NOTES, "PebbleOS changelog
 * list"). PebbleDeepLinkHandler is upstream-owned, and upstream's link installs the offered firmware
 * for the watch its path names. Checked as text, comment lines left out.
 */
class ShowWatchesLinkSentinelTest {

    private val code by lazy {
        TrackedTree.file("pebble/src/commonMain/kotlin/coredevices/pebble/PebbleDeepLinkHandler.kt")
            .readLines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }

    @Test
    fun theLinkIgnoresItsPathAndOpensTheDevicesTab() {
        assertTrue(Regex("""(?m)^\s*SHOW_WATCHES_HOST\s*->\s*handleShowWatches\(\)\s*$""").containsMatchIn(code))
        val body = assertNotNull(
            Regex("""private fun handleShowWatches\(\): Boolean \{\n(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL)
                .find(code)?.groupValues?.get(1),
            "PebbleDeepLinkHandler no longer has handleShowWatches()",
        )
        assertEquals(
            listOf(
                "val route = PebbleNavBarRoutes.WatchesRoute",
                "_navigateToPebbleDeepLink.value = PebbleDeepLink(route)",
                "return true",
            ),
            body.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    @Test
    fun theHandlerNamesNeitherTheTrackerNorTheUpdateInstalls() {
        for (name in listOf("updateWatchNow", "FirmwareUpdateUiTracker", "VerifiedFirmwareInstaller", "updateFirmware")) {
            assertFalse(name in code, "PebbleDeepLinkHandler references $name")
        }
    }
}
