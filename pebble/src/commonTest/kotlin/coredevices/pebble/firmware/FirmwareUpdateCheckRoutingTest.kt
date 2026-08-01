package coredevices.pebble.firmware

import CoreAppVersion
import com.russhwolf.settings.MapSettings
import coredevices.pebble.Platform
import coredevices.pebble.account.PebbleAccount
import coredevices.pebble.services.Memfault
import coredevices.pebble.services.PebbleAccountProvider
import coredevices.pebble.services.PebbleHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins the fork's doCheck routing: Core watches must use the GitHub checker
 * and never touch cohorts (which rejects Core hardware) or Memfault (no token
 * in fork builds; MemfaultTest pins that separately). Legacy watches must
 * keep using cohorts and never touch GitHub.
 */
class FirmwareUpdateCheckRoutingTest {

    private val now = Instant.parse("2026-07-31T00:00:00Z")
    private val fixedClock = object : Clock {
        override fun now(): Instant = this@FirmwareUpdateCheckRoutingTest.now
    }

    private val testJson = Json { ignoreUnknownKeys = true }

    private fun failingClient(label: String) = HttpClient(MockEngine { request ->
        fail("$label must not be contacted for this watch, but got a request to ${request.url}")
    }) {
        install(ContentNegotiation) { json(testJson) }
    }

    private fun respondingClient(body: String) = HttpClient(MockEngine { _ ->
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        install(ContentNegotiation) { json(testJson) }
    }

    private class SignedOutAccounts : PebbleAccountProvider {
        override fun get(): PebbleAccount = object : PebbleAccount {
            override val loggedIn = MutableStateFlow<String?>(null)
            override val devToken = MutableStateFlow<String?>(null)
            override suspend fun setToken(token: String?, bootUrl: String?) {}
            override suspend fun setDevPortalId() {}
        }
    }

    private fun cohorts(client: HttpClient, expectations: FirmwareArtifactExpectations) = Cohorts(
        httpClient = PebbleHttpClient(SignedOutAccounts(), client, lazy { FakeLibPebble() }),
        appVersion = CoreAppVersion("0.0.0"),
        expectations = expectations,
    )

    private fun memfaultNeverContacted() =
        Memfault(failingClient("Memfault"), MapSettings(), Platform.Android, memfaultToken = null)

    private val githubBody = """[{"tag_name":"v4.31.0","prerelease":false,"draft":false,
        "published_at":"2026-07-21T00:00:00Z","assets":[{"name":"normal_asterix_v4.31.0.pbz",
        "browser_download_url":"https://github.com/coredevices/PebbleOS/releases/download/v4.31.0/normal_asterix_v4.31.0.pbz",
        "digest":"sha256:${"a".repeat(64)}","size":100}]}]""".replace("\n", "")

    private val cohortsBody = """{"fw":{"normal":{"friendlyVersion":"v4.4.3-rbl","notes":"legacy notes",
        "sha-256":"${"b".repeat(64)}","timestamp":1762930476,
        "url":"https://binaries.rebble.io/fw/silk/Pebble-4.4.3-rbl-silk.pbz"}}}""".replace("\n", "")

    @Test
    fun coreWatchRoutesToGithubReleasesOnly() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val check = FirmwareUpdateCheck(
            memfault = memfaultNeverContacted(),
            cohorts = cohorts(failingClient("Cohorts"), expectations),
            githubReleases = GithubReleases(respondingClient(githubBody), expectations, fixedClock),
            channel = { FirmwareUpdateChannel.Soaked },
            clock = fixedClock,
        )
        val result = check.checkForUpdates(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"),
            force = false,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertContains(update.url, "normal_asterix_v4.31.0.pbz")
    }

    @Test
    fun legacyWatchRoutesToCohortsOnly() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val check = FirmwareUpdateCheck(
            memfault = memfaultNeverContacted(),
            cohorts = cohorts(respondingClient(cohortsBody), expectations),
            githubReleases = GithubReleases(failingClient("GitHub"), expectations, fixedClock),
            channel = { FirmwareUpdateChannel.Soaked },
            clock = fixedClock,
        )
        val result = check.checkForUpdates(
            testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0"),
            force = false,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertContains(update.url, "binaries.rebble.io")
    }

    /** Two main-line releases: v4.31.0 is soaked, v4.32.0 is 2 days old. */
    private val twoChannelGithubBody = """[{"tag_name":"v4.32.0","prerelease":false,"draft":false,
        "published_at":"2026-07-29T00:00:00Z","assets":[{"name":"normal_asterix_v4.32.0.pbz",
        "browser_download_url":"https://github.com/coredevices/PebbleOS/releases/download/v4.32.0/normal_asterix_v4.32.0.pbz",
        "digest":"sha256:${"c".repeat(64)}","size":100}]},
        {"tag_name":"v4.31.0","prerelease":false,"draft":false,
        "published_at":"2026-07-21T00:00:00Z","assets":[{"name":"normal_asterix_v4.31.0.pbz",
        "browser_download_url":"https://github.com/coredevices/PebbleOS/releases/download/v4.31.0/normal_asterix_v4.31.0.pbz",
        "digest":"sha256:${"a".repeat(64)}","size":100}]}]""".replace("\n", "")

    @Test
    fun channelFlipInvalidatesTheCacheWithoutForce() = runTest {
        // Regression: the cache key must include the channel, or flipping the
        // Early setting keeps serving the other channel's cached result until
        // the TTL expires (no UI path forces a check).
        var channel = FirmwareUpdateChannel.Soaked
        var githubRequests = 0
        val expectations = FirmwareArtifactExpectations()
        val client = HttpClient(MockEngine { _ ->
            githubRequests++
            respond(twoChannelGithubBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val check = FirmwareUpdateCheck(
            memfault = memfaultNeverContacted(),
            cohorts = cohorts(failingClient("Cohorts"), expectations),
            githubReleases = GithubReleases(client, expectations, fixedClock),
            channel = { channel },
            clock = fixedClock,
        )
        val watch = testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0")

        val soaked = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check.checkForUpdates(watch, force = false))
        assertContains(soaked.url, "v4.31.0")
        check.checkForUpdates(watch, force = false)
        assertEquals(1, githubRequests)

        channel = FirmwareUpdateChannel.Early
        val early = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check.checkForUpdates(watch, force = false))
        assertContains(early.url, "v4.32.0")
        assertEquals(2, githubRequests)

        // Flipping back reuses the still-valid Soaked entry.
        channel = FirmwareUpdateChannel.Soaked
        val soakedAgain = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check.checkForUpdates(watch, force = false))
        assertContains(soakedAgain.url, "v4.31.0")
        assertEquals(2, githubRequests)
    }

    @Test
    fun midFlightChannelFlipDoesNotPoisonTheOtherChannelsCache() = runTest {
        // Regression: the channel is read exactly once per check and serves
        // both the cache key and the release selection. A toggle flip while
        // the fetch is in flight must not cache the new channel's selection
        // under the old channel's key (background checks overlap freely with
        // settings visits).
        var channel = FirmwareUpdateChannel.Soaked
        var githubRequests = 0
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val expectations = FirmwareArtifactExpectations()
        val client = HttpClient(MockEngine { _ ->
            githubRequests++
            fetchStarted.complete(Unit)
            releaseFetch.await()
            respond(twoChannelGithubBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val check = FirmwareUpdateCheck(
            memfault = memfaultNeverContacted(),
            cohorts = cohorts(failingClient("Cohorts"), expectations),
            githubReleases = GithubReleases(client, expectations, fixedClock),
            channel = { channel },
            clock = fixedClock,
        )
        val watch = testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0")

        val inFlight = async { check.checkForUpdates(watch, force = false) }
        fetchStarted.await()
        channel = FirmwareUpdateChannel.Early // flips while the fetch is in flight
        releaseFetch.complete(Unit)
        val first = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(inFlight.await())
        // The selection must match the channel the check started with.
        assertContains(first.url, "v4.31.0")

        // And the entry cached for Soaked must be the Soaked result.
        channel = FirmwareUpdateChannel.Soaked
        val cached = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check.checkForUpdates(watch, force = false))
        assertContains(cached.url, "v4.31.0")
        assertEquals(1, githubRequests)
    }

    @Test
    fun channelFlipDoesNotEvictLegacyWatchCache() = runTest {
        var channel = FirmwareUpdateChannel.Soaked
        var cohortsRequests = 0
        val expectations = FirmwareArtifactExpectations()
        val client = HttpClient(MockEngine { _ ->
            cohortsRequests++
            respond(cohortsBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val check = FirmwareUpdateCheck(
            memfault = memfaultNeverContacted(),
            cohorts = cohorts(client, expectations),
            githubReleases = GithubReleases(failingClient("GitHub"), expectations, fixedClock),
            channel = { channel },
            clock = fixedClock,
        )
        val watch = testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0")

        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check.checkForUpdates(watch, force = false))
        channel = FirmwareUpdateChannel.Early
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check.checkForUpdates(watch, force = false))
        assertEquals(1, cohortsRequests)
    }
}
