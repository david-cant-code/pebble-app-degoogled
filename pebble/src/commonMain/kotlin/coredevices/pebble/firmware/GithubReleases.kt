package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.firmware.ChangelogListResult
import com.anopticlabs.gravel.firmware.ChangelogListing
import com.anopticlabs.gravel.firmware.FirmwareBuildChoice
import com.anopticlabs.gravel.firmware.FirmwareBuildChoicesResult
import com.anopticlabs.gravel.firmware.PebbleOsChangelogListSource
import com.anopticlabs.gravel.firmware.listedFormVersion
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
        val releases = when (val fetched = fetchReleases(listed)) {
            is Fetched.Success -> fetched.value
            Fetched.NotFound -> return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
            is Fetched.Failure -> return FirmwareUpdateCheckResult.UpdateCheckFailed(fetched.message)
        }
        val isNewer = newerThanRunning(watch) ?: return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
        val newer = listed.filter(isNewer).sortedDescending()
        if (newer.isEmpty()) {
            return FirmwareUpdateCheckResult.FoundNoUpdate
        }
        val build = findListedBuild(newer, releases, watch.platform.revision)
            ?: return FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
        return foundUpdateFor(build) ?: FirmwareUpdateCheckResult.UpdateCheckFailed(GENERIC_FAILURE)
    }

    /**
     * The builds the picker lists: releases tagged in the list's form with a
     * verifiable asset for the watch's board and newer than the running
     * version (any version in recovery), highest first. The build
     * [getLatestFirmware] offers is always among them; the newest others fill
     * up to [MAX_CHOICES]. With a readable list it makes the same requests as
     * [getLatestFirmware]; with an unreadable one it still reads the release
     * page and leaves the builds unlabeled.
     */
    suspend fun getBuildChoices(watch: WatchInfo): FirmwareBuildChoicesResult {
        val list = when (val result = changelogList.fetch()) {
            is ChangelogListResult.Success -> result.list
            ChangelogListResult.RateLimited -> return FirmwareBuildChoicesResult.Failed(RATE_LIMITED_FAILURE)
            ChangelogListResult.Unreadable -> null
        }
        val releases = when (val fetched = fetchReleases(list?.versions)) {
            is Fetched.Success -> fetched.value
            Fetched.NotFound -> return FirmwareBuildChoicesResult.Failed(GENERIC_FAILURE)
            is Fetched.Failure -> return FirmwareBuildChoicesResult.Failed(fetched.message)
        }
        val isNewer = newerThanRunning(watch)
            ?: return FirmwareBuildChoicesResult.Failed(GENERIC_FAILURE, retryable = false)
        val revision = watch.platform.revision
        val recommendedTag = list?.versions?.filter(isNewer)?.sortedDescending()
            ?.takeIf { it.isNotEmpty() }
            ?.let { findListedBuild(it, releases, revision)?.tag }
        val candidates = (releases.page + listOfNotNull(releases.lookedUp)).mapNotNull { release ->
            val version = listedFormVersion(release.tagName)?.takeIf(isNewer) ?: return@mapNotNull null
            release.verifiedBuildFor(revision)?.let { version to it }
        }
        val (recommended, others) = candidates.partition { (_, build) -> build.tag == recommendedTag }
        val builds = (recommended + others.sortedByDescending { it.first })
            .take(MAX_CHOICES)
            .sortedByDescending { it.first }
            .mapNotNull { (version, build) ->
                val update = foundUpdateFor(build) ?: return@mapNotNull null
                val listing = when {
                    list == null -> ChangelogListing.Unknown
                    list.versions.any { it.compareTo(version) == 0 } -> ChangelogListing.Listed
                    else -> ChangelogListing.NotListed
                }
                FirmwareBuildChoice(update, listing, isRecommended = build.tag == recommendedTag)
            }
        return FirmwareBuildChoicesResult.Success(builds, list?.checkedAt)
    }

    /**
     * The release page, and the highest listed version's release when it is
     * off the page. Requested whatever the watch runs, before anything about
     * the watch is read.
     */
    private suspend fun fetchReleases(listed: List<ReleaseTagVersion>?): Fetched<Releases> {
        val page = when (val fetched = fetch<List<GithubReleaseDto>>(RELEASES_URL, perPage = PAGE_SIZE)) {
            is Fetched.Success -> fetched.value
            Fetched.NotFound -> return Fetched.Failure(GENERIC_FAILURE)
            is Fetched.Failure -> return fetched
        }
        val highestTag = listed?.let { "v${it.max().raw}" }
        val lookedUp = if (highestTag == null || page.any { it.tagName == highestTag }) null else {
            when (val fetched = fetch<GithubReleaseDto>("$RELEASES_URL/tags/$highestTag")) {
                is Fetched.Success -> fetched.value.takeIf { it.tagName == highestTag }
                Fetched.NotFound -> null
                is Fetched.Failure -> return fetched
            }
        }
        return Fetched.Success(Releases(page, highestTag, lookedUp))
    }

    private class Releases(
        val page: List<GithubReleaseDto>,
        val highestListedTag: String?,
        val lookedUp: GithubReleaseDto?,
    )

    /**
     * Which versions are newer than the watch's firmware, or null when its
     * version cannot be compared. Fail closed: with an incomparable running
     * version any offer could be a downgrade or a same-version loop.
     * Recovery is exempt because it always gets an offer regardless of
     * comparison.
     */
    private fun newerThanRunning(watch: WatchInfo): ((ReleaseTagVersion) -> Boolean)? {
        if (watch.runningFwVersion.isRecovery) return { true }
        val runningRaw = watch.runningFwVersion.stringVersion
        val running = ReleaseTagVersion.from(runningRaw) ?: run {
            logger.e { "Cannot parse running firmware version '$runningRaw'" }
            return null
        }
        // Strictly newer only: equality must never re-offer (see ReleaseTagVersion).
        return { it > running }
    }

    /** Records the build's expectation for the installer; null when its tag yields no display version. */
    private suspend fun foundUpdateFor(build: VerifiedBuild): FirmwareUpdateCheckResult.FoundUpdate? {
        val displayVersion = FirmwareVersion.from(
            tag = build.tag,
            isRecovery = false,
            gitHash = "",
            // Display payload only. The offer decision compares tags;
            // feeding publishedAt into a FirmwareVersion comparison would
            // re-offer equal versions on its timestamp tiebreak.
            timestamp = build.publishedAt,
            isDualSlot = false, // not used from here
            isSlot0 = false, // not used from here
        )
        if (displayVersion == null) {
            logger.e { "Couldn't build display version from '${build.tag}'" }
            return null
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
            // Release bodies are empty upstream; the picker and notification
            // already carry the version string.
            notes = "",
        )
    }

    /**
     * The first of [newer] (highest first) whose release has a verifiable
     * asset for [revision], or null. A release matches only by its exact tag
     * `v<version>`. Outside the page only the highest listed version's
     * release is known; any other version missing from the page ends the
     * walk, so a lower version is not offered over one that was not examined.
     */
    private fun findListedBuild(
        newer: List<ReleaseTagVersion>,
        releases: Releases,
        revision: String,
    ): VerifiedBuild? {
        for (version in newer) {
            val tag = "v${version.raw}"
            val release = releases.page.firstOrNull { it.tagName == tag }
                ?: if (tag == releases.highestListedTag) releases.lookedUp else {
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
        private const val MAX_CHOICES = 5
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
