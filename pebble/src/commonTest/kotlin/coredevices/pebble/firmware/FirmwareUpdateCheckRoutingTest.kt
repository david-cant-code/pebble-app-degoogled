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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
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
            githubReleases = GithubReleases(
                respondingClient(githubBody), expectations, { FirmwareUpdateChannel.Soaked }, fixedClock,
            ),
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
            githubReleases = GithubReleases(
                failingClient("GitHub"), expectations, { FirmwareUpdateChannel.Soaked }, fixedClock,
            ),
            clock = fixedClock,
        )
        val result = check.checkForUpdates(
            testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0"),
            force = false,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertContains(update.url, "binaries.rebble.io")
    }
}
