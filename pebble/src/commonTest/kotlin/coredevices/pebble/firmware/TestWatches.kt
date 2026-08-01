package coredevices.pebble.firmware

import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlin.time.Instant

/**
 * WatchInfo fixtures for firmware-update tests. The running version is built
 * from the real parser when possible so upstream comparison code (the cohorts
 * path) sees consistent numeric fields; deliberately unparseable tags fall
 * back to a hand-built value so the fail-closed paths can be exercised.
 */
fun testFwVersion(tag: String, isRecovery: Boolean = false): FirmwareVersion =
    FirmwareVersion.from(
        tag = tag,
        isRecovery = isRecovery,
        gitHash = "",
        timestamp = Instant.parse("2026-07-01T00:00:00Z"),
        isDualSlot = false,
        isSlot0 = false,
    ) ?: FirmwareVersion(
        stringVersion = tag,
        timestamp = Instant.parse("2026-07-01T00:00:00Z"),
        major = 0,
        minor = 0,
        patch = 0,
        suffix = "",
        gitHash = "",
        isRecovery = isRecovery,
        isDualSlot = false,
        isSlot0 = false,
    )

const val TEST_WATCH_SERIAL = "TESTSERIAL01"

fun testWatchInfo(
    platform: WatchHardwarePlatform,
    runningTag: String,
    isRecovery: Boolean = false,
): WatchInfo = WatchInfo(
    runningFwVersion = testFwVersion(runningTag, isRecovery),
    recoveryFwVersion = null,
    platform = platform,
    bootloaderTimestamp = Instant.DISTANT_PAST,
    board = "test-board",
    serial = TEST_WATCH_SERIAL,
    btAddress = "00:11:22:33:44:55",
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
