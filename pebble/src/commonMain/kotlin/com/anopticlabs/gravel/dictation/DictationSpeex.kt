package com.anopticlabs.gravel.dictation

import com.anopticlabs.gravel.watchapp.WATCH_SPEEX_FRAME_SAMPLES
import com.anopticlabs.gravel.watchapp.WATCH_SPEEX_SAMPLE_RATE
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo

/** Whether a dictation session declares the frame size and rate the phone's Speex decoder produces. */
fun decodesOnThePhone(encoderInfo: VoiceEncoderInfo.Speex): Boolean =
    encoderInfo.frameSize == WATCH_SPEEX_FRAME_SAMPLES && encoderInfo.sampleRate == WATCH_SPEEX_SAMPLE_RATE.toLong()
