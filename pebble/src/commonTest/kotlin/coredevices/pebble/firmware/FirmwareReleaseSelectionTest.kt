package coredevices.pebble.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Locks the two behaviors the GitHub update path depends on and which
 * upstream's FirmwareVersion cannot provide:
 *
 * 1. Anchored four-component tag parsing. Upstream's regex find() would
 *    truncate "v4.9.142.3" to 4.9.142, making factory tags structurally
 *    invisible and factory hotfixes mutually equal.
 * 2. Structural release selection. GitHub's "latest" badge and publish dates
 *    must never influence which release is offered; a factory tag published
 *    five minutes ago must lose to an older main-line tag.
 */
class FirmwareReleaseSelectionTest {

    // --- ReleaseTagVersion parsing ---

    @Test
    fun parsesThreeComponentMainLineTag() {
        val v = ReleaseTagVersion.from("v4.32.0")!!
        assertEquals(4, v.major)
        assertEquals(32, v.minor)
        assertEquals(0, v.patch)
        assertEquals(0, v.fourth)
        assertEquals(3, v.componentCount)
        assertEquals(false, v.isFactoryLine)
    }

    @Test
    fun parsesFourComponentFactoryTagWithoutTruncation() {
        val v = ReleaseTagVersion.from("v4.9.142.3")!!
        assertEquals(listOf(4, 9, 142, 3), listOf(v.major, v.minor, v.patch, v.fourth))
        assertEquals(4, v.componentCount)
        assertTrue(v.isFactoryLine)
    }

    @Test
    fun parsesTwoComponentAndSuffixForms() {
        val prf = ReleaseTagVersion.from("v4.0-prf4")!!
        assertEquals(2, prf.componentCount)
        assertEquals(0, prf.patch)
        val suffixed = ReleaseTagVersion.from("4.0.1-beta2")!!
        assertEquals(1, suffixed.patch)
        assertEquals(3, suffixed.componentCount)
    }

    @Test
    fun rejectsUnparseableTags() {
        assertNull(ReleaseTagVersion.from("unknown"))
        assertNull(ReleaseTagVersion.from(""))
        assertNull(ReleaseTagVersion.from("v4"))
        assertNull(ReleaseTagVersion.from("v4."))
        // Five components must fail whole-string matching, not silently drop one.
        assertNull(ReleaseTagVersion.from("v4.9.142.3.1"))
    }

    // --- Comparison ---

    @Test
    fun mainLineOutranksFactoryLineNumerically() {
        // 32 > 9 on the minor component; the factory line is older content
        // despite its larger patch/fourth components.
        assertTrue(ReleaseTagVersion.from("v4.32.0")!! > ReleaseTagVersion.from("v4.9.142.3")!!)
    }

    @Test
    fun fourthComponentOrdersFactoryHotfixes() {
        // Upstream's truncating parser would compare these equal.
        assertTrue(ReleaseTagVersion.from("v4.9.142.3")!! > ReleaseTagVersion.from("v4.9.142.2")!!)
    }

    @Test
    fun equalVersionsCompareEqualAcrossFormsAndSuffixes() {
        val plain = ReleaseTagVersion.from("v4.31.1")!!
        assertEquals(0, plain.compareTo(ReleaseTagVersion.from("4.31.1")!!))
        assertEquals(0, plain.compareTo(ReleaseTagVersion.from("v4.31.1-anything")!!))
        assertEquals(0, ReleaseTagVersion.from("v4.31")!!.compareTo(ReleaseTagVersion.from("v4.31.0")!!))
    }

    @Test
    fun patchOrdersWithinMinor() {
        assertTrue(ReleaseTagVersion.from("v4.31.1")!! > ReleaseTagVersion.from("v4.31.0")!!)
        assertTrue(ReleaseTagVersion.from("v4.32.0")!! > ReleaseTagVersion.from("v4.31.1")!!)
    }

    // --- Release selection ---

    private val now = Instant.parse("2026-07-31T00:00:00Z")

    private fun release(tag: String, ageDays: Int, hasAsset: Boolean = true) = SelectableRelease(
        version = ReleaseTagVersion.from(tag)!!,
        publishedAt = now - ageDays.days,
        hasAsset = hasAsset,
    )

    @Test
    fun factoryTagNewestByDateIsNeverPicked() {
        // The "latest badge trap": a factory hotfix published most recently
        // would hold GitHub's Latest badge, and it also has the numerically
        // largest patch component. Both channels must ignore it.
        val releases = listOf(
            release("v4.9.142.4", ageDays = 0),
            release("v4.32.0", ageDays = 2),
            release("v4.31.1", ageDays = 8),
            release("v4.31.0", ageDays = 10),
        )
        assertEquals("v4.32.0", selectRelease(releases, FirmwareUpdateChannel.Early, now)!!.version.raw)
        assertEquals("v4.31.1", selectRelease(releases, FirmwareUpdateChannel.Soaked, now)!!.version.raw)
    }

    @Test
    fun soakedSkipsFreshMinorButTakesFreshHotfixWithinSoakedMinor() {
        val releases = listOf(
            release("v4.32.0", ageDays = 2),
            // Minor 4.31 first published 10 days ago; its hotfix is 1 day old
            // and must be taken immediately: it stabilizes the promoted build.
            release("v4.31.1", ageDays = 1),
            release("v4.31.0", ageDays = 10),
            release("v4.30.0", ageDays = 20),
        )
        assertEquals("v4.31.1", selectRelease(releases, FirmwareUpdateChannel.Soaked, now)!!.version.raw)
    }

    @Test
    fun soakedReturnsNullWhenNoMinorHasSoaked() {
        val releases = listOf(
            release("v4.32.0", ageDays = 2),
            release("v4.31.0", ageDays = 5),
        )
        assertNull(selectRelease(releases, FirmwareUpdateChannel.Soaked, now))
    }

    @Test
    fun soakedWalksForwardWhenTargetLacksAssetForNewHardware() {
        // Hardware ramp: the soaked pick predates the board revision, so the
        // nearest newer release that has the asset is offered instead.
        val releases = listOf(
            release("v4.32.0", ageDays = 1, hasAsset = true),
            release("v4.31.1", ageDays = 3, hasAsset = true),
            release("v4.31.0", ageDays = 10, hasAsset = false),
            release("v4.30.0", ageDays = 20, hasAsset = false),
        )
        // Soaked target is 4.31.1 (minor 4.31 soaked, highest patch) but the
        // walk starts from the target: 4.31.1 has the asset, so it wins.
        assertEquals("v4.31.1", selectRelease(releases, FirmwareUpdateChannel.Soaked, now)!!.version.raw)

        val noAssetUntil432 = listOf(
            release("v4.32.0", ageDays = 1, hasAsset = true),
            release("v4.31.1", ageDays = 3, hasAsset = false),
            release("v4.31.0", ageDays = 10, hasAsset = false),
        )
        assertEquals("v4.32.0", selectRelease(noAssetUntil432, FirmwareUpdateChannel.Soaked, now)!!.version.raw)
    }

    @Test
    fun selectionReturnsNullWhenNoReleaseHasAsset() {
        val releases = listOf(
            release("v4.31.0", ageDays = 10, hasAsset = false),
            release("v4.30.0", ageDays = 20, hasAsset = false),
        )
        assertNull(selectRelease(releases, FirmwareUpdateChannel.Soaked, now))
        assertNull(selectRelease(releases, FirmwareUpdateChannel.Early, now))
    }

    @Test
    fun earlyPicksNewestMainLineWithAsset() {
        val releases = listOf(
            release("v4.32.0", ageDays = 0, hasAsset = false),
            release("v4.31.1", ageDays = 2, hasAsset = true),
        )
        assertEquals("v4.31.1", selectRelease(releases, FirmwareUpdateChannel.Early, now)!!.version.raw)
    }

    @Test
    fun emptyAndFactoryOnlyListsSelectNothing() {
        assertNull(selectRelease(emptyList(), FirmwareUpdateChannel.Early, now))
        val factoryOnly = listOf(release("v4.9.142.3", ageDays = 30))
        assertNull(selectRelease(factoryOnly, FirmwareUpdateChannel.Early, now))
        assertNull(selectRelease(factoryOnly, FirmwareUpdateChannel.Soaked, now))
    }

    @Test
    fun selectionOrdersByVersionNotPublishDate() {
        // A re-published or out-of-order-tagged older release must not win on
        // recency: 4.30.1 is newer by date but 4.31.0 is the newer version.
        val releases = listOf(
            release("v4.30.1", ageDays = 8),
            release("v4.31.0", ageDays = 9),
        )
        assertEquals("v4.31.0", selectRelease(releases, FirmwareUpdateChannel.Early, now)!!.version.raw)
        assertEquals("v4.31.0", selectRelease(releases, FirmwareUpdateChannel.Soaked, now)!!.version.raw)
    }
}
