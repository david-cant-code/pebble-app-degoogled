package coredevices.pebble.firmware

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the registry contract the verified installer fails closed against:
 * exact-URL lookup, recording-order eviction, and the hash normalization
 * that decides whether a source's hash is usable at all.
 */
class FirmwareArtifactExpectationsTest {

    private fun expected(tag: String) = ExpectedFirmwareArtifact(
        sha256Hex = "ab".repeat(32),
        sizeBytes = 123L,
        versionTag = tag,
    )

    @Test
    fun recordThenLookupReturnsEntry() = runTest {
        val registry = FirmwareArtifactExpectations()
        registry.record("https://example.com/a.pbz", expected("v4.31.1"))
        assertEquals(expected("v4.31.1"), registry.lookup("https://example.com/a.pbz"))
        assertNull(registry.lookup("https://example.com/other.pbz"))
    }

    @Test
    fun oldestEntryIsEvictedPastTheCap() = runTest {
        val registry = FirmwareArtifactExpectations()
        repeat(17) { i -> registry.record("https://example.com/$i.pbz", expected("v$i")) }
        assertNull(registry.lookup("https://example.com/0.pbz"))
        assertEquals(expected("v1"), registry.lookup("https://example.com/1.pbz"))
        assertEquals(expected("v16"), registry.lookup("https://example.com/16.pbz"))
    }

    @Test
    fun reRecordingRefreshesEvictionOrder() = runTest {
        val registry = FirmwareArtifactExpectations()
        repeat(16) { i -> registry.record("https://example.com/$i.pbz", expected("v$i")) }
        // Re-record the oldest, then push one more: entry 1 (now oldest) goes,
        // entry 0 stays.
        registry.record("https://example.com/0.pbz", expected("v0new"))
        registry.record("https://example.com/16.pbz", expected("v16"))
        assertEquals(expected("v0new"), registry.lookup("https://example.com/0.pbz"))
        assertNull(registry.lookup("https://example.com/1.pbz"))
    }

    @Test
    fun normalizeAcceptsPrefixedAndBareHexAndLowercases() {
        val hex = "AB".repeat(32)
        assertEquals("ab".repeat(32), normalizeSha256Hex("sha256:$hex"))
        assertEquals("ab".repeat(32), normalizeSha256Hex(hex))
        assertEquals("12".repeat(32), normalizeSha256Hex(" ${"12".repeat(32)} "))
    }

    @Test
    fun normalizeRejectsAnythingElse() {
        assertNull(normalizeSha256Hex(null))
        assertNull(normalizeSha256Hex(""))
        assertNull(normalizeSha256Hex("sha256:"))
        assertNull(normalizeSha256Hex("ab".repeat(31)))
        assertNull(normalizeSha256Hex("ab".repeat(33)))
        assertNull(normalizeSha256Hex("zz".repeat(32)))
        assertNull(normalizeSha256Hex("sha512:" + "ab".repeat(32)))
    }
}
