package coredevices.whisper

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the PCM16-to-float conversion the engine's input depends on. The
 * cases that matter: byte order (the watch pipeline is little-endian),
 * exact scaling at both range extremes, and the even-length precondition,
 * because a silent off-by-one here would degrade every transcription
 * rather than fail loudly.
 */
class Pcm16ToFloatsTest {

    @Test
    fun silenceDecodesToZeros() {
        val floats = pcm16ToFloats(ByteArray(64))
        assertEquals(32, floats.size)
        assertContentEquals(FloatArray(32), floats)
    }

    @Test
    fun emptyInputDecodesToEmptyOutput() {
        assertEquals(0, pcm16ToFloats(ByteArray(0)).size)
    }

    @Test
    fun littleEndianByteOrderIsHonored() {
        // 0x0001 little-endian is byte pair (0x01, 0x00); read in the
        // wrong order it would be 256/32768 instead of 1/32768.
        val floats = pcm16ToFloats(byteArrayOf(0x01, 0x00))
        assertEquals(1 / 32768f, floats[0])
    }

    @Test
    fun negativeFullScaleMapsToMinusOne() {
        // -32768 is (0x00, 0x80) little-endian; dividing by 32768 must land
        // exactly on -1.0, the engine's lower input bound.
        val floats = pcm16ToFloats(byteArrayOf(0x00, 0x80.toByte()))
        assertEquals(-1.0f, floats[0])
    }

    @Test
    fun positiveFullScaleStaysBelowOne() {
        // 32767 is (0xFF, 0x7F); the 32768 divisor keeps it just under 1.0.
        val floats = pcm16ToFloats(byteArrayOf(0xFF.toByte(), 0x7F))
        assertEquals(32767 / 32768f, floats[0])
    }

    @Test
    fun negativeSampleRoundTrips() {
        // -2 is (0xFE, 0xFF) little-endian: the sign reinterpretation of
        // the reassembled 16 bits is what this pins.
        val floats = pcm16ToFloats(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))
        assertEquals(-2 / 32768f, floats[0])
    }

    @Test
    fun oddLengthInputIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            pcm16ToFloats(ByteArray(3))
        }
    }

    @Test
    fun shortDecodeAndFloatScaleComposeToTheSameResult() {
        // The split exists so a resampler can sit between the two steps;
        // composed with no resampler they must equal the one-shot path.
        val bytes = byteArrayOf(0x01, 0x00, 0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F)
        assertContentEquals(pcm16ToFloats(bytes), shortsToFloats(pcm16ToShorts(bytes)))
    }

    @Test
    fun shortDecodeIsLittleEndianSigned() {
        val shorts = pcm16ToShorts(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x01, 0x00))
        assertEquals(-2, shorts[0].toInt())
        assertEquals(1, shorts[1].toInt())
    }
}
