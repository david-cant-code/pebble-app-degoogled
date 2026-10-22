package com.anopticlabs.gravel.notebook.capture

import com.anopticlabs.gravel.notebook.pipeline.CAPTURE_SAMPLE_RATE
import com.anopticlabs.gravel.notebook.pipeline.MAX_CAPTURE_MILLIS
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingBufferTest {

    private fun ByteArray.int32(at: Int): Int =
        (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8) or
            ((this[at + 2].toInt() and 0xff) shl 16) or ((this[at + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.int16(at: Int): Int = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.ascii(at: Int, n: Int): String = (at until at + n).map { this[it].toInt().toChar() }.joinToString("")

    @Test
    fun theWavIsACanonicalMonoPcm16FileAtTheCaptureRate() {
        val buffer = RecordingBuffer()
        buffer.append(shortArrayOf(1, -2, 0x1234, 99), 3)

        val wav = buffer.toWav()

        assertEquals(WAV_HEADER_BYTES + 6, wav.size)
        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals(36 + 6, wav.int32(4))
        assertEquals("WAVEfmt ", wav.ascii(8, 8))
        assertEquals(16, wav.int32(16))
        assertEquals(1, wav.int16(20))
        assertEquals(1, wav.int16(22))
        assertEquals(CAPTURE_SAMPLE_RATE, wav.int32(24))
        assertEquals(CAPTURE_SAMPLE_RATE * 2, wav.int32(28))
        assertEquals(2, wav.int16(32))
        assertEquals(16, wav.int16(34))
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(6, wav.int32(40))
        assertContentEquals(byteArrayOf(1, 0, 0xfe.toByte(), 0xff.toByte(), 0x34, 0x12), wav.copyOfRange(44, 50))
    }

    @Test
    fun samplesPastTheCapAreNotTaken() {
        val buffer = RecordingBuffer(maxSamples = 10)

        assertEquals(7, buffer.append(ShortArray(7) { it.toShort() }, 7))
        assertFalse(buffer.isFull)
        assertEquals(3, buffer.append(ShortArray(7) { (100 + it).toShort() }, 7))
        assertTrue(buffer.isFull)
        assertEquals(0, buffer.append(ShortArray(7), 7))

        assertEquals(10, buffer.samples)
        val wav = buffer.toWav()
        assertEquals(20, wav.int32(40))
        assertEquals(102, wav.int16(44 + 2 * 9))
    }

    @Test
    fun theDefaultCapIsTheSpeechEnginesLimit() {
        val buffer = RecordingBuffer()
        val chunk = ShortArray(CAPTURE_SAMPLE_RATE)
        while (!buffer.isFull) buffer.append(chunk, chunk.size)
        assertEquals(MAX_CAPTURE_MILLIS.toLong(), buffer.millis)
        assertEquals(MAX_RECORDING_SAMPLES, buffer.samples)
    }

    @Test
    fun underOneSecondIsTooShort() {
        val buffer = RecordingBuffer()
        buffer.append(ShortArray(CAPTURE_SAMPLE_RATE - 1), CAPTURE_SAMPLE_RATE - 1)
        assertTrue(buffer.isTooShort)
        buffer.append(ShortArray(1), 1)
        assertFalse(buffer.isTooShort)
        assertEquals(MIN_RECORDING_MILLIS.toLong(), buffer.millis)
    }

    @Test
    fun aCountOutsideTheArrayIsRefused() {
        val buffer = RecordingBuffer()
        assertFailsWith<IllegalArgumentException> { buffer.append(ShortArray(4), 5) }
        assertFailsWith<IllegalArgumentException> { buffer.append(ShortArray(4), -1) }
        assertEquals(0, buffer.samples)
    }

    @Test
    fun pcmAtAnyRateBecomesAWavOfThatRateAndLength() {
        val pcm = ByteArray(8_000 * 2 * 3) { (it % 7).toByte() }
        val wav = pcm16Wav(pcm, 8_000)
        assertEquals(3_000L, wavDurationMillis(wav))
        assertContentEquals(pcm, wav.copyOfRange(WAV_HEADER_BYTES, wav.size))
        val fromBuffer = RecordingBuffer().apply { append(ShortArray(16_000) { 3 }, 16_000) }.toWav()
        assertContentEquals(fromBuffer, pcm16Wav(fromBuffer.copyOfRange(WAV_HEADER_BYTES, fromBuffer.size), CAPTURE_SAMPLE_RATE))
        assertFailsWith<IllegalArgumentException> { pcm16Wav(ByteArray(3), 8_000) }
    }
}
