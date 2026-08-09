package coredevices.pebble.firmware

import com.russhwolf.settings.MapSettings
import coredevices.pebble.Platform
import coredevices.pebble.services.EngDashOta
import coredevices.pebble.services.Memfault
import coredevices.pebble.services.PebbleAccountProvider
import coredevices.pebble.services.PebbleHttpClient
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import kotlinx.coroutines.flow.MutableStateFlow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * Pins the fork's doCheck routing: Core watches must use the GitHub checker
 * and never touch cohorts (which rejects Core hardware) or Memfault (no token
 * in fork builds; MemfaultTest pins that separately). Legacy watches must
 * keep using cohorts and never touch GitHub.
 */
class FirmwareUpdateCheckRoutingTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private fun failingClient(label: String) = HttpClient(MockEngine { request ->
        fail("$label must not be contacted for this watch, but got a request to ${request.url}")
    }) {
        install(ContentNegotiation) { json(testJson) }
    }

    private fun memfaultNeverContacted() =
        Memfault(failingClient("Memfault"), MapSettings(), Platform.Android, memfaultToken = null)

    // Eng-dash is doubly disabled in fork builds (BUG_URL is never set, and
    // useEngDashOta defaults to false), so the routing must never touch it;
    // every collaborator this instance holds fails the test on first use.
    private fun engDashNeverContacted() = EngDashOta(
        failingClient("EngDashOta"),
        PebbleHttpClient(
            pebbleAccount = object : PebbleAccountProvider {
                override fun get() = fail("PebbleAccount must not be touched: eng-dash is disabled in fork builds")
            },
            httpClient = failingClient("PebbleHttpClient"),
            libPebble = lazy { fail("LibPebble must not be touched by the eng-dash path") },
        ),
    )

    // Defaults only: useEngDashOta stays false, mirroring a fork install that
    // never opted in.
    private fun forkDefaultConfig() = CoreConfigFlow(MutableStateFlow(CoreConfig()))

    private val githubBody = releaseList(
        releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", "sha256:" + "a".repeat(64), 100))),
    )

    @Test
    fun coreWatchRoutesToGithubReleasesOnly() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val check = FirmwareUpdateCheck(
            memfault = memfaultNeverContacted(),
            engDashOta = engDashNeverContacted(),
            coreConfig = forkDefaultConfig(),
            cohorts = testCohorts(failingClient("Cohorts"), expectations),
            githubReleases = GithubReleases(jsonRespondingClient(githubBody), expectations, fixedTestClock),
            channel = { FirmwareUpdateChannel.Soaked },
            clock = fixedTestClock,
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
            engDashOta = engDashNeverContacted(),
            coreConfig = forkDefaultConfig(),
            cohorts = testCohorts(jsonRespondingClient(cohortsBody()), expectations),
            githubReleases = GithubReleases(failingClient("GitHub"), expectations, fixedTestClock),
            channel = { FirmwareUpdateChannel.Soaked },
            clock = fixedTestClock,
        )
        val result = check.checkForUpdates(
            testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0"),
            force = false,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertContains(update.url, "binaries.rebble.io")
    }

    /** Two main-line releases: v4.31.0 is soaked, v4.32.0 is 2 days old. */
    private val twoChannelGithubBody = releaseList(
        releaseJson("v4.32.0", 2, listOf(normalAsset("asterix", "v4.32.0", "sha256:" + "c".repeat(64), 100))),
        releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", "sha256:" + "a".repeat(64), 100))),
    )

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
            engDashOta = engDashNeverContacted(),
            coreConfig = forkDefaultConfig(),
            cohorts = testCohorts(failingClient("Cohorts"), expectations),
            githubReleases = GithubReleases(client, expectations, fixedTestClock),
            channel = { channel },
            clock = fixedTestClock,
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
            engDashOta = engDashNeverContacted(),
            coreConfig = forkDefaultConfig(),
            cohorts = testCohorts(failingClient("Cohorts"), expectations),
            githubReleases = GithubReleases(client, expectations, fixedTestClock),
            channel = { channel },
            clock = fixedTestClock,
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
            respond(cohortsBody(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val check = FirmwareUpdateCheck(
            memfault = memfaultNeverContacted(),
            engDashOta = engDashNeverContacted(),
            coreConfig = forkDefaultConfig(),
            cohorts = testCohorts(client, expectations),
            githubReleases = GithubReleases(failingClient("GitHub"), expectations, fixedTestClock),
            channel = { channel },
            clock = fixedTestClock,
        )
        val watch = testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0")

        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check.checkForUpdates(watch, force = false))
        channel = FirmwareUpdateChannel.Early
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check.checkForUpdates(watch, force = false))
        assertEquals(1, cohortsRequests)
    }
}
