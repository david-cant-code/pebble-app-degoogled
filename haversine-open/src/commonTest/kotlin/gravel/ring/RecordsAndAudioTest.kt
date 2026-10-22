@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package gravel.ring

import coredevices.haversine.ButtonSequenceDebouncer
import coredevices.haversine.DataDecodeException
import coredevices.haversine.KMPHaversineAdvertisement
import coredevices.haversine.KMPHaversineSatellite
import coredevices.haversine.SatelliteOps
import coredevices.haversine.TransferStatus
import coredevices.haversine.fingerprintMatchesFailsafe
import coredevices.haversine.fingerprintMatchesPTF
import coredevices.haversine.memoryRecording
import coredevices.haversine.parseAdvertisement
import coredevices.haversine.removeDCBias
import gravel.ring.audio.decodeAudioRecord
import gravel.ring.records.buttonWords
import gravel.ring.records.collectionUnix
import gravel.ring.records.parseCollection
import gravel.ring.records.parsePlatformVersion
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class RecordsAndAudioTest {
    @Test
    fun advertisementFingerprintAndFlags() {
        val failsafe = byteArrayOf(0xAD.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0xDE.toByte(), 4, 0x60)
        assertEquals(0xDEAD_DEADL, KMPHaversineAdvertisement.parseToStateFingerprint(failsafe))
        assertTrue(fingerprintMatchesFailsafe(0xDEAD_DEADL))
        assertTrue(fingerprintMatchesPTF(0xDEAD_BEEFL))
        assertFalse(fingerprintMatchesFailsafe(0xDEAD_BEEFL))
        assertNull(KMPHaversineAdvertisement.parseToStateFingerprint(byteArrayOf(1, 2, 3)))
        val parsed = parseAdvertisement(failsafe, rssi = -40f, timestamp = Instant.fromEpochMilliseconds(5))
        assertNotNull(parsed)
        assertEquals(4, parsed.collectionCount)
        assertTrue(parsed.needsServicing)
        assertTrue(parsed.recordingInProgress)
        val short = byteArrayOf(1, 0, 0, 0)
        assertNotNull(KMPHaversineAdvertisement.parseToStateFingerprint(short))
        assertEquals(0, parseAdvertisement(short, 0f, Instant.fromEpochMilliseconds(0))!!.collectionCount)
        val image = byteArrayOf(0x09, 0xFF.toByte(), 0xEA.toByte(), 0x0E, 0, 0, 0, 0, 0, 0x20)
        assertTrue(memoryRecording(image))
    }

    @Test
    fun headersTimestampsAndUnknownTags() {
        val parsed = parseCollection(
            collectionOf(
                record16(0x99, byteArrayOf(1, 2, 3)),
                clockRecords(100, 5),
                tapRecord(1, 2),
                record16(0x37, u16leBytes(4100)),
                record16(0x54, u32leBytes(9)),
            ),
        )
        assertEquals(106L, parsed.unixSeconds)
        assertEquals(106L, collectionUnix(100, 5))
        assertNull(collectionUnix(1, null))
        assertEquals(4100, parsed.batteryMillivolts)
        assertEquals(9L, parsed.lifetimeCount)
        assertEquals("long short", buttonWords(1, 2, 0).first)
        assertEquals("3.75.0", parsePlatformVersion(byteArrayOf(3, 75, 0, 11))!!.firmware)
        assertEquals(11 to 0, parsePlatformVersion(byteArrayOf(3, 75, 0, 11))!!.hardware)
        assertEquals("3.75.0", parsePlatformVersion("3.75.0".encodeToByteArray())!!.firmware)
    }

    @Test
    fun alternateHeadersAndOverrunCarryTheBytes() {
        val body = tapRecord(0, 1)
        val end = 3 + body.size
        val flagged = byteArrayOf(0xFF.toByte(), (end and 0xFF).toByte(), ((end shr 8) and 0xFF).toByte()) + body
        assertEquals(1L, parseCollection(flagged).pressCount)
        val bigEnd = 3 + body.size
        val big = byteArrayOf(0, ((bigEnd shr 8) and 0xFF).toByte(), (bigEnd and 0xFF).toByte()) + body
        assertEquals(1L, parseCollection(big).pressCount)
        val bad = byteArrayOf(1, 0, 0, 0)
        val error = assertFailsWith<DataDecodeException> { parseCollection(bad) }
        assertContentEquals(bad, error.data)
    }

    @Test
    fun randomCollectionsEitherParseOrCarryTheBytes() {
        val random = Random(1)
        repeat(40) {
            val bytes = ByteArray(random.nextInt(48)) { random.nextInt().toByte() }
            try {
                parseCollection(bytes)
            } catch (e: DataDecodeException) {
                assertNotNull(e.data)
                assertContentEquals(bytes, e.data)
            }
        }
    }

    @Test
    fun syntheticRiceVectorAndRoundTrip() {
        val payload = byteArrayOf(0x44, 5, 0, 0, 0, 0x0D, 0x27, 0, 0, 0xD0.toByte())
        val decoded = decodeAudioRecord(payload)
        assertEquals(9997u, decoded.sampleRate)
        assertContentEquals(shortArrayOf(-32768, -32768, -32752), decoded.samples)
        val collection = parseHex("13 00 00 00 51 0A 00 00 00 44 05 00 00 00 0D 27 00 00 D0")
        assertContentEquals(decoded.samples, parseCollection(collection).audio!!.samples)

        val words = intArrayOf(0, 1, 3, 2, 8)
        val encoded = encodeRice(words, escape = 4, dropped = 4, rate = 9997u)
        val again = decodeAudioRecord(encoded)
        val expected = ShortArray(words.size) { ((words[it] shl 4) - 0x8000).toShort() }
        assertContentEquals(expected, again.samples)

        val random = Random(2)
        repeat(20) {
            val escape = random.nextInt(2, 8)
            val dropped = random.nextInt(0, 5)
            val width = 16 - dropped
            val mask = (1 shl width) - 1
            val signal = IntArray(30) { random.nextInt(mask + 1) }
            val back = decodeAudioRecord(encodeRice(signal, escape, dropped, 16_000u))
            val want = ShortArray(signal.size) { ((signal[it] shl dropped) - 0x8000).toShort() }
            assertContentEquals(want, back.samples)
        }
        assertFailsWith<DataDecodeException> {
            decodeAudioRecord(byteArrayOf(0x44, 0x20, 0, 0, 0, 1, 0, 0, 0, 0))
        }
        assertEquals(0, decodeAudioRecord(byteArrayOf(0x44, 0, 0, 0, 0, 1, 0, 0, 0)).samples.size)
    }

    @Test
    fun removeDcBiasSubtractsTheRoundedMean() {
        val samples = shortArrayOf(1, 2, 3)
        removeDCBias(samples)
        assertContentEquals(shortArrayOf(-1, 0, 1), samples)
        val odd = shortArrayOf(1, 2)
        removeDCBias(odd)
        assertContentEquals(shortArrayOf(-1, 0), odd)
        val empty = shortArrayOf()
        removeDCBias(empty)
        assertEquals(0, empty.size)
    }

    @Test
    fun debouncerKeepsTheLastGestureInTheQuietWindow() = runTest {
        val debouncer = ButtonSequenceDebouncer(this)
        val got = mutableListOf<String>()
        val job = launch { debouncer.buttonGestures.collect { got += it.sequence } }
        debouncer.onTransferStatus(gesture("short", audio = false))
        advanceTimeBy(100.milliseconds)
        debouncer.onTransferStatus(gesture("long", audio = false))
        debouncer.onTransferStatus(gesture("short", audio = true))
        advanceTimeBy(1500.milliseconds)
        advanceUntilIdle()
        assertEquals(listOf("long"), got)
        job.cancel()
    }
}

private fun gesture(sequence: String, audio: Boolean): TransferStatus.TransferTypeDetermined =
    TransferStatus.TransferTypeDetermined(
        satellite = dummySatellite,
        isAudio = audio,
        buttonSequence = sequence,
        collectionStartIndex = 0,
        collectionIndex = 0,
        final = true,
        advertisementReceivedTimestamp = Instant.fromEpochMilliseconds(0),
        lifetimeCollectionCount = null,
    )

private val dummySatellite = KMPHaversineSatellite(
    "x",
    null,
    object : SatelliteOps {
        override suspend fun eraseCollections(satellite: KMPHaversineSatellite) = Unit
    },
)

private class BitWriter {
    private val bits = ArrayList<Int>()
    fun bit(value: Int) {
        bits += value
    }

    fun bits(value: Int, width: Int) {
        for (shift in width - 1 downTo 0) bit((value shr shift) and 1)
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        bits.forEachIndexed { index, bit ->
            if (bit == 1) out[index / 8] = (out[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
        }
        return out
    }

    val size: Int get() = bits.size
}

private fun encodeRice(words: IntArray, escape: Int, dropped: Int, rate: UInt): ByteArray {
    val width = 16 - dropped
    val mask = (1 shl width) - 1
    val writer = BitWriter()
    var previousWord = 0
    var previousDelta = 0
    for (word in words) {
        val delta = (word - previousWord) and mask
        val residual = (delta - previousDelta) and mask
        writeResidual(writer, residual, escape, width, mask)
        previousDelta = delta
        previousWord = word and mask
    }
    val bits = writer.toByteArray()
    val payload = ByteArray(9 + bits.size)
    payload[0] = ((escape shl 4) or dropped).toByte()
    payload.putU32le(1, writer.size.toLong())
    payload.putU32le(5, rate.toLong())
    bits.copyInto(payload, 9)
    return payload
}

private fun writeResidual(writer: BitWriter, value: Int, escape: Int, width: Int, mask: Int) {
    if (value == 0) {
        writer.bit(1)
        return
    }
    if (value in 1 until escape) {
        writer.bit(0)
        repeat(value - 1) { writer.bit(0) }
        writer.bit(1)
        writer.bit(0)
        return
    }
    val negative = (mask + 1 - value) and mask
    if (negative in 1 until escape) {
        writer.bit(0)
        repeat(negative - 1) { writer.bit(0) }
        writer.bit(1)
        writer.bit(1)
        return
    }
    writer.bit(0)
    repeat(escape - 1) { writer.bit(0) }
    writer.bits(value, width)
}
