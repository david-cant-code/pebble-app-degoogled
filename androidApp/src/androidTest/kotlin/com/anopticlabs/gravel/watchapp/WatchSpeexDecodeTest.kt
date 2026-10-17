package com.anopticlabs.gravel.watchapp

import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import org.junit.Test
import kotlin.test.assertEquals

/** The watch recording decode against the codec's native library, which no host test can load. */
@OptIn(ExperimentalUnsignedTypes::class)
class WatchSpeexDecodeTest {

    // A header byte and two zero bytes, a frame the decoder accepts.
    private val frame = ubyteArrayOf(0u, 0u, 0u)

    @Test
    fun eachFrameDecodesToTheDecodersFrameWhateverTheSessionDeclares() {
        for (declared in listOf(160, WATCH_SPEEX_FRAME_SAMPLES)) {
            val info = VoiceEncoderInfo.Speex(sampleRate = 16_000, version = "1.2", bitRate = 16_800, bitstreamVersion = 4, frameSize = declared)
            assertEquals(2 * WATCH_SPEEX_FRAME_SAMPLES * Short.SIZE_BYTES, decodeWatchSpeex(info, listOf(frame, frame)).size, "declared $declared")
        }
    }
}
