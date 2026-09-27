package coredevices.pebble.firmware

import com.anopticlabs.gravel.firmware.PebbleOsChangelogListSource
import com.russhwolf.settings.MapSettings
import coredevices.analytics.CoreAnalytics
import coredevices.pebble.Platform
import coredevices.pebble.services.EngDashOta
import coredevices.pebble.services.Memfault
import coredevices.pebble.services.PebbleAccountProvider
import coredevices.pebble.services.PebbleHttpClient
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
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

    // Fork builds never enable eng-dash OTA, and the only analytics event the
    // checker emits is on the eng-dash fallback, so no check here may touch
    // analytics at all: a call means the eng-dash gate opened.
    private fun analyticsNeverCalled(): CoreAnalytics = object : CoreAnalytics {
        override fun logEvent(name: String, parameters: Map<String, Any>?) =
            fail("analytics event '$name' logged; the eng-dash OTA gate must stay closed in fork builds")
        override suspend fun logHeartbeatState(name: String, value: Boolean, timestamp: Instant) =
            fail("analytics touched: logHeartbeatState")
        override suspend fun processHeartbeat() = fail("analytics touched: processHeartbeat")
        override fun updateLastConnectedSerial(serial: String?) = fail("analytics touched: updateLastConnectedSerial")
        override fun updateRingTransferDurationMetric(duration: Duration) = fail("analytics touched: updateRingTransferDurationMetric")
        override fun updateRingLifetimeCollectionCount(serial: String, count: Int) = fail("analytics touched: updateRingLifetimeCollectionCount")
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

    private fun coreWatchGithub() = FakeGithub(
        listBody = changelogListJson("4.31.0"),
        pageBody = releaseList(
            releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", "sha256:" + "a".repeat(64), 100))),
        ),
    )

    private fun githubNeverContacted(expectations: FirmwareArtifactExpectations) = GithubReleases(
        failingClient("GitHub"), expectations, PebbleOsChangelogListSource(failingClient("GitHub")),
    )

    @Test
    fun coreWatchRoutesToGithubReleasesOnly() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val check = FirmwareUpdateCheck(
            memfault = memfaultNeverContacted(),
            engDashOta = engDashNeverContacted(),
            coreConfig = forkDefaultConfig(),
            cohorts = testCohorts(failingClient("Cohorts"), expectations),
            githubReleases = coreWatchGithub().checker(expectations),
            coreAnalytics = analyticsNeverCalled(),
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
            githubReleases = githubNeverContacted(expectations),
            coreAnalytics = analyticsNeverCalled(),
            clock = fixedTestClock,
        )
        val result = check.checkForUpdates(
            testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0"),
            force = false,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertContains(update.url, "binaries.rebble.io")
    }

    @Test
    fun versionChangeReplacesTheCachedEntryInsteadOfShadowingIt() = runTest {
        // The running version and recovery flag are inputs to the check, so
        // they live in the cache entry: a check for a different version must
        // refetch, and its entry must replace the old one, so that going back
        // to the earlier version (recovery -> main -> recovery) refetches too
        // rather than finding the earlier answer still cached beside it.
        val github = coreWatchGithub()
        val githubChecks = { github.paths.count { it == CHANGELOG_LIST_PATH } }
        val expectations = FirmwareArtifactExpectations()
        val check = FirmwareUpdateCheck(
            memfault = memfaultNeverContacted(),
            engDashOta = engDashNeverContacted(),
            coreConfig = forkDefaultConfig(),
            cohorts = testCohorts(failingClient("Cohorts"), expectations),
            githubReleases = github.checker(expectations),
            coreAnalytics = analyticsNeverCalled(),
            clock = fixedTestClock,
        )
        val onRecovery = testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0", isRecovery = true)
        val onMain = testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0")

        check.checkForUpdates(onRecovery, force = false)
        assertEquals(1, githubChecks())

        // A different running version is a different check, and its entry
        // replaces the recovery one.
        check.checkForUpdates(onMain, force = false)
        assertEquals(2, githubChecks())
        check.checkForUpdates(onMain, force = false)
        assertEquals(2, githubChecks())

        // Back on recovery: the earlier entry is gone, so this refetches; a
        // stale entry surviving under its own version would answer here.
        check.checkForUpdates(onRecovery, force = false)
        assertEquals(3, githubChecks())
        check.checkForUpdates(onRecovery, force = false)
        assertEquals(3, githubChecks())
    }
}
