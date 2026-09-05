package io.rebble.libpebblecommon.voice

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration.Companion.seconds

interface TranscriptionProvider {
    suspend fun transcribe(
        encoderInfo: VoiceEncoderInfo,
        audioFrames: Flow<UByteArray>,
        isNotificationReply: Boolean
    ): TranscriptionResult
    suspend fun canServeSession(): Boolean
}

/**
 * The maximum amount of time the Pebble firmware will wait for a transcription result before timing
 * out and cancelling the session.
 */
val PEBBLE_FW_TRANSCRIPTION_TIMEOUT = 15.seconds

/**
 * The longest recording the firmware makes before it stops the audio
 * transfer on its own; the longest dictation the phone has to decode.
 */
val PEBBLE_FW_RECORDING_CAP = 15.seconds

/**
 * How much before [PEBBLE_FW_TRANSCRIPTION_TIMEOUT] the phone reports a
 * decode that has not finished, so the phone reports the loss, not the
 * watch.
 */
val DEADLINE_MARGIN = 1.seconds

/**
 * The time a decode has after the recording ends before the session
 * coordinator reports it as lost. Every phone-side figure derived from
 * the watch's result clock (the speed nudge, the model picker's fit
 * classes) is built on this one value.
 */
val DICTATION_DEADLINE = PEBBLE_FW_TRANSCRIPTION_TIMEOUT - DEADLINE_MARGIN
