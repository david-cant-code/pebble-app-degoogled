package com.anopticlabs.gravel.watchapp

import coredevices.speex.SpeexCodec
import coredevices.speex.SpeexDecodeResult
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo

/**
 * The samples the decoder writes per frame, whatever the session declares: kotlin-speex c61d18a
 * `initDecState` opens `speex_wb_mode`, whose `sb_decode` writes 2 x 160 (xiph/speex 05895229,
 * `sb_celp.c`, `modes_wb.c`).
 */
const val WATCH_SPEEX_FRAME_SAMPLES = 320

/** The rate of that wideband mode. */
const val WATCH_SPEEX_SAMPLE_RATE = 16_000

/**
 * Decodes a watch recording's Speex [frames] to 16-bit PCM at [WATCH_SPEEX_SAMPLE_RATE],
 * [WATCH_SPEEX_FRAME_SAMPLES] a frame; throws on a frame the codec refuses.
 */
@OptIn(ExperimentalUnsignedTypes::class)
fun decodeWatchSpeex(encoderInfo: VoiceEncoderInfo.Speex, frames: List<UByteArray>): ByteArray =
    SpeexCodec(sampleRate = WATCH_SPEEX_SAMPLE_RATE.toLong(), bitRate = encoderInfo.bitRate, frameSize = WATCH_SPEEX_FRAME_SAMPLES).use { speex ->
        val frameBytes = WATCH_SPEEX_FRAME_SAMPLES * Short.SIZE_BYTES
        val pcm = ByteArray(frameBytes)
        val out = ByteArray(frames.size * frameBytes)
        frames.forEachIndexed { i, frame ->
            val result = speex.decodeFrame(frame.asByteArray(), pcm, hasHeaderByte = true)
            check(result == SpeexDecodeResult.Success) { "Speex frame $i not decoded: $result" }
            pcm.copyInto(out, i * frameBytes)
        }
        out
    }
