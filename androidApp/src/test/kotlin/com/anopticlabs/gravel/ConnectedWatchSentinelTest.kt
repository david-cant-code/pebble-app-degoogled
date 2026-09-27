package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `connectedWatch()` starts from the current watch list. WatchOnboardingScreen resets its install
 * flags when it sees no connected watch, so a null first frame after the build picker can restart a
 * failed automatic install. LockerUtil.kt is upstream-owned, and upstream starts from null. Checked as
 * text, comment lines left out.
 */
class ConnectedWatchSentinelTest {

    @Test
    fun theFirstFrameHasTheCurrentWatchList() {
        val source = TrackedTree.file("pebble/src/commonMain/kotlin/coredevices/pebble/ui/LockerUtil.kt")
            .readLines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
        val body = assertNotNull(
            Regex("""fun connectedWatch\(\): CommonConnectedDevice\? \{\n(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
                .find(source)?.groupValues?.get(1),
            "LockerUtil.kt no longer has connectedWatch()",
        )
        assertTrue(
            Regex("""collectAsState\(\s*libPebble\.watches\.value\.filterIsInstance<CommonConnectedDevice>\(\)\s*\)""")
                .containsMatchIn(body),
            "connectedWatch() no longer starts from the current watch list",
        )
    }
}
