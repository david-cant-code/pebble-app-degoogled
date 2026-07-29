package coredevices.pebble.services

import com.russhwolf.settings.MapSettings
import coredevices.pebble.Platform
import coredevices.pebble.services.Memfault.Companion.serialForMemfault
import coredevices.util.CommonBuildKonfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

class MemfaultTest {
    private fun createWatchInfo(serial: String, mac: String) = WatchInfo(
        runningFwVersion = FirmwareVersion(
            "v3.8",
            Instant.DISTANT_PAST,
            8,
            0,
            0,
            "",
            "ABCDEF",
            false,
            isDualSlot = false,
            isSlot0 = false,
        ),
        recoveryFwVersion = FirmwareVersion(
            "v3.8",
            Instant.DISTANT_PAST,
            8,
            0,
            0,
            "",
            "ABCDEF",
            true,
            isDualSlot = false,
            isSlot0 = false,
        ),
        platform = WatchHardwarePlatform.CORE_ASTERIX,
        bootloaderTimestamp = Instant.DISTANT_PAST,
        board = "basalt_ev2",
        serial = serial,
        btAddress = mac,
        resourceCrc = 0,
        resourceTimestamp = Instant.DISTANT_PAST,
        language = "en_US",
        languageVersion = 2,
        capabilities = emptySet(),
        isUnfaithful = false,
        healthInsightsVersion = 1,
        javascriptVersion = 1,
        color = WatchColor.ClassicFlyBlue,
    )

    @Test
    fun xxxxxxxxxxxxSerial() {
        assertEquals("XXXXB7B8CD5E", createWatchInfo(
            serial = "XXXXXXXXXXXX",
            mac = "F5:F9:5E:CD:B8:B7"
        ).serialForMemfault())
    }

    @Test
    fun normalSerial() {
        assertEquals("123456789012", createWatchInfo(
            serial = "123456789012",
            mac = "B7:B8:CD:5E:F9:F5"
        ).serialForMemfault())
    }

    // Any request reaching the engine violates the fork's no-network guarantee
    // for the code path under test.
    private fun noNetworkClient() = HttpClient(MockEngine { request ->
        fail("No-network invariant violated: unexpected HTTP request to ${request.url}")
    })

    @Test
    fun uploadChunkBatchSendsNothingEvenWithATokenConfigured() = runTest {
        // Strongest pro-upload configuration: a token is present and default
        // settings leave uploads "enabled". The fork must still send nothing.
        // The token is injected so this holds regardless of the build's
        // memfaultToken property.
        val memfault = Memfault(noNetworkClient(), MapSettings(), Platform.Android, memfaultToken = "test-token")
        assertTrue(memfault.uploadChunkBatch(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4)), "123456789012"))
    }

    @Test
    fun getLatestFirmwareWithoutTokenFailsWithoutTouchingTheNetwork() = runTest {
        // Pins the tokenless behavior of the kept firmware feature: fail fast,
        // offline. Token injected as null so this is deterministic in any build.
        val memfault = Memfault(noNetworkClient(), MapSettings(), Platform.Android, memfaultToken = null)
        val result = memfault.getLatestFirmware(createWatchInfo("123456789012", "B7:B8:CD:5E:F9:F5"))
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
    }

    @Test
    fun defaultBuildShipsNoMemfaultToken() {
        // Pins the build config layer: fork builds ship no Memfault token, so the
        // kept firmware update check never contacts api.memfault.com (those
        // requests would carry the watch serial, see the README note). If you
        // configured memfaultToken deliberately, update this test and the README
        // together.
        assertNull(CommonBuildKonfig.MEMFAULT_TOKEN)
    }
}