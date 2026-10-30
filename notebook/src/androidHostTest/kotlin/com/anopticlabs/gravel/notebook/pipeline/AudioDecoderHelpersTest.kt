package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder.Companion.PCM_16BIT
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder.Companion.PCM_FLOAT
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder.Companion.checkedRate
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder.Companion.downmixInto
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder.Companion.initialSamples
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder.Companion.maxSamplesAt
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder.Companion.resampleTo16k
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder.Companion.toPcm16Bytes
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder.Companion.toShorts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The decoder's sample arithmetic; the codec loop itself runs only on a device. */
class AudioDecoderHelpersTest {

    @Test
    fun stereoIsAveragedToMonoAndAPartialFrameDropped() {
        val mono = MonoPcm()
        downmixInto(mono, shortArrayOf(100, 300, -200, 0, 7), channels = 2)
        assertContentEquals(shortArrayOf(200, -100), mono.toArray())
    }

    @Test
    fun monoPassesThroughAndTheBufferGrows() {
        val mono = MonoPcm()
        val big = ShortArray(400_000) { (it % 100).toShort() }
        downmixInto(mono, big, channels = 1)
        downmixInto(mono, shortArrayOf(5), channels = 1)
        assertEquals(400_001, mono.size)
        assertEquals(5, mono.toArray().last().toInt())
    }

    @Test
    fun sixteenBitAndFloatOutputBecomeSamples() {
        val pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(1234).putShort(-5).flip() as ByteBuffer
        assertContentEquals(shortArrayOf(1234, -5), toShorts(pcm, PCM_16BIT))
        val floats = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).putFloat(-2f).putFloat(0.5f).flip() as ByteBuffer
        assertContentEquals(shortArrayOf(Short.MAX_VALUE, (-Short.MAX_VALUE).toShort(), (Short.MAX_VALUE / 2).toShort()), toShorts(floats, PCM_FLOAT))
    }

    @Test
    fun anUnknownEncodingIsFlagged() {
        val failure = assertFailsWith<CaptureProcessingException> { toShorts(ByteBuffer.allocate(4), 3) }
        assertEquals(CaptureFailureKind.FLAG, failure.kind)
        assertEquals(CaptureFailureReason.UNREADABLE_AUDIO, failure.reason)
    }

    @Test
    fun samplesAreLittleEndianBytes() {
        assertContentEquals(byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()), toPcm16Bytes(shortArrayOf(0x1234, -1)))
    }

    @Test
    fun resamplingKeepsTheDurationAndLeaves16kAlone() {
        val second = ShortArray(44_100)
        assertEquals(CAPTURE_SAMPLE_RATE, resampleTo16k(second, 44_100).size)
        val same = ShortArray(16_000) { it.toShort() }
        assertContentEquals(same, resampleTo16k(same, CAPTURE_SAMPLE_RATE))
    }

    /** Five minutes at 44.1 kHz resamples to exactly the speech engine's five minutes. */
    @Test
    fun theCapAt44kResamplesToTheEnginesCap() {
        val atCap = ShortArray(maxSamplesAt(44_100).toInt())
        assertEquals(CAPTURE_SAMPLE_RATE.toLong() * MAX_CAPTURE_MILLIS / 1000, resampleTo16k(atCap, 44_100).size.toLong())
    }

    /** The rate bound keeps what a decode holds in memory within five minutes at 48 kHz, whatever a file declares. */
    @Test
    fun ratesAboveTheBoundAreFlagged() {
        assertEquals(48_000, checkedRate(48_000))
        for (rate in listOf(0, -1, 96_000, 384_000)) {
            val failure = assertFailsWith<CaptureProcessingException>("$rate") { checkedRate(rate) }
            assertEquals(CaptureFailureKind.FLAG, failure.kind)
            assertEquals(CaptureFailureReason.UNREADABLE_AUDIO, failure.reason)
        }
    }

    @Test
    fun theBufferIsSizedFromTheDeclaredDurationWithinTheCap() {
        assertEquals(MonoPcm.INITIAL, initialSamples(-1, 44_100))
        assertEquals(10 * 44_100 + 44_100, initialSamples(10_000_000, 44_100))
        assertEquals(maxSamplesAt(48_000).toInt() + 1, initialSamples(Long.MAX_VALUE / 1_000_000, 48_000))
    }
}
