package com.anopticlabs.gravel.firmware

import com.anopticlabs.gravel.firmware.ChangelogListing.Listed
import com.anopticlabs.gravel.firmware.ChangelogListing.NotListed
import com.anopticlabs.gravel.firmware.ChangelogListing.Unknown
import coredevices.pebble.firmware.CHANGELOG_LIST_PATH
import coredevices.pebble.firmware.ExpectedFirmwareArtifact
import coredevices.pebble.firmware.FakeGithub
import coredevices.pebble.firmware.FirmwareArtifactExpectations
import coredevices.pebble.firmware.RELEASES_PATH
import coredevices.pebble.firmware.changelogListJson
import coredevices.pebble.firmware.normalAsset
import coredevices.pebble.firmware.releaseJson
import coredevices.pebble.firmware.releaseList
import coredevices.pebble.firmware.testWatchInfo
import io.ktor.http.HttpStatusCode
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_ASTERIX
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_GETAFIX_DVT
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_GETAFIX_EVT
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_OBELIX_DVT
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_OBELIX_PVT
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The build picker's list, against the same September 2026 release shapes as GithubReleasesTest. */
class GithubReleasesBuildChoicesTest {

    private fun digest(char: Char) = "sha256:" + char.toString().repeat(64)
    private fun hex(char: Char) = char.toString().repeat(64)

    private val allBoards = listOf("asterix", "obelix_pvt", "getafix_evt")
    private val withoutEvt = listOf("asterix", "obelix_pvt")

    private fun release(
        tag: String,
        digestChar: Char,
        boards: List<String> = allBoards,
        prerelease: Boolean = false,
        draft: Boolean = false,
    ) = releaseJson(
        tag, 0, boards.map { normalAsset(it, tag, digest(digestChar), 100) },
        prerelease = prerelease, draft = draft,
    )

    private val septemberPage = releaseList(
        release("v4.38.2", 'a', withoutEvt),
        release("v4.38.1", 'b', withoutEvt),
        release("v4.38.0", 'c', withoutEvt),
        release("v4.30.3", 'd'),
        release("v4.27.3", 'e'),
        release("v4.9.142.4", 'f'),
        release("v4.37.0", '1', withoutEvt),
        release("v4.36.2", '2'),
        release("v4.36.0", '3'),
        release("v4.33.2", '4'),
    )

    private val septemberList = changelogListJson("4.38.1", "4.36.0", "4.33.2", "4.31.2", "4.30.0")

    private fun github(
        list: String = septemberList,
        page: String = septemberPage,
        tags: Map<String, String> = emptyMap(),
    ) = FakeGithub(listBody = list, pageBody = page, tagBodies = tags)

    private fun assetUrl(board: String, tag: String) =
        "https://github.com/coredevices/PebbleOS/releases/download/x/normal_${board}_$tag.pbz"

    private suspend fun choices(
        github: FakeGithub,
        running: String,
        platform: WatchHardwarePlatform = CORE_ASTERIX,
        isRecovery: Boolean = false,
        expectations: FirmwareArtifactExpectations = FirmwareArtifactExpectations(),
    ) = github.checker(expectations).getBuildChoices(testWatchInfo(platform, running, isRecovery))

    private fun FirmwareBuildChoicesResult.shown(): List<Triple<String, ChangelogListing, Boolean>> =
        assertIs<FirmwareBuildChoicesResult.Success>(this).builds.map {
            Triple(it.update.version.stringVersion, it.listing, it.isRecommended)
        }

    @Test
    fun listsNewerBuildsByVersionWithTheCheckedBuildRecommended() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val result = choices(github(), "v4.36.0", expectations = expectations)
        assertEquals(
            listOf(
                Triple("v4.38.2", NotListed, false),
                Triple("v4.38.1", Listed, true),
                Triple("v4.38.0", NotListed, false),
                Triple("v4.37.0", NotListed, false),
                Triple("v4.36.2", NotListed, false),
            ),
            result.shown(),
        )
        assertEquals(Instant.parse("2026-07-30T09:22:00Z"), assertIs<FirmwareBuildChoicesResult.Success>(result).listCheckedAt)
        for ((tag, char) in listOf("v4.38.2" to 'a', "v4.38.1" to 'b', "v4.38.0" to 'c', "v4.37.0" to '1', "v4.36.2" to '2')) {
            assertEquals(ExpectedFirmwareArtifact(hex(char), 100, tag), expectations.lookup(assetUrl("asterix", tag)))
        }
    }

    @Test
    fun theRecommendedBuildStaysWhenMoreThanFourUnlistedBuildsAreNewer() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val page = releaseList(
            release("v4.39.0", 'a'),
            release("v4.43.0", 'b'),
            release("v4.41.0", 'c'),
            release("v4.38.1", 'd'),
            release("v4.42.0", 'e'),
            release("v4.40.0", 'f'),
        )
        val result = choices(github(page = page), "v4.36.0", expectations = expectations)
        assertEquals(
            listOf("v4.43.0", "v4.42.0", "v4.41.0", "v4.40.0", "v4.38.1"),
            result.shown().map { it.first },
        )
        assertEquals("v4.38.1", result.shown().single { it.third }.first)
        assertNull(expectations.lookup(assetUrl("asterix", "v4.39.0")))
    }

    @Test
    fun onlyPublishedTagsInTheListsFormAreListed() = runTest {
        val page = releaseList(
            release("v4.38.4", 'a', draft = true),
            release("v4.38.3", 'b', prerelease = true),
            release("v4.38.1-core59", 'c'),
            release("v4.9.142.4", 'd'),
            release("v4.30.3", 'e'),
            release("v4.27.3", 'f'),
            release("v4.038.5", '1'),
        )
        val result = choices(github(page = page), "v4.9.0")
        assertEquals(
            listOf(Triple("v4.30.3", NotListed, false), Triple("v4.27.3", NotListed, false)),
            result.shown(),
        )
    }

    @Test
    fun buildsWithoutAVerifiableAssetForTheBoardAreLeftOut() = runTest {
        val page = releaseList(
            release("v4.38.1", 'b', withoutEvt),
            releaseJson("v4.37.0", 0, listOf(normalAsset("getafix_evt", "v4.37.0", null, 100))),
            release("v4.36.2", '2'),
        )
        assertEquals(
            listOf(Triple("v4.36.2", NotListed, false)),
            choices(github(page = page), "v4.36.0", CORE_GETAFIX_EVT).shown(),
        )
    }

    @Test
    fun theRecommendedBuildIsTheOneTheCheckOffers() = runTest {
        val watches = listOf(
            Triple("v4.36.0", CORE_ASTERIX, false),
            Triple("v4.30.0", CORE_GETAFIX_EVT, false),
            Triple("v4.38.1", CORE_ASTERIX, false),
            Triple("v4.38.1", CORE_ASTERIX, true),
            Triple("unknown", CORE_ASTERIX, true),
        )
        for ((running, platform, isRecovery) in watches) {
            val checked = github().checker().getLatestFirmware(testWatchInfo(platform, running, isRecovery))
            val recommended = assertIs<FirmwareBuildChoicesResult.Success>(choices(github(), running, platform, isRecovery))
                .builds.singleOrNull { it.isRecommended }
            assertEquals((checked as? FirmwareUpdateCheckResult.FoundUpdate)?.url, recommended?.update?.url, running)
        }
    }

    @Test
    fun recoveryListsBuildsWhateverTheRunningVersion() = runTest {
        val expected = listOf(
            Triple("v4.38.2", NotListed, false),
            Triple("v4.38.1", Listed, true),
            Triple("v4.38.0", NotListed, false),
            Triple("v4.37.0", NotListed, false),
            Triple("v4.36.2", NotListed, false),
        )
        assertEquals(expected, choices(github(), "v4.38.1", isRecovery = true).shown())
        assertEquals(expected, choices(github(), "unknown", isRecovery = true).shown())
    }

    @Test
    fun anUnreadableListLeavesTheBuildsUnlabeledAndNoneRecommended() = runTest {
        for (gh in listOf(github().apply { listStatus = HttpStatusCode.InternalServerError }, github(list = """{"schema":2}"""))) {
            val result = choices(gh, "v4.37.0")
            assertEquals(
                listOf(Triple("v4.38.2", Unknown, false), Triple("v4.38.1", Unknown, false), Triple("v4.38.0", Unknown, false)),
                result.shown(),
            )
            assertNull(assertIs<FirmwareBuildChoicesResult.Success>(result).listCheckedAt)
            assertEquals(listOf(CHANGELOG_LIST_PATH, RELEASES_PATH), gh.paths)
        }
    }

    @Test
    fun theHighestListedVersionOffThePageIsLookedUpAndListed() = runTest {
        val gh = github(
            list = changelogListJson("4.31.2", "4.30.0"),
            tags = mapOf("v4.31.2" to release("v4.31.2", '5')),
        )
        val shown = choices(gh, "v4.30.3").shown()
        assertTrue(Triple("v4.31.2", Listed, true) in shown, "$shown")
        assertEquals(listOf(CHANGELOG_LIST_PATH, RELEASES_PATH, "$RELEASES_PATH/tags/v4.31.2"), gh.paths)
    }

    @Test
    fun withNothingNewerListedNoBuildIsPreselected() = runTest {
        assertEquals(listOf(Triple("v4.38.2", NotListed, false)), choices(github(), "v4.38.1").shown())
    }

    @Test
    fun aListedBuildTheCheckCannotReachIsListedButNotRecommended() = runTest {
        // 4.31.2 is listed above 4.30.0 but neither on the page nor the highest
        // listed version, so the check ends its walk there and offers nothing.
        val page = releaseList(release("v4.30.0", 'b'), release("v4.30.3", 'd'))
        val gh = github(list = changelogListJson("4.33.2", "4.31.2", "4.30.0"), page = page)
        assertEquals(
            listOf(Triple("v4.30.3", NotListed, false), Triple("v4.30.0", Listed, false)),
            choices(gh, "v4.29.0").shown(),
        )
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(
            github(list = changelogListJson("4.33.2", "4.31.2", "4.30.0"), page = page)
                .checker().getLatestFirmware(testWatchInfo(CORE_ASTERIX, "v4.29.0")),
        )
    }

    @Test
    fun pickerLoadsForSeveralBoardsKeepTheChecksExpectation() = runTest {
        val boards = listOf("asterix", "obelix_pvt", "obelix_dvt", "getafix_evt", "getafix_dvt")
        val page = releaseList(
            *listOf("v4.38.2", "v4.38.1", "v4.38.0", "v4.37.0", "v4.36.2").mapIndexed { i, tag ->
                releaseJson(tag, 0, boards.map { normalAsset(it, tag, digest('a' + i), 100) })
            }.toTypedArray(),
        )
        val expectations = FirmwareArtifactExpectations()
        val offer = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(
            github(page = page).checker(expectations).getLatestFirmware(testWatchInfo(CORE_ASTERIX, "v4.36.0")),
        )
        for (platform in listOf(CORE_OBELIX_PVT, CORE_OBELIX_DVT, CORE_GETAFIX_EVT, CORE_GETAFIX_DVT)) {
            assertEquals(5, choices(github(page = page), "v4.36.0", platform, expectations = expectations).shown().size)
        }
        assertNotNull(expectations.lookup(offer.url))
    }

    @Test
    fun nothingNewerIsAnEmptyList() = runTest {
        val result = assertIs<FirmwareBuildChoicesResult.Success>(choices(github(), "v4.38.2"))
        assertEquals(emptyList(), result.builds)
    }

    @Test
    fun failuresOtherThanTheListFailTheWholeList() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val rateLimitedList = github().apply { listStatus = HttpStatusCode.Forbidden }
        assertContains(assertIs<FirmwareBuildChoicesResult.Failed>(choices(rateLimitedList, "v4.36.0")).message, "rate limited")
        assertEquals(listOf(CHANGELOG_LIST_PATH), rateLimitedList.paths)
        val rateLimitedPage = github().apply { pageStatus = HttpStatusCode.TooManyRequests }
        assertContains(assertIs<FirmwareBuildChoicesResult.Failed>(choices(rateLimitedPage, "v4.36.0")).message, "rate limited")
        assertIs<FirmwareBuildChoicesResult.Failed>(choices(github().apply { pageStatus = HttpStatusCode.InternalServerError }, "v4.36.0"))
        assertIs<FirmwareBuildChoicesResult.Failed>(choices(github().apply { pageFailure = IOException("reset") }, "v4.36.0"))
        assertTrue(assertIs<FirmwareBuildChoicesResult.Failed>(choices(rateLimitedPage, "v4.36.0")).retryable)
        assertFalse(assertIs<FirmwareBuildChoicesResult.Failed>(choices(github(), "unknown", expectations = expectations)).retryable)
        for (tag in listOf("v4.38.2", "v4.38.1", "v4.37.0")) assertNull(expectations.lookup(assetUrl("asterix", tag)))
    }

    @Test
    fun theRequestsMatchTheChecksAndDoNotVaryWithTheWatch() = runTest {
        val watches = listOf(
            Triple("v4.38.1", CORE_ASTERIX, false),
            Triple("v4.30.0", CORE_GETAFIX_EVT, false),
            Triple("v4.38.1", CORE_ASTERIX, true),
            Triple("unknown", CORE_ASTERIX, false),
        )
        val offPage = mapOf("v4.31.2" to release("v4.31.2", '5'))
        for (list in listOf(septemberList, changelogListJson("4.31.2", "4.30.0"))) {
            val checkRequests = github(list = list, tags = offPage)
                .also { it.checker().getLatestFirmware(testWatchInfo(CORE_ASTERIX, "v4.30.0")) }
                .requests.map { it.url.toString() }
            for ((running, platform, isRecovery) in watches) {
                val gh = github(list = list, tags = offPage)
                choices(gh, running, platform, isRecovery)
                assertEquals(checkRequests, gh.requests.map { it.url.toString() }, "$running $platform")
            }
        }
    }
}
