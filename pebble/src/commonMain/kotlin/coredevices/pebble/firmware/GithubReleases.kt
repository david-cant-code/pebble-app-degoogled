package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Update checker for Core watches backed by the public PebbleOS GitHub
 * releases (fork builds ship no Memfault token, and cohorts rejects every
 * Core hardware revision, so upstream has no working source for these
 * watches here).
 *
 * Privacy: the request names no hardware, serial, or version; the asset is
 * chosen client-side from the release list. Verification: the API declares a
 * sha256 digest and exact size per asset, which are recorded in
 * [FirmwareArtifactExpectations] for the installer to enforce; a release
 * whose asset lacks a usable digest is treated as having no asset at all.
 * Selection and comparison rules live in FirmwareReleaseSelection.kt.
 */
class GithubReleases(
    private val httpClient: HttpClient,
    private val expectations: FirmwareArtifactExpectations,
    // Provider, not a value: the channel is user-configurable and must be
    // re-read on every check.
    private val channel: () -> FirmwareUpdateChannel,
    private val clock: Clock,
) {
    private val logger = Logger.withTag("GithubReleases")

    suspend fun getLatestFirmware(watch: WatchInfo): FirmwareUpdateCheckResult {
        val runningRaw = watch.runningFwVersion.stringVersion
        val running = ReleaseTagVersion.from(runningRaw)
        if (running == null && !watch.runningFwVersion.isRecovery) {
            // Fail closed: with an incomparable running version any offer
            // could be a downgrade or a same-version loop. Recovery is exempt
            // because it always gets an offer regardless of comparison.
            logger.e { "Cannot parse running firmware version '$runningRaw'" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
        }
        val releases = when (val fetched = fetchReleases()) {
            is Fetched.Failure -> return FirmwareUpdateCheckResult.UpdateCheckFailed(fetched.message)
            is Fetched.Success -> fetched.releases
        }
        val revision = watch.platform.revision
        val candidates = releases.mapNotNull { it.toCandidate(revision) }
        val selected = selectRelease(candidates.map { it.selectable }, channel(), clock.now())
        if (selected == null) {
            logger.w { "No selectable PebbleOS release for '$revision' among ${releases.size} releases" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
        }
        val chosen = candidates.first { it.selectable === selected }
        // Strictly newer only: equality must never re-offer (see
        // ReleaseTagVersion), and a channel switch back from Early must never
        // offer a downgrade.
        if (!watch.runningFwVersion.isRecovery && running != null && chosen.selectable.version <= running) {
            return FirmwareUpdateCheckResult.FoundNoUpdate
        }
        val displayVersion = FirmwareVersion.from(
            tag = chosen.tagName,
            isRecovery = false,
            gitHash = "",
            // Display payload only. The offer decision above compares tags;
            // feeding publishedAt into a FirmwareVersion comparison would
            // re-offer equal versions on its timestamp tiebreak.
            timestamp = chosen.publishedAt,
            isDualSlot = false, // not used from here
            isSlot0 = false, // not used from here
        )
        if (displayVersion == null) {
            logger.e { "Couldn't build display version from '${chosen.tagName}'" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
        }
        expectations.record(
            checkNotNull(chosen.assetUrl),
            ExpectedFirmwareArtifact(
                sha256Hex = checkNotNull(chosen.digestHex),
                sizeBytes = checkNotNull(chosen.assetSize),
                versionTag = chosen.tagName,
            ),
        )
        return FirmwareUpdateCheckResult.FoundUpdate(
            version = displayVersion,
            url = checkNotNull(chosen.assetUrl),
            // Release bodies are empty upstream; the dialog and notification
            // already carry the version string.
            notes = "",
        )
    }

    private sealed class Fetched {
        data class Success(val releases: List<GithubReleaseDto>) : Fetched()
        data class Failure(val message: String) : Fetched()
    }

    private suspend fun fetchReleases(): Fetched {
        val response = try {
            httpClient.get(RELEASES_URL) {
                parameter("per_page", PAGE_SIZE)
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            }
        } catch (e: IOException) {
            logger.w(e) { "Network error fetching PebbleOS releases" }
            return Fetched.Failure(GENERIC_FAILURE)
        }
        if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.TooManyRequests) {
            // Unauthenticated GitHub quota is per-IP and the in-app check
            // cache keeps normal usage far below it, so this is transient.
            logger.w { "PebbleOS release fetch rate limited: ${response.status}" }
            return Fetched.Failure(RATE_LIMITED_FAILURE)
        }
        if (!response.status.isSuccess()) {
            logger.w { "PebbleOS release fetch failed: ${response.status}" }
            return Fetched.Failure(GENERIC_FAILURE)
        }
        return try {
            Fetched.Success(response.body<List<GithubReleaseDto>>())
        } catch (e: NoTransformationFoundException) {
            logger.w(e) { "Unexpected content fetching PebbleOS releases" }
            Fetched.Failure(GENERIC_FAILURE)
        } catch (e: ContentConvertException) {
            logger.w(e) { "Malformed PebbleOS release list" }
            Fetched.Failure(GENERIC_FAILURE)
        }
    }

    private fun GithubReleaseDto.toCandidate(revision: String): Candidate? {
        if (draft || prerelease) return null
        val version = ReleaseTagVersion.from(tagName)
        if (version == null) {
            logger.w { "Skipping unparseable release tag '$tagName'" }
            return null
        }
        val published = publishedAt?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
            ?: return null
        val asset = assets.firstOrNull { it.name == "normal_${revision}_${tagName}.pbz" }
        val digestHex = normalizeSha256Hex(asset?.digest)
        // An asset the fork cannot verify is treated as absent, so selection
        // walks to a release it can verify instead of offering this one.
        val usable = asset != null && digestHex != null && asset.size > 0
        return Candidate(
            selectable = SelectableRelease(version, published, hasAsset = usable),
            tagName = tagName,
            publishedAt = published,
            assetUrl = asset?.browserDownloadUrl,
            assetSize = asset?.size,
            digestHex = digestHex,
        )
    }

    private data class Candidate(
        val selectable: SelectableRelease,
        val tagName: String,
        val publishedAt: Instant,
        val assetUrl: String?,
        val assetSize: Long?,
        val digestHex: String?,
    )

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/coredevices/PebbleOS/releases"

        // ~20 releases spans several weeks of both tag lines: enough history
        // for the soak policy and for the asset walk-forward on new hardware.
        private const val PAGE_SIZE = 20
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val GENERIC_FAILURE = "Failed to check for PebbleOS update"
        private const val RATE_LIMITED_FAILURE = "PebbleOS update check is rate limited, try again later"
    }
}

@Serializable
data class GithubReleaseDto(
    @SerialName("tag_name")
    val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val assets: List<GithubReleaseAssetDto> = emptyList(),
)

@Serializable
data class GithubReleaseAssetDto(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val digest: String? = null,
    val size: Long = 0,
)
