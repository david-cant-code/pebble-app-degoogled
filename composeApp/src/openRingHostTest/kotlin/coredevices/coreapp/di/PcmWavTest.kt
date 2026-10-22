package coredevices.coreapp.di

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PcmWavTest {
    @Test
    fun headerAndOneSample() {
        val wav = pcm16LeWav(shortArrayOf(0x1234), 16_000)
        assertEquals(46, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals("fmt ", wav.decodeToString(12, 16))
        assertEquals("data", wav.decodeToString(36, 40))
        assertContentEquals(byteArrayOf(0x34, 0x12), wav.copyOfRange(44, 46))
        assertEquals(16_000, le32(wav, 24))
        assertEquals(2, le16(wav, 32))
    }

    @Test
    fun emptyRecordingIsAHeaderOnly() {
        val wav = pcm16LeWav(shortArrayOf(), 8_000)
        assertEquals(44, wav.size)
        assertEquals(0, le32(wav, 40))
    }
}

private fun le16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

private fun le32(bytes: ByteArray, at: Int): Int = le16(bytes, at) or (le16(bytes, at + 2) shl 16)
