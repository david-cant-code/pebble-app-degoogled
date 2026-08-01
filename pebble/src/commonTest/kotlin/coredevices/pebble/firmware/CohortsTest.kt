package coredevices.pebble.firmware

import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins the fork's addition to the cohorts checker: the server's sha-256,
 * which upstream parses and discards, is recorded for the verified installer.
 * A malformed hash records nothing (the installer then refuses that download)
 * but must not break the offer itself.
 */
class CohortsTest {

    @Test
    fun recordsServerSha256WhenOfferingAnUpdate() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val result = testCohorts(jsonRespondingClient(cohortsBody("B".repeat(64))), expectations)
            .getLatestFirmware(testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0"))
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals(
            ExpectedFirmwareArtifact(
                sha256Hex = "b".repeat(64),
                sizeBytes = null,
                versionTag = "v4.4.3-rbl",
            ),
            expectations.lookup(update.url),
        )
    }

    @Test
    fun malformedSha256RecordsNothingButStillOffers() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val result = testCohorts(jsonRespondingClient(cohortsBody("not-a-hash")), expectations)
            .getLatestFirmware(testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0"))
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertNull(expectations.lookup(update.url))
    }
}
