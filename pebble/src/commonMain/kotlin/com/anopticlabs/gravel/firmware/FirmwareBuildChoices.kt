package com.anopticlabs.gravel.firmware

import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import kotlin.time.Instant

/** Whether Core's changelog names a build; Unknown when the changelog list could not be read. */
enum class ChangelogListing { Listed, NotListed, Unknown }

data class FirmwareBuildChoice(
    /** Its integrity expectation is recorded for the verified installer. */
    val update: FirmwareUpdateCheckResult.FoundUpdate,
    val listing: ChangelogListing,
    /** The build an update check would offer from the same list and releases. */
    val isRecommended: Boolean,
)

sealed class FirmwareBuildChoicesResult {
    data class Success(
        /** Highest version first. */
        val builds: List<FirmwareBuildChoice>,
        /** Null when the changelog list could not be read. */
        val listCheckedAt: Instant?,
    ) : FirmwareBuildChoicesResult()

    /** [retryable] is false when loading again would fail the same way. */
    data class Failed(val message: String, val retryable: Boolean = true) : FirmwareBuildChoicesResult()
}
