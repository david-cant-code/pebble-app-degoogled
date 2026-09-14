package io.rebble.libpebblecommon.pebblekit.two

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.pebblekit.PebbleKitComponentState
import io.rebble.libpebblecommon.pebblekit.PebbleKitToggles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fork: pins what the `.pebblekit` provider's state tracks and announces (collection changes by
 * type, never a single watch), and that it tracks and announces nothing while PebbleKit 2 is off.
 */
class PebbleKit2ProviderStateTest {

    private val watchA = mapOf("ID" to "SERIAL-A", "NAME" to "Pebble")
    private val watchB = mapOf("ID" to "SERIAL-B", "NAME" to "Pebble Time")
    private val faceApp = mapOf("ID" to "uuid-1", "NAME" to "Face", "TYPE" to 0)

    private class Harness {
        val enabled = MutableStateFlow(true)
        val connected = MutableStateFlow<List<Map<String, Any?>>>(emptyList())
        val activeApps = mutableMapOf<String, MutableStateFlow<Map<String, Any?>?>>()
        val notifications = mutableListOf<PebbleKit2Change>()

        fun activeApp(serial: String) = activeApps.getOrPut(serial) { MutableStateFlow(null) }
    }

    private fun kotlinx.coroutines.test.TestScope.stateOf(h: Harness) = PebbleKit2ProviderState(
        enabled = h.enabled,
        connectedWatches = h.connected,
        activeAppOf = { serial -> h.activeApp(serial) },
        idColumn = "ID",
        notify = { h.notifications += it },
        scope = backgroundScope,
    ).also { it.init() }

    @Test
    fun tracksWatchesAndAnnouncesCollectionChanges() = runTest {
        val h = Harness()
        val state = stateOf(h)
        runCurrent()
        assertEquals(listOf(PebbleKit2Change.ConnectedWatches), h.notifications, "the empty initial list announces once")

        h.connected.value = listOf(watchA)
        runCurrent()
        assertEquals(listOf(watchA), state.connectedWatches())
        assertEquals(listOf("SERIAL-A"), state.connectedSerials())
        assertNull(state.activeApp("SERIAL-A"), "an unknown running app was served")
        assertEquals(2, h.notifications.size)
        assertEquals(PebbleKit2Change.ConnectedWatches, h.notifications.last())

        h.activeApp("SERIAL-A").value = faceApp
        runCurrent()
        assertEquals(faceApp, state.activeApp("SERIAL-A"))
        assertEquals(PebbleKit2Change.ActiveApps, h.notifications.last())
        assertEquals(3, h.notifications.size)

        // The same running app again is not a change.
        h.activeApp("SERIAL-A").value = faceApp.toMap()
        runCurrent()
        assertEquals(3, h.notifications.size, "an unchanged running app announced")

        // A disconnected watch takes its running app with it, announced once.
        h.connected.value = listOf(watchB)
        runCurrent()
        assertNull(state.activeApp("SERIAL-A"), "a disconnected watch kept its running app")
        assertEquals(
            listOf(PebbleKit2Change.ConnectedWatches, PebbleKit2Change.ActiveApps),
            h.notifications.takeLast(2),
        )
    }

    @Test
    fun anUnchangedWatchListAnnouncesNothing() = runTest {
        val h = Harness()
        h.connected.value = listOf(watchA)
        stateOf(h)
        runCurrent()
        val before = h.notifications.size

        h.connected.value = listOf(watchA.toMap())
        runCurrent()
        assertEquals(before, h.notifications.size, "an equal watch list announced")
    }

    @Test
    fun followsTheToggle() = runTest {
        val h = Harness()
        h.enabled.value = false
        h.connected.value = listOf(watchA)
        h.activeApp("SERIAL-A").value = faceApp
        val state = stateOf(h)
        runCurrent()
        assertEquals(emptyList(), state.connectedWatches(), "tracked while off")
        assertNull(state.activeApp("SERIAL-A"), "served a running app while off")
        assertEquals(emptyList(), h.notifications, "announced while off")

        h.enabled.value = true
        runCurrent()
        assertEquals(listOf(watchA), state.connectedWatches())
        assertEquals(faceApp, state.activeApp("SERIAL-A"))
        assertEquals(listOf(PebbleKit2Change.ConnectedWatches, PebbleKit2Change.ActiveApps), h.notifications)

        h.enabled.value = false
        runCurrent()
        assertEquals(emptyList(), state.connectedWatches(), "kept watches after toggle off")
        assertNull(state.activeApp("SERIAL-A"), "kept a running app after toggle off")
        assertEquals(2, h.notifications.size, "announced the toggle off")

        // Changes that land while off reach nothing and are not replayed.
        h.connected.value = listOf(watchA, watchB)
        runCurrent()
        assertEquals(2, h.notifications.size, "announced a change while off")
    }

    @Test
    fun aNewWatchListCancelsThePreviousCollectors() = runTest {
        val h = Harness()
        val state = stateOf(h)
        repeat(10) {
            h.connected.value = listOf(watchA)
            runCurrent()
            h.connected.value = emptyList()
            runCurrent()
        }
        h.connected.value = listOf(watchA, watchB)
        runCurrent()
        assertEquals(1, h.activeApp("SERIAL-A").subscriptionCount.value, "collectors for A piled up across list changes")
        assertEquals(1, h.activeApp("SERIAL-B").subscriptionCount.value)

        h.connected.value = listOf(watchB)
        runCurrent()
        assertEquals(0, h.activeApp("SERIAL-A").subscriptionCount.value, "a collector outlived its watch's disconnect")
        assertEquals(1, h.activeApp("SERIAL-B").subscriptionCount.value)
        assertEquals(listOf(watchB), state.connectedWatches())
    }

    @Test
    fun tracksOnlyWhileTheToggleIsOnAndItsComponentsAreEnabled() = runTest {
        val config = MutableStateFlow(LibPebbleConfig(watchConfig = WatchConfig(pebbleKit2Enabled = true)))
        val componentState = PebbleKitComponentState({ _, _ -> }, WatchConfigFlow(config), backgroundScope)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { pebbleKit2Tracking(WatchConfigFlow(config), componentState).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(false), seen, "tracked before the components were enabled")
        componentState.apply(PebbleKitToggles(classic = false, pebbleKit2 = true))
        runCurrent()
        assertEquals(listOf(false, true), seen)
        config.value = LibPebbleConfig(watchConfig = WatchConfig(pebbleKit2Enabled = false))
        runCurrent()
        assertEquals(listOf(false, true, false), seen, "kept tracking after the toggle went off")
    }

    @Test
    fun projectionFollowsTheLibraryRule() {
        val all = listOf("ID", "NAME", "TYPE")
        assertEquals(all, projectedColumns(null, all))
        assertEquals(listOf("NAME", "ID"), projectedColumns(listOf("NAME", "bogus", null, "ID"), all))
        assertEquals(emptyList(), projectedColumns(listOf("bogus"), all))
        assertEquals(listOf("Face", null), rowValues(mapOf("NAME" to "Face"), listOf("NAME", "TYPE")))
    }

    @Test
    fun pseudonymisedRowsNeverCarryTheSerial() {
        val rows = pseudonymiseRows(listOf(watchA, watchB), "ID") { serial ->
            if (serial == "SERIAL-A") "pseudo-a" else null
        }
        assertEquals(listOf(mapOf("ID" to "pseudo-a", "NAME" to "Pebble")), rows)
    }
}
