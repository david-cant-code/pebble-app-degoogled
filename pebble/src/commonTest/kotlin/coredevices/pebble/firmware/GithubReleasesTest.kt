package coredevices.pebble.firmware

import io.ktor.http.HttpStatusCode
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_ASTERIX
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_GETAFIX_EVT
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Core-watch checker against the release shapes seen in September
 * 2026: only versions on Core's changelog are offered, however new an
 * unlisted tag (4.38.2, the 4.30.x/4.27.x backports, factory tags) is by
 * version or date; an equal version is never re-offered; a board a newer
 * build dropped gets the highest listed build that still has its asset; and
 * the requests carry no device data and do not vary with the watch.
 */
class GithubReleasesTest {

    private fun digest(char: Char) = "sha256:" + char.toString().repeat(64)
    private fun hex(char: Char) = char.toString().repeat(64)

    private val allBoards = listOf("asterix", "obelix_pvt", "getafix_evt")
    private val withoutEvt = listOf("asterix", "obelix_pvt")

    private fun release(
        tag: String,
        ageDays: Int,
        digestChar: Char,
        boards: List<String> = allBoards,
        size: Long = 100,
        prerelease: Boolean = false,
        draft: Boolean = false,
    ) = releaseJson(
        tag, ageDays, boards.map { normalAsset(it, tag, digest(digestChar), size) },
        prerelease = prerelease, draft = draft,
    )

    /** The newest releases by date on 2026-09-26, reduced to three boards. */
    private val septemberPage = releaseList(
        release("v4.38.2", 0, 'a', withoutEvt),
        release("v4.38.1", 2, 'b', withoutEvt),
        release("v4.38.0", 2, 'c', withoutEvt),
        release("v4.30.3", 8, 'd'),
        release("v4.27.3", 8, 'e'),
        release("v4.9.142.4", 12, 'f'),
        release("v4.37.0", 16, '1', withoutEvt),
        release("v4.36.2", 28, '2'),
        release("v4.36.0", 30, '3'),
        release("v4.33.2", 40, '4'),
    )

    private val septemberList = changelogListJson("4.38.1", "4.36.0", "4.33.2", "4.31.2", "4.30.0")

    private fun github(
        list: String = septemberList,
        page: String = septemberPage,
        tags: Map<String, String> = emptyMap(),
    ) = FakeGithub(listBody = list, pageBody = page, tagBodies = tags)

    private fun assetUrl(board: String, tag: String) =
        "https://github.com/coredevices/PebbleOS/releases/download/x/normal_${board}_$tag.pbz"

    private suspend fun assertNothingRecorded(expectations: FirmwareArtifactExpectations, tags: List<String>) {
        for (tag in tags) for (board in allBoards) assertNull(expectations.lookup(assetUrl(board, tag)), "$board $tag")
    }

    private suspend fun check(
        github: FakeGithub,
        running: String,
        platform: WatchHardwarePlatform = CORE_ASTERIX,
        isRecovery: Boolean = false,
        expectations: FirmwareArtifactExpectations = FirmwareArtifactExpectations(),
    ) = github.checker(expectations).getLatestFirmware(testWatchInfo(platform, running, isRecovery))

    @Test
    fun offersTheHighestListedBuildAndRecordsItsExpectation() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(
            check(github(), "v4.36.0", expectations = expectations),
        )
        assertEquals("v4.38.1", update.version.stringVersion)
        assertContains(update.url, "normal_asterix_v4.38.1.pbz")
        assertEquals("", update.notes)
        assertEquals(ExpectedFirmwareArtifact(hex('b'), 100, "v4.38.1"), expectations.lookup(update.url))
    }

    @Test
    fun runningTheHighestListedVersionIsUpToDateDespiteNewerUnlistedTags() = runTest {
        // 4.38.2 is newer than 4.38.1 but not on the changelog. An equal
        // version is never re-offered, which a FirmwareVersion comparison
        // would do on its publish-time tiebreak.
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(check(github(), "v4.38.1"))
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check(github(), "v4.36.2")).also {
            assertEquals("v4.38.1", it.version.stringVersion)
        }
    }

    @Test
    fun runningNewerThanEveryListedVersionIsNeverOfferedADowngrade() = runTest {
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(check(github(), "v4.38.2"))
    }

    @Test
    fun backportsNewestByDateAndVersionAreNotOfferedOverTheListedBuild() = runTest {
        val page = releaseList(
            release("v4.30.3", 0, 'd'),
            release("v4.30.2", 0, 'e'),
            release("v4.9.142.4", 0, 'f'),
            release("v4.30.0", 20, 'b'),
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(
            check(github(list = changelogListJson("4.30.0", "4.29.0"), page = page), "v4.29.0"),
        )
        assertEquals("v4.30.0", update.version.stringVersion)
    }

    @Test
    fun onlyTheExactTagStandsInForAListedVersion() = runTest {
        // A suffixed tag compares equal to 4.38.1 numerically; it must not
        // be offered, so 4.38.1 is looked up by its own tag, which is absent.
        val expectations = FirmwareArtifactExpectations()
        val gh = github(
            list = changelogListJson("4.38.1"),
            page = releaseList(release("v4.38.1-core59", 0, 'a')),
        )
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(gh, "v4.36.0", expectations = expectations))
        assertEquals(listOf(CHANGELOG_LIST_PATH, RELEASES_PATH, "$RELEASES_PATH/tags/v4.38.1"), gh.paths)
        assertNull(expectations.lookup(
            "https://github.com/coredevices/PebbleOS/releases/download/x/normal_asterix_v4.38.1-core59.pbz",
        ))
    }

    @Test
    fun recoveryIsOfferedTheHighestListedBuild() = runTest {
        val equalVersion = check(github(), "v4.38.1", isRecovery = true)
        assertEquals("v4.38.1", assertIs<FirmwareUpdateCheckResult.FoundUpdate>(equalVersion).version.stringVersion)
        val unparseable = check(github(), "unknown", isRecovery = true)
        assertEquals("v4.38.1", assertIs<FirmwareUpdateCheckResult.FoundUpdate>(unparseable).version.stringVersion)
    }

    @Test
    fun unparseableRunningVersionFailsClosed() = runTest {
        val expectations = FirmwareArtifactExpectations()
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(github(), "unknown", expectations = expectations))
        assertNothingRecorded(expectations, listOf("v4.38.1", "v4.36.0"))
    }

    @Test
    fun theRequestsDoNotVaryWithTheWatch() = runTest {
        val watches = listOf(
            Triple("v4.38.1", CORE_ASTERIX, false),
            Triple("v4.30.0", CORE_ASTERIX, false),
            Triple("v4.30.0", CORE_GETAFIX_EVT, false),
            Triple("v4.38.1", CORE_ASTERIX, true),
            Triple("unknown", CORE_ASTERIX, false),
        )
        val offPage = mapOf("v4.31.2" to release("v4.31.2", 60, '5'))
        for ((list, expected) in listOf(
            septemberList to listOf(CHANGELOG_LIST_PATH, RELEASES_PATH),
            changelogListJson("4.31.2", "4.30.0") to listOf(CHANGELOG_LIST_PATH, RELEASES_PATH, "$RELEASES_PATH/tags/v4.31.2"),
        )) {
            val requests = watches.map { (running, platform, isRecovery) ->
                val gh = github(list = list, tags = offPage)
                check(gh, running, platform, isRecovery)
                gh.requests.map { it.url.toString() }
            }
            assertEquals(expected, github(list = list, tags = offPage).also { check(it, "v4.30.0") }.paths)
            assertTrue(requests.all { it == requests.first() }, "$requests")
        }
    }

    @Test
    fun theHighestListedVersionOffThePageIsLookedUpByTag() = runTest {
        val gh = github(
            list = changelogListJson("4.31.2", "4.30.0"),
            tags = mapOf("v4.31.2" to release("v4.31.2", 60, '5')),
        )
        val expectations = FirmwareArtifactExpectations()
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check(gh, "v4.30.0", expectations = expectations))
        assertEquals("v4.31.2", update.version.stringVersion)
        assertEquals(ExpectedFirmwareArtifact(hex('5'), 100, "v4.31.2"), expectations.lookup(update.url))
        assertEquals(listOf(CHANGELOG_LIST_PATH, RELEASES_PATH, "$RELEASES_PATH/tags/v4.31.2"), gh.paths)
    }

    @Test
    fun aLookupAnswerForAnotherTagIsNotOffered() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val gh = github(
            list = changelogListJson("4.39.0", "4.38.1"),
            tags = mapOf("v4.39.0" to release("v4.38.2", 0, 'c')),
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check(gh, "v4.36.0", expectations = expectations))
        assertEquals("v4.38.1", update.version.stringVersion)
        assertNull(expectations.lookup(assetUrl("asterix", "v4.38.2")))
    }

    @Test
    fun listedVersionWithoutAReleaseMovesToTheNextListedVersion() = runTest {
        val gh = github(list = changelogListJson("4.39.0", "4.38.1"))
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check(gh, "v4.36.0"))
        assertEquals("v4.38.1", update.version.stringVersion)
        assertEquals(listOf(CHANGELOG_LIST_PATH, RELEASES_PATH, "$RELEASES_PATH/tags/v4.39.0"), gh.paths)
    }

    @Test
    fun aListedVersionBelowTheHighestThatIsOffThePageEndsTheCheck() = runTest {
        // 4.31.2 would have the asset, but only the highest listed version is looked up.
        val tags = mapOf("v4.31.2" to release("v4.31.2", 60, '5'))
        val expectations = FirmwareArtifactExpectations()
        val gh = github(list = changelogListJson("4.39.0", "4.31.2", "4.30.0"), tags = tags)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(gh, "v4.30.0", expectations = expectations))
        assertEquals(listOf(CHANGELOG_LIST_PATH, RELEASES_PATH, "$RELEASES_PATH/tags/v4.39.0"), gh.paths)
        // The highest listed version is on the page but has no asset for this board.
        val evt = github(list = changelogListJson("4.38.1", "4.31.2"), tags = tags)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(evt, "v4.30.0", CORE_GETAFIX_EVT, expectations = expectations))
        assertEquals(listOf(CHANGELOG_LIST_PATH, RELEASES_PATH), evt.paths)
        // 4.36.0 is on the page, but 4.37.5 above it is neither there nor looked up.
        val gap = github(list = changelogListJson("4.39.0", "4.37.5", "4.36.0"))
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(gap, "v4.33.2", expectations = expectations))
        assertNothingRecorded(expectations, listOf("v4.31.2", "v4.38.1", "v4.36.0"))
    }

    @Test
    fun boardDroppedFromNewerBuildsGetsTheHighestListedBuildThatHasIt() = runTest {
        // getafix_evt has no asset from 4.37.0 onward.
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check(github(), "v4.33.2", CORE_GETAFIX_EVT))
        assertEquals("v4.36.0", update.version.stringVersion)
        assertContains(update.url, "normal_getafix_evt_v4.36.0.pbz")
    }

    @Test
    fun noVerifiableAssetInAnyNewerListedBuildFailsVisibly() = runTest {
        val expectations = FirmwareArtifactExpectations()
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(
            check(github(), "v4.36.2", CORE_GETAFIX_EVT, expectations = expectations),
        )
        assertNothingRecorded(expectations, listOf("v4.38.2", "v4.38.1", "v4.36.2", "v4.36.0"))
    }

    @Test
    fun unverifiableAssetsCountAsAbsent() = runTest {
        val page = releaseList(
            releaseJson("v4.38.1", 2, listOf(normalAsset("asterix", "v4.38.1", null, 100))),
            releaseJson("v4.36.0", 30, listOf(normalAsset("asterix", "v4.36.0", "not-a-digest", 100))),
            releaseJson("v4.33.2", 40, listOf(normalAsset("asterix", "v4.33.2", digest('4'), 0))),
            release("v4.31.2", 50, '5'),
        )
        val expectations = FirmwareArtifactExpectations()
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(
            check(github(page = page), "v4.30.0", expectations = expectations),
        )
        assertEquals("v4.31.2", update.version.stringVersion)
        assertEquals(ExpectedFirmwareArtifact(hex('5'), 100, "v4.31.2"), expectations.lookup(update.url))
    }

    @Test
    fun prereleaseDraftUndatedAndMisdatedListedReleasesAreSkipped() = runTest {
        val page = releaseList(
            release("v4.40.0", 0, 'a', prerelease = true),
            release("v4.39.0", 0, 'b', draft = true),
            """{"tag_name":"v4.38.3","prerelease":false,"draft":false,"published_at":"not-a-date","assets":[${normalAsset("asterix", "v4.38.3", digest('e'), 100)}]}""",
            """{"tag_name":"v4.38.2","prerelease":false,"draft":false,"assets":[${normalAsset("asterix", "v4.38.2", digest('c'), 100)}]}""",
            """{"tag_name":"totally-unparseable","prerelease":false,"draft":false,"published_at":"$TEST_NOW","assets":[]}""",
            release("v4.38.1", 2, 'd'),
        )
        val gh = github(list = changelogListJson("4.40.0", "4.39.0", "4.38.3", "4.38.2", "4.38.1"), page = page)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(check(gh, "v4.36.0"))
        assertEquals("v4.38.1", update.version.stringVersion)
        assertEquals(listOf(CHANGELOG_LIST_PATH, RELEASES_PATH), gh.paths)
    }

    @Test
    fun anUnreadableListFailsWithoutARequestForReleases() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)) {
            val gh = github().apply { listStatus = status }
            val failure = assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(gh, "v4.36.0"))
            assertEquals("Couldn't read the PebbleOS changelog list", failure.error)
            assertEquals(listOf(CHANGELOG_LIST_PATH), gh.paths)
        }
        val malformed = github(list = """{"schema":2}""")
        val failure = assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(malformed, "v4.36.0"))
        assertEquals("Couldn't read the PebbleOS changelog list", failure.error)
    }

    @Test
    fun rateLimitsFailWithARetryableMessage() = runTest {
        val onList = github().apply { listStatus = HttpStatusCode.Forbidden }
        assertContains(assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(onList, "v4.36.0")).error, "rate limited")
        val onPage = github().apply { pageStatus = HttpStatusCode.TooManyRequests }
        assertContains(assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(onPage, "v4.36.0")).error, "rate limited")
        val onTag = github(list = changelogListJson("4.31.2"), tags = mapOf("v4.31.2" to release("v4.31.2", 60, '5')))
        onTag.tagStatus = HttpStatusCode.Forbidden
        assertContains(assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(onTag, "v4.30.0")).error, "rate limited")
    }

    @Test
    fun releaseServerErrorsAndMalformedBodiesFailClosed() = runTest {
        val serverError = github().apply { pageStatus = HttpStatusCode.InternalServerError }
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(serverError, "v4.36.0"))
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(github(page = "this is not json"), "v4.36.0"))
        val badTag = github(list = changelogListJson("4.31.2"), tags = mapOf("v4.31.2" to "this is not json"))
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(badTag, "v4.30.0"))
    }

    @Test
    fun releasePageNetworkErrorFailsClosed() = runTest {
        val gh = github().apply { pageFailure = IOException("network down") }
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(check(gh, "v4.36.0"))
    }

    @Test
    fun requestsCarryNoDeviceData() = runTest {
        val gh = github(
            list = changelogListJson("4.31.2", "4.30.0"),
            tags = mapOf("v4.31.2" to release("v4.31.2", 60, '5')),
        )
        check(gh, "v4.30.0")
        assertEquals(3, gh.requests.size)
        for (request in gh.requests) {
            assertEquals("api.github.com", request.url.host)
            val text = request.url.toString() + request.headers.entries().joinToString { "${it.key}=${it.value}" }
            for (deviceValue in listOf(TEST_WATCH_SERIAL, "asterix", "v4.30.0", "00:11:22:33:44:55")) {
                assertFalse(text.contains(deviceValue), "request carries '$deviceValue': $text")
            }
        }
        val (list, page, tag) = gh.requests
        assertEquals(setOf("ref"), list.url.parameters.names())
        assertEquals(setOf("per_page"), page.url.parameters.names())
        assertTrue(tag.url.parameters.isEmpty())
        assertEquals("application/vnd.github+json", page.headers["Accept"])
        assertEquals("application/vnd.github+json", tag.headers["Accept"])
    }

    @Test
    fun nothingIsRecordedWhenNoUpdateIsOffered() = runTest {
        val expectations = FirmwareArtifactExpectations()
        check(github(), "v4.38.1", expectations = expectations)
        assertNull(expectations.lookup("https://github.com/coredevices/PebbleOS/releases/download/x/normal_asterix_v4.38.1.pbz"))
    }
}
