package io.rebble.libpebblecommon.pebblekit

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.pebblekit.classic.PebbleKitClassicStartListeners
import io.rebble.libpebblecommon.pebblekit.classic.PebbleKitProviderNotifier
import io.rebble.libpebblecommon.pebblekit.two.pebbleKit2Tracking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork: pins which surface each runtime consumer follows, through the functions its production
 * factory uses, by giving the two surfaces different toggles or different applied states.
 */
class PebbleKitSurfaceWiringTest {

    private val bothOn = WatchConfig(classicPebbleKitEnabled = true, pebbleKit2Enabled = true)
    private val defaults = WatchConfig()

    @Test
    fun theBasaltNotifierFollowsTheClassicToggleAndItsComponentAsApplied() = runTest {
        assertTrue(
            PebbleKitProviderNotifier.classicEnabled(configFlow(bothOn), stateApplying(bothOn, failing = PK2_SENDER)).first(),
        )
        assertFalse(
            PebbleKitProviderNotifier.classicEnabled(configFlow(bothOn), stateApplying(bothOn, failing = CLASSIC_PROVIDER)).first(),
            "the notifier reads as enabled although the classic provider's enable threw",
        )
        // The config goes off first: the component is still applied-on while apply(off) runs.
        assertFalse(
            PebbleKitProviderNotifier.classicEnabled(configFlow(defaults), stateApplying(bothOn)).first(),
            "the notifier reads as enabled although the classic toggle is off",
        )
    }

    @Test
    fun thePebbleKit2ProviderStateFollowsThePebbleKit2ToggleAndComponents() = runTest {
        assertFalse(pebbleKit2Tracking(configFlow(bothOn), stateApplying(bothOn, failing = PK2_SENDER)).first())
        assertTrue(pebbleKit2Tracking(configFlow(bothOn), stateApplying(bothOn, failing = CLASSIC_PROVIDER)).first())
        assertTrue(pebbleKit2Tracking(configFlow(defaults), stateApplying(defaults)).first())
    }

    @Test
    fun theStartListenersFollowTheClassicToggle() = runTest {
        assertFalse(PebbleKitClassicStartListeners.classicEnabled(configFlow(defaults)).first())
        assertTrue(
            PebbleKitClassicStartListeners.classicEnabled(configFlow(WatchConfig(classicPebbleKitEnabled = true, pebbleKit2Enabled = false))).first(),
        )
    }

    private fun configFlow(watchConfig: WatchConfig) = WatchConfigFlow(MutableStateFlow(LibPebbleConfig(watchConfig = watchConfig)))

    /** A component state after applying [watchConfig]'s toggles, with [failing]'s call throwing. */
    private fun TestScope.stateApplying(watchConfig: WatchConfig, failing: String? = null) =
        PebbleKitComponentState(
            setComponentEnabled = { className, _ -> if (className == failing) throw IllegalStateException("refused") },
            watchConfig = configFlow(watchConfig),
            scope = backgroundScope,
        ).also { it.apply(watchConfig.pebbleKitToggles()) }
}
