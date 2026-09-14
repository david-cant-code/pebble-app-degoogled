package io.rebble.libpebblecommon.pebblekit.classic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fork: pins that basalt provider change notifications fire only while the classic toggle is
 * on, and once when it turns on, so a client that kept observing re-reads the provider.
 */
class PebbleKitProviderNotifierTest {

    @Test
    fun notifiesOnlyWhileClassicIsOn() = runTest {
        val connected = MutableStateFlow(false)
        val enabled = MutableStateFlow(false)
        var notifications = 0
        PebbleKitProviderNotifier(
            watchConnected = connected,
            classicEnabled = enabled,
            notifyChange = { notifications++ },
            scope = backgroundScope,
        ).init()
        runCurrent()
        assertEquals(0, notifications, "notified while off")

        connected.value = true
        runCurrent()
        assertEquals(0, notifications, "a connection change notified while off")

        enabled.value = true
        runCurrent()
        assertEquals(1, notifications, "turning the toggle on did not notify")

        connected.value = false
        runCurrent()
        assertEquals(2, notifications, "a connection change did not notify while on")

        enabled.value = false
        runCurrent()
        connected.value = true
        runCurrent()
        assertEquals(2, notifications, "notified after the toggle went off")
    }
}
