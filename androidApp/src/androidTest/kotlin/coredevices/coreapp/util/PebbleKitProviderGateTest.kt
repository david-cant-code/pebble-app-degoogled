package coredevices.coreapp.util

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertNull

/**
 * Exercises the PebbleKit 2 provider's authorization gate from the outside, through the real
 * exported ContentProvider. This process is not a declared companion of any installed watchapp,
 * so every query must be refused with a null cursor. The pin is non-vacuous: the ungated base
 * class answers the connectedWatches path with a cursor unconditionally (empty when no watch is
 * connected), so a regression that reverts query() to a plain super call, for example an
 * upstream merge taking upstream's ungated version of the file, turns these into failures.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.PebbleKitProviderGateTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class PebbleKitProviderGateTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun connectedWatchesQueryFromANonCompanionIsRefused() {
        assertRefused(Uri.parse("content://${context.packageName}.pebblekit/connectedWatches"))
    }

    @Test
    fun activeAppQueryFromANonCompanionIsRefused() {
        assertRefused(Uri.parse("content://${context.packageName}.pebblekit/activeApp/any-id"))
    }

    @Test
    fun unknownPathQueryFromANonCompanionIsRefused() {
        assertRefused(Uri.parse("content://${context.packageName}.pebblekit/somethingElse"))
    }

    private fun assertRefused(uri: Uri) {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            assertNull(cursor, "query of $uri from a non-companion returned a cursor")
        } finally {
            cursor?.close()
        }
    }
}
