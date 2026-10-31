package com.anopticlabs.gravel.ring.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RingRangeTest {

    @Test
    fun theRangeIsTwoSixteenBitIndexes() {
        assertEquals(RingRange(0x1234, 0xFFFE), parseRingRange(bytes(0x34, 0x12, 0xFE, 0xFF)))
        assertFailsWith<RingProtocolException> { parseRingRange(bytes(1, 2, 3)) }
        assertFailsWith<RingProtocolException> { parseRingRange(bytes(1, 2, 3, 4, 5)) }
    }

    @Test
    fun theSizeCountsAcrossTheWrap() {
        assertEquals(10, RingRange(65530, 4).size)
        assertEquals(0, RingRange(7, 7).size)
    }

    @Test
    fun aSyncResumesAtTheSavedIndexWhileItIsInTheRangeOrAtItsEnd() {
        val range = RingRange(10, 20)

        assertEquals(10, range.resumeAt(null))
        assertEquals(15, range.resumeAt(15))
        assertEquals(20, range.resumeAt(20), "everything stored was synced")
        assertEquals(10, range.resumeAt(21))
        assertEquals(10, range.resumeAt(9))
    }

    @Test
    fun aSyncResumesAcrossTheWrap() {
        val range = RingRange(65530, 4)

        assertEquals(2, range.resumeAt(2))
        assertEquals(65535, range.resumeAt(65535))
        assertEquals(65530, range.resumeAt(65529))
        assertEquals(65530, range.resumeAt(5))
    }

    @Test
    fun aSavedIndexOutsideSixteenBitsIsRefused() {
        assertFailsWith<IllegalArgumentException> { RingRange(0, 1).resumeAt(0x1_0000) }
    }

    @Test
    fun theNextIndexWraps() {
        assertEquals(1, nextRingIndex(0))
        assertEquals(0, nextRingIndex(65535))
    }
}
