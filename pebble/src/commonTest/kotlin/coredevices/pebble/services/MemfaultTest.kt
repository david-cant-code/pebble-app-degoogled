package coredevices.pebble.services

import com.russhwolf.settings.MapSettings
import coredevices.pebble.Platform
import coredevices.pebble.services.Memfault.Companion.serialForMemfault
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

    // Any request reaching the engine is a telemetry regression, most likely from
    // an upstream merge restoring the chunk upload body.
    private fun noNetworkClient() = HttpClient(MockEngine { request ->
        fail("Telemetry regression: unexpected HTTP request to ${request.url}")
    })

    @Test
    fun uploadChunkBatchSucceedsWithoutTouchingTheNetwork() = runTest {
        // Default settings leave uploads "enabled"; the fork must still send nothing.
        val memfault = Memfault(noNetworkClient(), MapSettings(), Platform.Android)
        assertTrue(memfault.uploadChunkBatch(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4)), "123456789012"))
    }

    @Test
    fun getLatestFirmwareWithoutTokenFailsWithoutTouchingTheNetwork() = runTest {
        // Pins the config layer: fork builds ship no MEMFAULT_TOKEN, so the update
        // check must fail fast and offline. If a token is ever configured, this
        // test flags that the defense-in-depth assumption changed.
        val memfault = Memfault(noNetworkClient(), MapSettings(), Platform.Android)
        val result = memfault.getLatestFirmware(createWatchInfo("123456789012", "B7:B8:CD:5E:F9:F5"))
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
    }
}