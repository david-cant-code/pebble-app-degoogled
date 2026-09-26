package coredevices.pebble.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the tag parsing and comparison the GitHub update path depends on and
 * which upstream's FirmwareVersion cannot provide: upstream's regex find()
 * would truncate "v4.9.142.3" to 4.9.142, making factory tags structurally
 * invisible and factory hotfixes mutually equal.
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
}
