package coredevices.pebble.firmware

import CoreAppVersion
import co.touchlab.kermit.Logger
import coredevices.pebble.account.bootConfigPlatform
import coredevices.pebble.services.HttpClientAuthType
import coredevices.pebble.services.PebbleHttpClient
import coredevices.pebble.services.PebbleHttpClient.Companion.get
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

class Cohorts(
    private val httpClient: PebbleHttpClient,
    private val appVersion: CoreAppVersion,
    // Fork: carries the response's sha-256 to the verified installer.
    private val expectations: FirmwareArtifactExpectations,
) {
    private val logger = Logger.withTag("Cohorts")

    suspend fun getLatestFirmware(watch: WatchInfo): FirmwareUpdateCheckResult {
        logger.v { "getLatestFirmware" }
        val hardware = watch.platform.revision
        val platform = bootConfigPlatform()
        val parameters = mapOf(
            "select" to "fw",
            "hardware" to hardware,
            "mobilePlatform" to platform,
            "mobileVersion" to appVersion.version,
            "mobileHardware" to platform,
            "pebbleAppVersion" to appVersion.version,
        )
        val response: CohortsResponse? = httpClient.get(
            "$COHORTS_URL/cohort",
            auth = HttpClientAuthType.PebbleOptional,
            parameters = parameters,
        )
        if (response == null) {
            logger.i { "No response from cohorts" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }
        val normalFw = response.fw?.normal
        if (normalFw == null) {
            logger.i { "No firmware found for $hardware" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }
        val latestFwVersion = FirmwareVersion.from(
            tag = normalFw.friendlyVersion,
            isRecovery = false,
            gitHash = "", // TODO
            timestamp = Instant.fromEpochSeconds(normalFw.timestamp),
            isDualSlot = false, // not used from here
            isSlot0 = false, // not used from here
        )
        if (latestFwVersion == null) {
            logger.e { "Couldn't parse firmware version from response" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }
        if (watch.runningFwVersion.isRecovery || latestFwVersion > watch.runningFwVersion) {
            // Fork: upstream parses sha-256 but never consumes it. Record it so
            // the verified installer holds legacy downloads to it too; if the
            // server ever stops sending a real sha256 hex the installer will
            // refuse the download (fail closed) rather than skip verification.
            val sha256Hex = normalizeSha256Hex(normalFw.sha256)
            if (sha256Hex != null) {
                expectations.record(
                    normalFw.url,
                    ExpectedFirmwareArtifact(
                        sha256Hex = sha256Hex,
                        sizeBytes = null,
                        versionTag = normalFw.friendlyVersion,
                    ),
                )
            } else {
                logger.w { "Cohorts sha-256 is not a sha256 hex string; install will be refused" }
            }
            return FirmwareUpdateCheckResult.FoundUpdate(
                version = latestFwVersion,
                url = normalFw.url,
                notes = normalFw.notes.orEmpty(),
            )
        } else {
            return FirmwareUpdateCheckResult.FoundNoUpdate
        }
    }

    companion object {
        private const val COHORTS_URL = "https://cohorts.rebble.io"
    }
}

@Serializable
data class CohortsResponse(
    val fw: CohortsFirmwareType? = null,
)

@Serializable
data class CohortsFirmwareType(
    val normal: CohortsFirmware? = null,
    val recovery: CohortsFirmware? = null,
)

@Serializable
data class CohortsFirmware(
    val url: String,
    @SerialName("sha-256")
    val sha256: String,
    val friendlyVersion: String,
    val timestamp: Long,
    val notes: String? = null,
)
