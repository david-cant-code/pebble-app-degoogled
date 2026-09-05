package coredevices.util.transcription

import io.rebble.libpebblecommon.voice.PEBBLE_FW_TRANSCRIPTION_TIMEOUT
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Debug-only dictation test hooks (see the `debug*` fields of
 * `STTConfig`). Each pure decision below repeats the debug-build check
 * instead of trusting the settings UI, so a stored flag can never act in
 * a release install that inherited a debug build's settings.
 */

/**
 * The fixed hold added after a decode when the slow-decode hook is on:
 * past the watch's result window by a margin, so one dictation exercises
 * the deadline report on any phone.
 */
internal val DEBUG_SLOW_DECODE_DELAY: Duration = PEBBLE_FW_TRANSCRIPTION_TIMEOUT + 5.seconds

/** Extra delay to hold a decode result for, zero unless the hook applies. */
internal fun debugDecodeDelay(slowDecode: Boolean, debugBuild: Boolean): Duration =
    if (slowDecode && debugBuild) DEBUG_SLOW_DECODE_DELAY else Duration.ZERO

/**
 * The 16 kHz mono PCM16 test clip bundled with debug builds, or null when
 * this is not a debug build or the clip is unavailable.
 */
expect fun debugDictationClip(): ByteArray?

/** Whether the substitute-audio hook applies, and a clip exists to substitute. */
fun debugSubstituteClip(substituteAudio: Boolean, debugBuild: Boolean): ByteArray? =
    if (substituteAudio && debugBuild) debugDictationClip() else null

/**
 * Archives the watch's codec frames for one dictation when the capture
 * dump hook is on in a debug build; a no-op otherwise. Lives beside the
 * decoded-audio dump so the two files of one dictation share a stamp.
 */
fun debugArchiveDictationFrames(captureDump: Boolean, debugBuild: Boolean, frames: List<ByteArray>) {
    if (captureDump && debugBuild && frames.isNotEmpty()) DictationCaptureDump.writeFrames(frames)
}
