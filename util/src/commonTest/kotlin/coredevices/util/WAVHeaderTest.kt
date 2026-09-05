package coredevices.util

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins the 44-byte canonical PCM WAV header every upload and capture in
 * the tree is built on: a replay tool and both transcription server
 * families parse exactly this layout.
 */
class WAVHeaderTest {

    private fun ByteArray.leInt(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.leShort(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun headerDescribesMono16BitPcm() {
        val pcm = ByteArray(1000) { it.toByte() }
        val wav = Buffer().apply {
            writeWavHeader(16_000, pcm.size)
            write(pcm)
        }.readByteArray()
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals(36 + pcm.size, wav.leInt(4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals("fmt ", wav.decodeToString(12, 16))
        assertEquals(16, wav.leInt(16))
        assertEquals(1, wav.leShort(20), "PCM format tag")
        assertEquals(1, wav.leShort(22), "mono")
        assertEquals(16_000, wav.leInt(24))
        assertEquals(32_000, wav.leInt(28), "byte rate")
        assertEquals(2, wav.leShort(32), "block align")
        assertEquals(16, wav.leShort(34), "bits per sample")
        assertEquals("data", wav.decodeToString(36, 40))
        assertEquals(pcm.size, wav.leInt(40))
        assertContentEquals(pcm, wav.copyOfRange(44, wav.size))
    }
}
