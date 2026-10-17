package com.anopticlabs.gravel.dictation

import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class DictationSpeexTest {
    private fun session(sampleRate: Long, frameSize: Int) =
        VoiceEncoderInfo.Speex(sampleRate = sampleRate, version = "1.2", bitRate = 16_800, bitstreamVersion = 4, frameSize = frameSize)

    @Test
    fun onlyTheDecodersFrameAndRateDecodeOnThePhone() {
        val expected = listOf(
            session(16_000, 320) to true,
            session(16_000, 0) to false,
            session(16_000, 160) to false,
            session(16_000, 319) to false,
            session(16_000, 321) to false,
            session(16_000, 640) to false,
            session(16_000, 65_535) to false,
            session(0, 320) to false,
            session(8_000, 320) to false,
            session(15_999, 320) to false,
            session(16_001, 320) to false,
            session(32_000, 320) to false,
            session(48_000, 320) to false,
            session(4_294_967_295, 320) to false,
        )
        for ((info, decodes) in expected) {
            assertEquals(decodes, decodesOnThePhone(info), "${info.sampleRate} Hz, ${info.frameSize} samples")
        }
    }
}
