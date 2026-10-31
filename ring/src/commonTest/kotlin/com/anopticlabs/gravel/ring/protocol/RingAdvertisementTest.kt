package com.anopticlabs.gravel.ring.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RingAdvertisementTest {

    private fun data(fingerprint: Long, count: Int, flags: Int) = u32(fingerprint) + bytes(count, flags)

    @Test
    fun theCountAndTheTwoFlagsAreRead() {
        val idle = parseRingAdvertisement(data(0x1234_FFFF, 0xAB, 0x98))!!
        val busy = parseRingAdvertisement(data(0x1234_FFFF, 0xAC, 0x60))!!

        assertEquals(RingAdvertisement(0x1234_FFFF, 0xAB, needsServicing = false, recording = false), idle)
        assertEquals(RingAdvertisement(0x1234_FFFF, 0xAC, needsServicing = true, recording = true), busy)
        assertTrue(parseRingAdvertisement(data(1, 0, 0x40))!!.needsServicing)
        assertTrue(parseRingAdvertisement(data(1, 0, 0x20))!!.recording)
    }

    @Test
    fun aFailsafeOrFactoryTestImageIsNotPairable() {
        assertFalse(parseRingAdvertisement(data(0xDEADDEAD, 0, 0))!!.pairable)
        assertFalse(parseRingAdvertisement(data(0xDEADBEEF, 0, 0))!!.pairable)
        assertTrue(parseRingAdvertisement(data(0x1234_FFFF, 0, 0))!!.pairable)
    }

    @Test
    fun shortDataIsNoAdvertisementAndLongerDataIsReadFromItsStart() {
        assertNull(parseRingAdvertisement(bytes(1, 2, 3, 4, 5)))
        assertEquals(7, parseRingAdvertisement(data(1, 7, 0) + bytes(9, 9))!!.collectionCount)
    }

    @Test
    fun theAdvertisementReadIsOneManufacturerDataStructure() {
        val read = bytes(0x09, 0xFF, 0xEA, 0x0E) + data(0x1234_FFFF, 5, 0x20)

        assertEquals(RingAdvertisement(0x1234_FFFF, 5, needsServicing = false, recording = true), parseRingAdvertisementRead(read))
    }

    @Test
    fun anAdvertisementReadOfAnotherShapeIsRefused() {
        val body = data(1, 5, 0)
        for (bad in listOf(
            bytes(0x0A, 0xFF, 0xEA, 0x0E) + body,
            bytes(0x09, 0x16, 0xEA, 0x0E) + body,
            bytes(0x09, 0xFF, 0xEB, 0x0E) + body,
            bytes(0x09, 0xFF, 0xEA, 0x0E) + body + bytes(0),
            bytes(0x09, 0xFF, 0xEA, 0x0E) + body.copyOf(5),
        )) {
            assertFailsWith<RingProtocolException> { parseRingAdvertisementRead(bad) }
        }
    }
}
