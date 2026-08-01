package coredevices.pebble.firmware

import coredevices.util.CoreConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the GitHub-releases checker against the traps found during research:
 * GitHub's Latest badge and publish dates must never drive selection, factory
 * tags are never offered, an equal version is never re-offered (upstream's
 * timestamp tiebreak would re-offer forever), unverifiable assets are treated
 * as absent, and the request must carry zero device data.
 */
class GithubReleasesTest {

    private fun digest(char: Char) = "sha256:" + char.toString().repeat(64)
    private fun hex(char: Char) = char.toString().repeat(64)

    /** Two main-line minors, one soaked; a factory hotfix is the newest publish. */
    private fun standardBody() = "[" + listOf(
        releaseJson("v4.9.142.4", 0, listOf(normalAsset("asterix", "v4.9.142.4", digest('d'), 400))),
        releaseJson("v4.31.1", 1, listOf(normalAsset("asterix", "v4.31.1", digest('b'), 222))),
        releaseJson("v4.32.0", 2, listOf(normalAsset("asterix", "v4.32.0", digest('a'), 111))),
        releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", digest('c'), 333))),
        releaseJson("v4.30.0", 20, listOf(normalAsset("asterix", "v4.30.0", digest('e'), 555))),
    ).joinToString(",") + "]"

    private fun checker(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        expectations: FirmwareArtifactExpectations = FirmwareArtifactExpectations(),
        requests: MutableList<HttpRequestData> = mutableListOf(),
    ): GithubReleases {
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return GithubReleases(client, expectations, fixedTestClock)
    }

    @Test
    fun soakedOffersSoakedMinorHotfixAndRecordsExpectation() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val checker = checker(standardBody(), expectations = expectations)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        // Minor 4.31 soaked (first release 10 days old), hotfix taken despite
        // being 1 day old; 4.32 (2 days) not soaked; factory tag ignored.
        assertEquals("v4.31.1", update.version.stringVersion)
        assertContains(update.url, "normal_asterix_v4.31.1.pbz")
        assertEquals("", update.notes)
        assertEquals(
            ExpectedFirmwareArtifact(hex('b'), 222, "v4.31.1"),
            expectations.lookup(update.url),
        )
    }

    @Test
    fun earlyOffersNewestMainLineTagNeverTheFactoryTag() = runTest {
        val checker = checker(standardBody())
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Early)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        // The factory hotfix v4.9.142.4 is the most recent publish (it would
        // hold GitHub's Latest badge) and has the biggest patch number.
        assertEquals("v4.32.0", update.version.stringVersion)
    }

    @Test
    fun earlyChannelSettingSwitchesTheOfferBetweenChecks() = runTest {
        // The channel is read from CoreConfig per check (by
        // FirmwareUpdateCheck in production) and passed in; flipping the
        // setting between checks must change the offer without recreating
        // the checker.
        var config = CoreConfig()
        val checker = checker(standardBody())
        val watch = testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0")

        val soaked = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(
            checker.getLatestFirmware(watch, config.firmwareUpdateChannel()),
        )
        assertEquals("v4.31.1", soaked.version.stringVersion)

        config = config.copy(firmwareUpdatesEarlyChannel = true)
        val early = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(
            checker.getLatestFirmware(watch, config.firmwareUpdateChannel()),
        )
        assertEquals("v4.32.0", early.version.stringVersion)

        config = config.copy(firmwareUpdatesEarlyChannel = false)
        val backToSoaked = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(
            checker.getLatestFirmware(watch, config.firmwareUpdateChannel()),
        )
        assertEquals("v4.31.1", backToSoaked.version.stringVersion)
    }

    @Test
    fun equalVersionIsNeverReOffered() = runTest {
        // The release's published_at is later than any firmware build
        // timestamp, so upstream FirmwareVersion comparison would call the
        // same version "newer" on its timestamp tiebreak. The checker must
        // compare tags and say no.
        val checker = checker(standardBody())
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.31.1"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(result)
    }

    @Test
    fun downgradeIsNeverOffered() = runTest {
        // Running the Early tier, then switching back to Soaked: the soaked
        // candidate is older than the running firmware.
        val checker = checker(standardBody())
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.32.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(result)
    }

    @Test
    fun recoveryIsAlwaysOffered() = runTest {
        val checker = checker(standardBody())
        val equalVersion = checker.getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.31.1", isRecovery = true),
            FirmwareUpdateChannel.Soaked,
        )
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(equalVersion)
        val unparseable = checker.getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "unknown", isRecovery = true),
            FirmwareUpdateChannel.Soaked,
        )
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(unparseable)
    }

    @Test
    fun unparseableRunningVersionFailsClosed() = runTest {
        val checker = checker(standardBody())
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "unknown"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
    }

    @Test
    fun unverifiableAssetIsTreatedAsAbsentAndSelectionWalksForward() = runTest {
        val body = "[" + listOf(
            releaseJson("v4.32.0", 2, listOf(normalAsset("asterix", "v4.32.0", digest('a'), 111))),
            releaseJson("v4.31.1", 1, listOf(normalAsset("asterix", "v4.31.1", null, 222))),
            releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", "not-a-digest", 333))),
        ).joinToString(",") + "]"
        val expectations = FirmwareArtifactExpectations()
        val checker = checker(body, expectations = expectations)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        // Soaked target is 4.31.1 (digest null) and 4.31.0's digest is
        // malformed; the nearest newer verifiable release wins.
        assertEquals("v4.32.0", update.version.stringVersion)
        assertEquals(ExpectedFirmwareArtifact(hex('a'), 111, "v4.32.0"), expectations.lookup(update.url))
    }

    @Test
    fun perEntryMalformedReleasesAreSkippedNotFatal() = runTest {
        // The skip-vs-fail split is deliberate: one odd upstream entry (bad
        // tag, missing or garbage date) must cost only itself, or a single
        // unusual tag would break every Core watch's update check.
        val body = "[" + listOf(
            """{"tag_name":"totally-unparseable","prerelease":false,"draft":false,""" +
                """"published_at":"${TEST_NOW}","assets":[]}""",
            """{"tag_name":"v4.33.0","prerelease":false,"draft":false,""" +
                """"assets":[${normalAsset("asterix", "v4.33.0", digest('f'), 111)}]}""",
            """{"tag_name":"v4.34.0","prerelease":false,"draft":false,"published_at":"not-a-date",""" +
                """"assets":[${normalAsset("asterix", "v4.34.0", digest('f'), 111)}]}""",
            releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", digest('c'), 333))),
        ).joinToString(",") + "]"
        val result = checker(body).getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("v4.31.0", update.version.stringVersion)
    }

    @Test
    fun zeroSizeAssetIsTreatedAsUnverifiable() = runTest {
        // A zero-size asset cannot be size-verified, so it counts as absent
        // and selection moves on, like the null/garbage digest cases.
        val body = "[" + listOf(
            releaseJson("v4.32.0", 2, listOf(normalAsset("asterix", "v4.32.0", digest('a'), 0))),
            releaseJson("v4.31.1", 3, listOf(normalAsset("asterix", "v4.31.1", digest('b'), 222))),
        ).joinToString(",") + "]"
        val result = checker(body).getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Early,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("v4.31.1", update.version.stringVersion)
    }

    @Test
    fun noAssetForThisHardwareFailsClosed() = runTest {
        val body = "[" + releaseJson(
            "v4.31.0", 10,
            listOf(normalAsset("obelix_pvt", "v4.31.0", digest('a'), 111)),
        ) + "]"
        val checker = checker(body)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
    }

    @Test
    fun prereleaseAndDraftAreSkipped() = runTest {
        val body = "[" + listOf(
            releaseJson("v4.33.0", 10, listOf(normalAsset("asterix", "v4.33.0", digest('a'), 111)), prerelease = true),
            releaseJson("v4.34.0", 10, listOf(normalAsset("asterix", "v4.34.0", digest('b'), 222)), draft = true),
            releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", digest('c'), 333))),
        ).joinToString(",") + "]"
        val checker = checker(body)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("v4.31.0", update.version.stringVersion)
    }

    @Test
    fun rateLimitFailsWithARetryableMessage() = runTest {
        val checker = checker("""{"message":"API rate limit exceeded"}""", status = HttpStatusCode.Forbidden)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        val failure = assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
        assertContains(failure.error, "rate limited")
    }

    @Test
    fun serverErrorsAndMalformedBodiesFailClosed() = runTest {
        val serverError = checker("oops", status = HttpStatusCode.InternalServerError)
            .getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(serverError)

        val notJson = checker("this is not json")
            .getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(notJson)
    }

    @Test
    fun networkErrorFailsClosed() = runTest {
        val client = HttpClient(MockEngine { throw IOException("network down") }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val checker = GithubReleases(client, FirmwareArtifactExpectations(), fixedTestClock)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
    }

    @Test
    fun requestIsAnonymousAndTargetsTheReleaseList() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val checker = checker(standardBody(), requests = requests)
        checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("api.github.com", request.url.host)
        assertEquals("/repos/coredevices/PebbleOS/releases", request.url.encodedPath)
        // The only query parameter is the page size: no hardware, serial, or
        // version leaves the device.
        assertEquals(setOf("per_page"), request.url.parameters.names())
        val urlText = request.url.toString()
        assertFalse(urlText.contains(TEST_WATCH_SERIAL))
        assertFalse(urlText.contains("asterix"))
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertTrue(request.headers.names().none { name ->
            request.headers.getAll(name).orEmpty().any { it.contains(TEST_WATCH_SERIAL) }
        })
    }

    @Test
    fun nothingIsRecordedWhenNoUpdateIsOffered() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val checker = checker(standardBody(), expectations = expectations)
        checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.31.1"), FirmwareUpdateChannel.Soaked)
        assertNull(expectations.lookup("https://github.com/coredevices/PebbleOS/releases/download/x/normal_asterix_v4.31.1.pbz"))
    }
}
