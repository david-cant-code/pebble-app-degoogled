package com.anopticlabs.gravel.ring.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RingCollectionTest {

    private val audioBits = bitString("1 010 011 0010 00011")
    private val audio = compressedRecord(0x40, audioBits.first, 9_997, audioBits.second)

    /** Every record in the order the ring writes them, the unused ones with filler payloads. */
    private fun observedOrder(clock: Long = 1_800_000_000, offset: Long = U32_MAX, final: Boolean = true) = collection(
        record(0x35, u32(offset)),
        partRecord(77, final),
        record(0x53, u32(0) + u32(1)),
        audio,
        record(0x02, u32(clock)),
        record(0x25, bytes(11, 0, 3, 75)),
        record(0x01, bytes(1, 2, 3, 4, 5, 6)),
        record(0x54, u32(1234)),
        record(0x37, u16(3_900)),
    )

    @Test
    fun aCollectionInTheRingsOrderYieldsItsAudioPartAndTime() {
        val parsed = parseRingCollection(observedOrder())

        assertEquals(9_997, parsed.audio.sampleRate)
        assertContentEquals(intArrayOf(0, 1, 1, 3, 2), parsed.audio.samples)
        assertEquals(77, parsed.startIndex)
        assertTrue(parsed.final)
        assertEquals(1_800_000_000, parsed.capturedAtUnixSeconds)
    }

    @Test
    fun beforeTheClockIsSetTheOffsetDatesTheCollection() {
        val parsed = parseRingCollection(observedOrder(clock = 100, offset = 1_700_000_000))

        assertEquals(1_700_000_101, parsed.capturedAtUnixSeconds)
    }

    @Test
    fun aCollectionWithoutClockRecordsHasNoTime() {
        assertNull(parseRingCollection(collection(partRecord(1, true), audio)).capturedAtUnixSeconds)
    }

    @Test
    fun aMiddlePartIsNotFinal() {
        assertFalse(parseRingCollection(observedOrder(final = false)).final)
    }

    @Test
    fun pcmSamplesAreSignedAndMovedToTheUnsignedScale() {
        val pcm = record(0x50, u32(16_000) + u16(0) + u16(0x7FFF) + u16(0x8000))

        val parsed = parseRingCollection(collection(partRecord(1, true), pcm))

        assertEquals(16_000, parsed.audio.sampleRate)
        assertContentEquals(intArrayOf(32768, 65535, 0), parsed.audio.samples)
    }

    @Test
    fun aRecordOfAnUnknownTagIsSkipped() {
        val parsed = parseRingCollection(collection(record(0x99, ByteArray(40)), partRecord(1, true), audio))

        assertContentEquals(intArrayOf(0, 1, 1, 3, 2), parsed.audio.samples)
    }

    @Test
    fun aCollectionWhoseHeaderDoesNotMatchItsSizeIsRefused() {
        val good = observedOrder()

        assertFailsWith<RingProtocolException> { parseRingCollection(good + bytes(0)) }
        assertFailsWith<RingProtocolException> { parseRingCollection(good.copyOf(good.size - 1)) }
        assertFailsWith<RingProtocolException> { parseRingCollection(good.copyOf().also { it[3] = 0xFF.toByte() }) }
        assertFailsWith<RingProtocolException> { parseRingCollection(good.copyOf().also { it[2] = 1 }) }
        assertFailsWith<RingProtocolException> { parseRingCollection(bytes(3, 0, 0)) }
    }

    /** A collection of [size] bytes: a large part index, about 3 KB of audio, and a padding record over 255 bytes. */
    private fun page(size: Int): ByteArray {
        val stream = ByteArray(3_000) { 0xFF.toByte() }
        val records = arrayOf(partRecord(0x0001_2345, true), compressedRecord(0x84, 24_000, 9_997, stream))
        val padding = size - 4 - records.sumOf { it.size } - 3
        return collection(*records, record(0x99, ByteArray(padding)))
    }

    @Test
    fun aPageOfTheSizeTheRingWritesIsParsedWithItsWideFields() {
        val parsed = parseRingCollection(page(4094))

        assertContentEquals(bytes(0xFE, 0x0F, 0x00, 0x00), page(4094).copyOf(4))
        assertEquals(0x0001_2345, parsed.startIndex)
        assertEquals(24_000, parsed.audio.samples.size)
        assertEquals(MAX_COLLECTION_SIZE, page(MAX_COLLECTION_SIZE).size)
        assertEquals(24_000, parseRingCollection(page(MAX_COLLECTION_SIZE)).audio.samples.size)
    }

    /** The overrun reads into a record whose tag byte, all ones, would decode cleanly. */
    @Test
    fun anAudioBitCountBeyondItsOwnRecordIsRefusedEvenWithRecordsAfterIt() {
        val (count, stream) = bitString("1 010 011 0010 00011")
        val overrun = compressedRecord(0x40, count + 8, 9_997, stream)
        val wideCount = compressedRecord(0x40, 0x0001_0010, 9_997, stream + bytes(0xFF))

        assertFailsWith<RingProtocolException> {
            parseRingCollection(collection(partRecord(1, true), overrun, record(0xFF, bytes(0xFF))))
        }
        assertFailsWith<RingProtocolException> { parseRingCollection(collection(partRecord(1, true), wideCount)) }
    }

    @Test
    fun aCollectionLargerThanAPageIsRefused() {
        val padding = record(0x99, ByteArray(MAX_COLLECTION_SIZE - 4 - 3 - partRecord(1, true).size - audio.size + 1))

        assertFailsWith<RingProtocolException> { parseRingCollection(collection(padding, partRecord(1, true), audio)) }
    }

    @Test
    fun aRecordRunningPastTheEndIsRefused() {
        val cut = collection(partRecord(1, true), audio, bytes(0x02, 4, 0, 1, 2))
        val wide = collection(partRecord(1, true), bytes(0x51) + u32(0x0100_0009L) + ByteArray(9))
        val header = collection(partRecord(1, true), audio, bytes(0x51, 0))

        assertFailsWith<RingProtocolException> { parseRingCollection(cut) }
        assertFailsWith<RingProtocolException> { parseRingCollection(wide) }
        assertFailsWith<RingProtocolException> { parseRingCollection(header) }
    }

    @Test
    fun aCollectionMissingItsAudioOrItsPartIsRefused() {
        assertFailsWith<RingProtocolException> { parseRingCollection(collection(partRecord(1, true))) }
        assertFailsWith<RingProtocolException> { parseRingCollection(collection(audio)) }
    }

    @Test
    fun aSecondRecordOfAUsedTagIsRefused() {
        val pcm = record(0x50, u32(16_000) + u16(0))

        assertFailsWith<RingProtocolException> { parseRingCollection(collection(partRecord(1, true), audio, audio)) }
        assertFailsWith<RingProtocolException> { parseRingCollection(collection(partRecord(1, true), audio, pcm)) }
        assertFailsWith<RingProtocolException> { parseRingCollection(collection(partRecord(1, true), partRecord(2, true), audio)) }
        assertFailsWith<RingProtocolException> {
            parseRingCollection(collection(partRecord(1, true), audio, record(0x02, u32(1)), record(0x02, u32(2))))
        }
    }

    @Test
    fun recordsShorterThanTheirFieldsAreRefused() {
        val shortPart = record(0x52, u32(1) + bytes(1))
        val shortClock = record(0x02, bytes(1, 2, 3))
        val shortAudio = record(0x51, bytes(0x40) + u32(0) + bytes(1, 2, 3))
        val oddPcm = record(0x50, u32(16_000) + bytes(1, 2, 3))

        assertFailsWith<RingProtocolException> { parseRingCollection(collection(shortPart, audio)) }
        assertFailsWith<RingProtocolException> { parseRingCollection(collection(partRecord(1, true), audio, shortClock)) }
        assertFailsWith<RingProtocolException> { parseRingCollection(collection(partRecord(1, true), shortAudio)) }
        assertFailsWith<RingProtocolException> { parseRingCollection(collection(partRecord(1, true), oddPcm)) }
    }

    @Test
    fun aSampleRateOfZeroOrAboveTheBoundIsRefused() {
        for (rate in listOf(0L, MAX_RING_SAMPLE_RATE + 1L, U32_MAX)) {
            val bad = compressedRecord(0x40, audioBits.first, rate, audioBits.second)
            assertFailsWith<RingProtocolException>("rate $rate") { parseRingCollection(collection(partRecord(1, true), bad)) }
        }
    }
}
