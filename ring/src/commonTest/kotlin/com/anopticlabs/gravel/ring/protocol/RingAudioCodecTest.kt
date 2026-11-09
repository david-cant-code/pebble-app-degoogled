package com.anopticlabs.gravel.ring.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class RingAudioCodecTest {

    private fun decode(config: Int, bits: Pair<Long, ByteArray>): IntArray =
        decodeRingAudio(config, bits.first, bits.second, 0, bits.second.size)

    @Test
    fun theUnaryCodesWithAThresholdOfFourAreNoneOneMinusOneTwoAndMinusThree() {
        val bits = bitString("1 010 011 0010 00011")

        assertContentEquals(intArrayOf(0, 1, 1, 3, 2), decode(0x40, bits))
    }

    @Test
    fun anEscapeCarriesTheRawValueAndSamplesKeepTheDroppedBitsZero() {
        val bits = bitString("0000 100000000000 1")

        assertContentEquals(intArrayOf(0x8000, 0x0000), decode(0x44, bits))
    }

    @Test
    fun theObservedConfigCodesUpToSevenInUnaryAndEscapesAfterSevenZeros() {
        val seven = bitString("0 0000001 0")
        val escape = bitString("0 0000000 111111111111")

        assertContentEquals(intArrayOf(7 shl 4), decode(0x84, seven))
        assertContentEquals(intArrayOf(0xFFF0), decode(0x84, escape))
    }

    @Test
    fun aBitCountOfZeroDecodesToNoSamples() {
        assertContentEquals(intArrayOf(), decodeRingAudio(0x84, 0, bytes(0xFF), 0, 1))
    }

    @Test
    fun bitsPastTheBitCountAreIgnored() {
        val (count, stream) = bitString("1 010 011 0010 00011")

        assertContentEquals(intArrayOf(0, 1, 1, 3, 2), decodeRingAudio(0x40, count, stream + bytes(0xFF), 0, stream.size + 1))
    }

    @Test
    fun aBitCountEndingInsideACodeIsRefused() {
        assertFailsWith<RingProtocolException> { decodeRingAudio(0x40, 2, bytes(0x40), 0, 1) }
    }

    @Test
    fun aBitCountBeyondTheStreamIsRefused() {
        assertFailsWith<RingProtocolException> { decodeRingAudio(0x40, 9, bytes(0xFF), 0, 1) }
    }

    @Test
    fun everyConfigDecodesWhatTheTestEncoderWrote() {
        for (config in 0..0xFF) {
            val dropped = config and 0xF
            val words = testWords(300, 16 - dropped, seed = config.toLong())

            val decoded = decode(config, encodeRingAudio(config, words))

            assertContentEquals(IntArray(words.size) { words[it] shl dropped }, decoded, "config 0x${config.toString(16)}")
        }
    }
}
