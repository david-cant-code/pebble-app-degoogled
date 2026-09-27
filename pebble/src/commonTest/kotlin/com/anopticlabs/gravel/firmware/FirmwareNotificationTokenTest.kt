package com.anopticlabs.gravel.firmware

import io.rebble.libpebblecommon.connection.FakeConnectedDevice
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckState
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.fakeWatch
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareNotificationTokenTest {

    private val token = "0123456789abcdef0123456789abcdef"

    private fun route(
        watch: String? = "watch-1",
        tokenExtra: String? = token,
        launchedFromHistory: Boolean = false,
        coreWatches: Set<String> = setOf("watch-1"),
    ) = firmwarePickerRouteForNotification(
        readExtra = { name ->
            when (name) {
                EXTRA_FIRMWARE_PICKER_WATCH -> watch
                EXTRA_FIRMWARE_PICKER_TOKEN -> tokenExtra
                else -> error(name)
            }
        },
        launchedFromHistory = launchedFromHistory,
        isConnectedCoreWatch = { it in coreWatches },
        expectedToken = token,
    )

    @Test
    fun theProcessTokenOpensThePickerForAConnectedCoreWatch() {
        assertEquals(FirmwarePickerRoute("watch-1"), route())
    }

    @Test
    fun withoutAnExpectedTokenThePickerOpensForTheProcessToken() {
        val route = firmwarePickerRouteForNotification(
            readExtra = { name ->
                when (name) {
                    EXTRA_FIRMWARE_PICKER_WATCH -> "watch-1"
                    EXTRA_FIRMWARE_PICKER_TOKEN -> FirmwareNotificationToken.value
                    else -> error(name)
                }
            },
            launchedFromHistory = false,
            isConnectedCoreWatch = { true },
        )
        assertEquals(FirmwarePickerRoute("watch-1"), route)
    }

    @Test
    fun anyOtherTokenIsHandledAsALink() {
        assertNull(route(tokenExtra = null))
        assertNull(route(tokenExtra = ""))
        assertNull(route(tokenExtra = token.dropLast(1)))
        assertNull(route(tokenExtra = token.uppercase()))
        assertNull(route(watch = null))
    }

    @Test
    fun aLegacyOrDisconnectedWatchIsHandledAsALink() {
        assertNull(route(coreWatches = emptySet()))
        assertNull(route(watch = "watch-2"))
    }

    @Test
    fun aRelaunchFromRecentsIsHandledAsALink() {
        assertNull(route(launchedFromHistory = true))
    }

    @Test
    fun extrasThatCannotBeReadAreHandledAsALink() {
        val route = firmwarePickerRouteForNotification(
            readExtra = { throw IllegalStateException("unparcel") },
            launchedFromHistory = false,
            isConnectedCoreWatch = { true },
            expectedToken = token,
        )
        assertNull(route)
    }

    @Test
    fun thePickerServesConnectedCoreWatchesOnly() {
        fun connected(platform: WatchHardwarePlatform) = FakeConnectedDevice(
            identifier = "00:11:22:33:44:55".asPebbleBleIdentifier(),
            firmwareUpdateAvailable = FirmwareUpdateCheckState(false, null),
            firmwareUpdateState = FirmwareUpdater.FirmwareUpdateStatus.NotInProgress.Idle(),
            name = "watch",
            nickname = null,
            watchType = platform,
            connectionFailureInfo = null,
        )
        assertTrue(connected(WatchHardwarePlatform.CORE_ASTERIX).firmwarePickerServes())
        assertTrue(connected(WatchHardwarePlatform.CORE_OBELIX_PVT).firmwarePickerServes())
        assertFalse(connected(WatchHardwarePlatform.PEBBLE_SNOWY_DVT).firmwarePickerServes())
        assertFalse(connected(WatchHardwarePlatform.PEBBLE_SILK).firmwarePickerServes())
        assertFalse(connected(WatchHardwarePlatform.UNKNOWN).firmwarePickerServes())
        assertFalse(fakeWatch(connected = false).firmwarePickerServes())
    }

    @Test
    fun tokensAreRandomUuidsInHexAndStableWithinTheProcess() {
        val first = newFirmwareNotificationToken()
        assertNotEquals(first, newFirmwareNotificationToken())
        // Version 4 and variant nibbles of Uuid.random.
        assertTrue(Regex("[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}").matches(first), first)
        assertEquals(FirmwareNotificationToken.value, FirmwareNotificationToken.value)
        assertTrue(Regex("[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}").matches(FirmwareNotificationToken.value))
        assertNotEquals(FirmwareNotificationToken.value, newFirmwareNotificationToken())
    }
}
