package com.anopticlabs.gravel.ring.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RingRequestTest {

    private fun request(type: Int, address: Long, length: Long) = bytes(type) + u32(address) + u32(0) + u32(length)

    @Test
    fun theRangeReadAsksForFourBytes() {
        assertContentEquals(request(3, 0x4003_0005, 4), RingRequest.ReadRange.encode())
    }

    @Test
    fun aCollectionReadAsksForTheWholeObjectAtItsIndex() {
        assertContentEquals(request(3, 0x4002_1234, 0), RingRequest.ReadCollection(0x1234).encode())
        assertContentEquals(request(3, 0x4002_FFFF, 0), RingRequest.ReadCollection(0xFFFF).encode())
    }

    @Test
    fun aCollectionIndexOutsideSixteenBitsIsRefused() {
        assertFailsWith<IllegalArgumentException> { RingRequest.ReadCollection(-1) }
        assertFailsWith<IllegalArgumentException> { RingRequest.ReadCollection(0x1_0000) }
    }

    @Test
    fun theAdvertisementReadAsksForTenBytes() {
        assertContentEquals(request(3, 0x4003_000E, 10), RingRequest.ReadAdvertisement.encode())
    }

    @Test
    fun theClockWriteCarriesTheSecondsAsItsPayload() {
        val write = RingRequest.SetClock.at(0x6553_F100)

        assertContentEquals(request(2, 0x4003_0001, 4), write.encode())
        assertContentEquals(bytes(0x00, 0xF1, 0x53, 0x65), write.payload)
        assertTrue(write.isWrite)
        assertFalse(RingRequest.ReadRange.isWrite)
        assertEquals(0, RingRequest.ReadRange.payload.size)
    }

    /** Lists every request type with no else branch, so a new type stops this test compiling. */
    @Test
    fun theRequestsAreThreeReadsAndTheClockWrite() {
        fun type(request: RingRequest): Int = when (request) {
            RingRequest.ReadRange, is RingRequest.ReadCollection, RingRequest.ReadAdvertisement -> 3
            is RingRequest.SetClock -> 2
        }
        val all = listOf(RingRequest.ReadRange, RingRequest.ReadCollection(0), RingRequest.ReadAdvertisement, RingRequest.SetClock.at(0))

        for (request in all) assertEquals(type(request), request.encode()[0].toInt(), "$request")
    }

    @Test
    fun aClockOutsideUnsigned32BitSecondsIsRefused() {
        assertFailsWith<IllegalArgumentException> { RingRequest.SetClock.at(-1) }
        assertFailsWith<IllegalArgumentException> { RingRequest.SetClock.at(U32_MAX + 1) }
    }
}
