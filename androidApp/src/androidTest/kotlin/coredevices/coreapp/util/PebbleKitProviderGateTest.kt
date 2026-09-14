package coredevices.coreapp.util

import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.rebble.libpebblecommon.connection.LibPebble
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the PebbleKit 2 provider's authorization gate from the outside, through the real
 * exported ContentProvider. This process is not a declared companion of any installed watchapp,
 * so every query must be refused with a null cursor. The pin is non-vacuous: the ungated base
 * class answers the connectedWatches path with a cursor unconditionally (empty when no watch is
 * connected), so a regression that reverts query() to a plain super call, for example an
 * upstream merge taking upstream's ungated version of the file, turns these into failures.
 * The PebbleKit 2 toggle and the provider's component state are checked to be on first, so a
 * refusal here is the registry gate's and not the toggle's or the system's.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.PebbleKitProviderGateTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class PebbleKitProviderGateTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requirePebbleKit2On() {
        val libPebble: LibPebble = GlobalContext.get().get()
        assertTrue(
            libPebble.config.value.watchConfig.pebbleKit2Enabled,
            "PebbleKit 2 is off on this device, so the registry gate would not be reached",
        )
        val provider = ComponentName(context.packageName, "io.rebble.libpebblecommon.pebblekit.two.PebbleKitProvider")
        assertNotEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(provider),
            "the .pebblekit provider component is disabled, so the system would refuse before the registry gate",
        )
    }

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
