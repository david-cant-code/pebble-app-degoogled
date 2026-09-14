package io.rebble.libpebblecommon.pebblekit

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal const val CLASSIC_PROVIDER = "io.rebble.libpebblecommon.pebblekit.classic.PebbleKitProvider"
internal const val PK2_PROVIDER = "io.rebble.libpebblecommon.pebblekit.two.PebbleKitProvider"
internal const val PK2_SENDER = "io.rebble.libpebblecommon.pebblekit.two.PebbleSenderReceiver"

/**
 * Fork: pins which manifest component follows which toggle, by the class name the system is
 * handed, what each surface's applied state reads, and that the system state follows a toggle
 * after a call threw.
 */
class PebbleKitComponentStateTest {

    /** Stands in for PackageManager: a component's state changes only when its call returns. */
    private class FakeComponents {
        val enabled = mutableMapOf<String, Boolean>()
        var throwsFor: (className: String, enabled: Boolean) -> Boolean = { _, _ -> false }

        fun set(className: String, on: Boolean) {
            if (throwsFor(className, on)) throw IllegalStateException("refused")
            enabled[className] = on
        }
    }

    @Test
    fun eachComponentFollowsItsSurfaceToggle() {
        assertEquals(
            mapOf(CLASSIC_PROVIDER to true, PK2_PROVIDER to false, PK2_SENDER to false),
            PebbleKitComponentState.desiredStates(PebbleKitToggles(classic = true, pebbleKit2 = false)),
        )
        assertEquals(
            mapOf(CLASSIC_PROVIDER to false, PK2_PROVIDER to true, PK2_SENDER to true),
            PebbleKitComponentState.desiredStates(PebbleKitToggles(classic = false, pebbleKit2 = true)),
        )
    }

    @Test
    fun defaultsDisableOnlyTheClassicProvider() {
        assertEquals(
            mapOf(CLASSIC_PROVIDER to false, PK2_PROVIDER to true, PK2_SENDER to true),
            PebbleKitComponentState.desiredStates(WatchConfig().pebbleKitToggles()),
        )
    }

    @Test
    fun aSurfaceReadsAsEnabledOnlyOnceEveryCallForItReturned() = runTest {
        val config = MutableStateFlow(configWith(classic = false, pebbleKit2 = false))
        val components = FakeComponents()
        components.throwsFor = { className, on -> className == PK2_SENDER && on }
        val state = stateOf(components, config)
        assertFalse(state.appliedEnabled(PebbleKitSurface.Classic).first(), "classic read as enabled before the first apply")
        assertFalse(state.appliedEnabled(PebbleKitSurface.PebbleKit2).first(), "PebbleKit 2 read as enabled before the first apply")

        state.init()
        runCurrent()
        config.value = configWith(classic = true, pebbleKit2 = true)
        runCurrent()
        assertTrue(state.appliedEnabled(PebbleKitSurface.Classic).first(), "classic does not read as enabled after its enable returned")
        assertFalse(
            state.appliedEnabled(PebbleKitSurface.PebbleKit2).first(),
            "PebbleKit 2 reads as enabled although its sender's enable threw",
        )

        components.throwsFor = { _, _ -> false }
        config.value = config.value.withUnrelatedChange()
        runCurrent()
        assertTrue(state.appliedEnabled(PebbleKitSurface.PebbleKit2).first(), "the failed enable was not applied again")
    }

    @Test
    fun aSurfaceTurnedOffReadsAsOffEvenWhenItsDisableThrows() = runTest {
        val components = FakeComponents()
        components.throwsFor = { className, on -> className == CLASSIC_PROVIDER && !on }
        val state = stateOf(components, MutableStateFlow(LibPebbleConfig()))
        state.apply(PebbleKitToggles(classic = true, pebbleKit2 = true))
        assertTrue(state.appliedEnabled(PebbleKitSurface.Classic).first())
        state.apply(PebbleKitToggles(classic = false, pebbleKit2 = true))
        assertFalse(state.appliedEnabled(PebbleKitSurface.Classic).first(), "classic reads as enabled after it was turned off")
        assertTrue(state.appliedEnabled(PebbleKitSurface.PebbleKit2).first(), "a classic failure changed what PebbleKit 2 reads")
    }

    @Test
    fun turningPebbleKit2OffAfterAPartialEnableDisablesTheComponentThatWasEnabled() = runTest {
        val config = MutableStateFlow(configWith(classic = false, pebbleKit2 = false))
        val components = FakeComponents()
        stateOf(components, config).init()
        runCurrent()

        components.throwsFor = { className, on -> className == PK2_SENDER && on }
        config.value = configWith(classic = false, pebbleKit2 = true)
        runCurrent()
        assertEquals(true, components.enabled[PK2_PROVIDER])

        components.throwsFor = { _, _ -> false }
        config.value = configWith(classic = false, pebbleKit2 = false)
        runCurrent()
        assertEquals(false, components.enabled[PK2_PROVIDER], "the provider stayed enabled with PebbleKit 2 off")
    }

    @Test
    fun turningClassicOffAfterAThrowingReEnableDisablesIt() = runTest {
        val config = MutableStateFlow(configWith(classic = true, pebbleKit2 = true))
        val components = FakeComponents()
        stateOf(components, config).init()
        runCurrent()

        // A PebbleKit 2 flip applies every component again, and classic's re-enable throws.
        components.throwsFor = { className, on -> className == CLASSIC_PROVIDER && on }
        config.value = configWith(classic = true, pebbleKit2 = false)
        runCurrent()

        components.throwsFor = { _, _ -> false }
        config.value = configWith(classic = false, pebbleKit2 = false)
        runCurrent()
        assertEquals(false, components.enabled[CLASSIC_PROVIDER], "the classic provider stayed enabled with classic off")
    }

    @Test
    fun aSurfaceWhoseReEnableThrewReadsAsEnabledOnceTheCallReturns() = runTest {
        val config = MutableStateFlow(configWith(classic = true, pebbleKit2 = true))
        val components = FakeComponents()
        val state = stateOf(components, config)
        state.init()
        runCurrent()

        components.throwsFor = { className, on -> className == CLASSIC_PROVIDER && on }
        config.value = configWith(classic = true, pebbleKit2 = false)
        runCurrent()
        assertFalse(state.appliedEnabled(PebbleKitSurface.Classic).first())

        // A call that threw leaves its component's state unknown, so the next emission calls it again.
        components.throwsFor = { _, _ -> false }
        config.value = config.value.withUnrelatedChange()
        runCurrent()
        assertTrue(state.appliedEnabled(PebbleKitSurface.Classic).first(), "classic stayed off after its re-enable returned")
    }

    @Test
    fun aDisableThatThrewIsRetriedAtTheNextConfigEmission() = runTest {
        val config = MutableStateFlow(configWith(classic = true, pebbleKit2 = true))
        val components = FakeComponents()
        stateOf(components, config).init()
        runCurrent()

        components.throwsFor = { className, on -> className == CLASSIC_PROVIDER && !on }
        config.value = configWith(classic = false, pebbleKit2 = true)
        runCurrent()
        assertEquals(true, components.enabled[CLASSIC_PROVIDER])

        components.throwsFor = { _, _ -> false }
        config.value = config.value.withUnrelatedChange()
        runCurrent()
        assertEquals(false, components.enabled[CLASSIC_PROVIDER], "the failed disable was not retried")
    }

    private fun TestScope.stateOf(components: FakeComponents, config: MutableStateFlow<LibPebbleConfig>) =
        PebbleKitComponentState(
            setComponentEnabled = components::set,
            watchConfig = WatchConfigFlow(config),
            scope = backgroundScope,
        )

    private fun configWith(classic: Boolean, pebbleKit2: Boolean) =
        LibPebbleConfig(watchConfig = WatchConfig(classicPebbleKitEnabled = classic, pebbleKit2Enabled = pebbleKit2))

    private fun LibPebbleConfig.withUnrelatedChange() =
        copy(watchConfig = watchConfig.copy(calendarPins = !watchConfig.calendarPins))
}
