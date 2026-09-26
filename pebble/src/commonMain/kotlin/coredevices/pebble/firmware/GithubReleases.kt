package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.firmware.ChangelogListResult
import com.anopticlabs.gravel.firmware.PebbleOsChangelogListSource
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
import kotlin.time.Instant

/**
 * Update checker for Core watches backed by the public PebbleOS GitHub
 * releases (fork builds ship no Memfault token, and cohorts rejects every
 * Core hardware revision, so upstream has no working source for these
 * watches here).
 *
 * Offers only versions Core's public changelog names: the highest listed
 * version newer than the running one whose release has a verifiable asset
 * for the watch's board. In recovery the highest such version is offered
 * whatever the running version.
 *
 * Privacy: no request carries the watch's serial, hardware revision, or
 * running version, and which requests are made depends only on the list and
 * the release page; the asset is chosen client-side. Verification: the API
 * declares a sha256 digest and exact size per asset, which are recorded in
 * [FirmwareArtifactExpectations] for the installer to enforce; an asset
 * without a usable digest and size counts as absent.
 */
class GithubReleases(
    private val httpClient: HttpClient,
    private val expectations: FirmwareArtifactExpectations,
    private val changelogList: PebbleOsChangelogListSource,
) {
    private val logger = Logger.withTag("GithubReleases")

    suspend fun getLatestFirmware(watch: WatchInfo): FirmwareUpdateCheckResult {
        val listed = when (val result = changelogList.fetch()) {
            is ChangelogListResult.Success -> result.list.versions
            ChangelogListResult.RateLimited -> return FirmwareUpdateCheckResult.UpdateCheckFailed(RATE_LIMITED_FAILURE)
            ChangelogListResult.Unreadable -> return FirmwareUpdateCheckResult.UpdateCheckFailed(LIST_FAILURE)
        }
        val page = when (val fetched = fetch<List<GithubReleaseDto>>(RELEASES_URL, perPage = PAGE_SIZE)) {
            is Fetched.Success -> fetched.value
            Fetched.NotFound -> return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
            is Fetched.Failure -> return FirmwareUpdateCheckResult.UpdateCheckFailed(fetched.message)
        }
        // The one release looked up outside the page. The page and this
        // lookup are requested whatever the watch runs, before anything about
        // the watch is read.
        val highestTag = "v${listed.max().raw}"
        val lookedUp = if (page.any { it.tagName == highestTag }) null else {
            when (val fetched = fetch<GithubReleaseDto>("$RELEASES_URL/tags/$highestTag")) {
                is Fetched.Success -> fetched.value.takeIf { it.tagName == highestTag }
                Fetched.NotFound -> null
                is Fetched.Failure -> return FirmwareUpdateCheckResult.UpdateCheckFailed(fetched.message)
            }
        }
        val runningRaw = watch.runningFwVersion.stringVersion
        val running = ReleaseTagVersion.from(runningRaw)
        val isRecovery = watch.runningFwVersion.isRecovery
        if (running == null && !isRecovery) {
            // Fail closed: with an incomparable running version any offer
            // could be a downgrade or a same-version loop. Recovery is exempt
            // because it always gets an offer regardless of comparison.
            logger.e { "Cannot parse running firmware version '$runningRaw'" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
        }
        // Strictly newer only: equality must never re-offer (see ReleaseTagVersion).
        val newer = (if (isRecovery) listed else listed.filter { it > checkNotNull(running) }).sortedDescending()
        if (newer.isEmpty()) {
            return FirmwareUpdateCheckResult.FoundNoUpdate
        }
        val build = findListedBuild(newer, page, highestTag, lookedUp, watch.platform.revision)
            ?: return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
        val displayVersion = FirmwareVersion.from(
            tag = build.tag,
            isRecovery = false,
            gitHash = "",
            // Display payload only. The offer decision above compares tags;
            // feeding publishedAt into a FirmwareVersion comparison would
            // re-offer equal versions on its timestamp tiebreak.
            timestamp = build.publishedAt,
            isDualSlot = false, // not used from here
            isSlot0 = false, // not used from here
        )
        if (displayVersion == null) {
            logger.e { "Couldn't build display version from '${build.tag}'" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
        }
        expectations.record(
            build.assetUrl,
            ExpectedFirmwareArtifact(
                sha256Hex = build.sha256Hex,
                sizeBytes = build.assetSize,
                versionTag = build.tag,
            ),
        )
        return FirmwareUpdateCheckResult.FoundUpdate(
            version = displayVersion,
            url = build.assetUrl,
            // Release bodies are empty upstream; the dialog and notification
            // already carry the version string.
            notes = "",
        )
    }

    /**
     * The first of [newer] (highest first) whose release has a verifiable
     * asset for [revision], or null. A release matches only by its exact tag
     * `v<version>`. Outside [page] only the highest listed version's release
     * ([lookedUp]) is known; any other version missing from the page ends the
     * walk, so a lower version is not offered over one that was not examined.
     */
    private fun findListedBuild(
        newer: List<ReleaseTagVersion>,
        page: List<GithubReleaseDto>,
        highestTag: String,
        lookedUp: GithubReleaseDto?,
        revision: String,
    ): VerifiedBuild? {
        for (version in newer) {
            val tag = "v${version.raw}"
            val release = page.firstOrNull { it.tagName == tag } ?: if (tag == highestTag) lookedUp else {
                logger.w { "PebbleOS release $tag is not on the first page of releases" }
                return null
            }
            release?.verifiedBuildFor(revision)?.let { return it }
        }
        logger.w { "No listed PebbleOS release newer than the running one has a verifiable asset for '$revision'" }
        return null
    }

    private sealed class Fetched<out T> {
        data class Success<T>(val value: T) : Fetched<T>()
        data object NotFound : Fetched<Nothing>()
        data class Failure(val message: String) : Fetched<Nothing>()
    }

    private suspend inline fun <reified T> fetch(url: String, perPage: Int? = null): Fetched<T> {
        val response = try {
            httpClient.get(url) {
                perPage?.let { parameter("per_page", it) }
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            }
        } catch (e: IOException) {
            logger.w(e) { "Network error fetching $url" }
            return Fetched.Failure(GENERIC_FAILURE)
        }
        if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.TooManyRequests) {
            // The unauthenticated quota is per IP and per hour (GitHub docs,
            // "Rate limits for the REST API"), so this is transient.
            logger.w { "PebbleOS release fetch rate limited: ${response.status}" }
            return Fetched.Failure(RATE_LIMITED_FAILURE)
        }
        if (response.status == HttpStatusCode.NotFound) {
            return Fetched.NotFound
        }
        if (!response.status.isSuccess()) {
            logger.w { "PebbleOS release fetch failed: ${response.status}" }
            return Fetched.Failure(GENERIC_FAILURE)
        }
        return try {
            Fetched.Success(response.body<T>())
        } catch (e: NoTransformationFoundException) {
            logger.w(e) { "Unexpected content fetching $url" }
            Fetched.Failure(GENERIC_FAILURE)
        } catch (e: ContentConvertException) {
            logger.w(e) { "Malformed PebbleOS release data from $url" }
            Fetched.Failure(GENERIC_FAILURE)
        }
    }

    /** Null unless this release is published and has a verifiable asset for [revision]. */
    private fun GithubReleaseDto.verifiedBuildFor(revision: String): VerifiedBuild? {
        if (draft || prerelease) return null
        val published = publishedAt?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
            ?: return null
        val asset = assets.firstOrNull { it.name == "normal_${revision}_${tagName}.pbz" } ?: return null
        val digestHex = normalizeSha256Hex(asset.digest) ?: return null
        if (asset.size <= 0) return null
        return VerifiedBuild(tagName, published, asset.browserDownloadUrl, asset.size, digestHex)
    }

    private data class VerifiedBuild(
        val tag: String,
        val publishedAt: Instant,
        val assetUrl: String,
        val assetSize: Long,
        val sha256Hex: String,
    )

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/coredevices/PebbleOS/releases"

        // The first page of GitHub's release list, both tag lines mixed.
        private const val PAGE_SIZE = 20
        internal const val GITHUB_API_VERSION = "2022-11-28"
        private const val GENERIC_FAILURE = "Failed to check for PebbleOS update"
        private const val RATE_LIMITED_FAILURE = "PebbleOS update check is rate limited, try again later"
        private const val LIST_FAILURE = "Couldn't read the PebbleOS changelog list"
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
