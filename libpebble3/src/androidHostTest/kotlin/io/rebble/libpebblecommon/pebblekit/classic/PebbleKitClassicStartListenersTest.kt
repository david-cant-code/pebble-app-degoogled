package io.rebble.libpebblecommon.pebblekit.classic

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.pebblekit.PebbleKitSurface
import io.rebble.libpebblecommon.pebblekit.pebbleKitSurfaceEnabled
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Fork: pins that the classic START/STOP receivers exist only while the classic toggle is
 * on. The intent flows are stand-ins whose subscription count is the registration state: the
 * real flows register their receiver on collection and unregister on cancellation.
 */
class PebbleKitClassicStartListenersTest {
    private val appA = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val appB = Uuid.parse("00000000-0000-0000-0000-0000000000bb")

    @Test
    fun receiversFollowTheToggle() = runTest {
        val enabled = MutableStateFlow(false)
        val starts = MutableSharedFlow<Uuid>()
        val stops = MutableSharedFlow<Uuid>()
        val launched = mutableListOf<Uuid>()
        val stopped = mutableListOf<Uuid>()
        PebbleKitClassicStartListeners(
            classicEnabled = enabled,
            startRequests = starts,
            stopRequests = stops,
            launchApp = { launched += it },
            stopApp = { stopped += it },
            scope = backgroundScope,
        ).init()
        runCurrent()
        assertEquals(0, starts.subscriptionCount.value, "START registered while off")
        assertEquals(0, stops.subscriptionCount.value, "STOP registered while off")

        enabled.value = true
        runCurrent()
        assertEquals(1, starts.subscriptionCount.value, "START not registered after toggle on")
        assertEquals(1, stops.subscriptionCount.value, "STOP not registered after toggle on")

        starts.emit(appA)
        stops.emit(appB)
        runCurrent()
        assertEquals(listOf(appA), launched)
        assertEquals(listOf(appB), stopped)

        enabled.value = false
        runCurrent()
        assertEquals(0, starts.subscriptionCount.value, "START still registered after toggle off")
        assertEquals(0, stops.subscriptionCount.value, "STOP still registered after toggle off")

        // A broadcast that lands while off reaches nothing. emit() on a flow with no
        // subscriber returns without delivering, which is exactly the unregistered case.
        starts.emit(appB)
        runCurrent()
        assertEquals(listOf(appA), launched)

        enabled.value = true
        runCurrent()
        assertEquals(1, starts.subscriptionCount.value, "START not re-registered after toggle on")
        starts.emit(appB)
        runCurrent()
        assertEquals(listOf(appA, appB), launched)
    }

    @Test
    fun anUnrelatedConfigSaveDoesNotReRegister() = runTest {
        // The production mapping: a config save re-emits the whole config, and the toggle
        // value it maps to repeats, which must not tear the receivers down and back up.
        val config = MutableStateFlow(LibPebbleConfig(watchConfig = WatchConfig(classicPebbleKitEnabled = true)))
        val starts = MutableSharedFlow<Uuid>()
        val stops = MutableSharedFlow<Uuid>()
        var registrations = 0
        PebbleKitClassicStartListeners(
            classicEnabled = WatchConfigFlow(config).pebbleKitSurfaceEnabled(PebbleKitSurface.Classic),
            startRequests = flow { registrations++; starts.collect { emit(it) } },
            stopRequests = stops,
            launchApp = {},
            stopApp = {},
            scope = backgroundScope,
        ).init()
        runCurrent()
        assertEquals(1, registrations)

        config.value = config.value.copy(watchConfig = config.value.watchConfig.copy(calendarPins = false))
        runCurrent()
        assertEquals(1, registrations, "an unrelated config save re-registered the receivers")
    }
}
