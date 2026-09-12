package coredevices.whisper

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the checks the engine service applies to what the app process
 * sends it and to what it writes back, and the isolated-uid predicate
 * the pre-API 28 process check and the on-device isolation test share.
 */
class WhisperEngineRequestsTest {

    @Test
    fun theSampleCountMustFitTheRegionItArrivedWith() {
        assertTrue(audioFitsRegion(samples = 0, regionBytes = 4))
        assertTrue(audioFitsRegion(samples = 240_000, regionBytes = 960_000))
        assertFalse(audioFitsRegion(samples = 240_001, regionBytes = 960_000), "one sample past the region")
        assertFalse(audioFitsRegion(samples = -1, regionBytes = 960_000), "a negative count is not a short read")
        assertFalse(audioFitsRegion(samples = Int.MAX_VALUE, regionBytes = Int.MAX_VALUE), "the byte product must not wrap")
    }

    @Test
    fun failureMessagesAreCutAtTheBoundTheClientReads() {
        assertContentEquals("short".toByteArray(), boundedMessageBytes("short"))
        val long = "x".repeat(WhisperEngineProtocol.MAX_MESSAGE_BYTES + 100)
        assertEquals(WhisperEngineProtocol.MAX_MESSAGE_BYTES, boundedMessageBytes(long).size)
        assertEquals(WhisperEngineProtocol.MAX_MESSAGE_BYTES, boundedMessageBytes("x".repeat(WhisperEngineProtocol.MAX_MESSAGE_BYTES)).size)
    }

    /** The platform's isolated range is 99000 to 99999 within each user's block of 100000. */
    @Test
    fun theIsolatedRangeIsPinnedAtBothEndsForEveryUser() {
        assertTrue(isIsolatedUid(99_000))
        assertTrue(isIsolatedUid(99_999))
        assertFalse(isIsolatedUid(98_999))
        assertFalse(isIsolatedUid(100_000), "the next user's first uid")
        assertTrue(isIsolatedUid(1_099_000), "the second user's isolated range")
        assertTrue(isIsolatedUid(1_099_999))
        assertFalse(isIsolatedUid(1_100_000))
        assertFalse(isIsolatedUid(10_123), "an app uid")
        assertFalse(isIsolatedUid(0), "root")
        assertFalse(isIsolatedUid(2_000), "shell")
    }
}
