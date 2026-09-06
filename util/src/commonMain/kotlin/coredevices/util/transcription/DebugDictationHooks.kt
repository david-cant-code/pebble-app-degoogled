package coredevices.util.transcription

import io.rebble.libpebblecommon.voice.PEBBLE_FW_TRANSCRIPTION_TIMEOUT
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Debug-only dictation test hooks (see the `debug*` fields of
 * `STTConfig`). Each hook below repeats the debug-build check instead of
 * trusting the settings UI, so a stored flag can never act in a release
 * install that inherited a debug build's settings.
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

/** Whether the capture dump writes for a dictation: the flag and a debug build, like every other hook. */
fun debugCaptureDumpApplies(captureDump: Boolean, debugBuild: Boolean): Boolean = captureDump && debugBuild

/**
 * Whether the capture directory must be emptied on a config emission:
 * whenever the hook is not on and was not already known to be off, so
 * the captures go when the toggle is turned off, and once at start in a
 * build that cannot honour the hook (a debug install's captures survive
 * into a release install of the same package). [wasOn] is null on the
 * first emission.
 */
fun captureDumpShouldClear(wasOn: Boolean?, on: Boolean): Boolean = !on && wasOn != false

/**
 * Archives the engine's input for one dictation ([pcm16] at [sampleRate])
 * when the capture dump hook is on in a debug build; a no-op otherwise.
 * The write is fenced inside the dumper and cannot fail the dictation.
 */
fun debugArchiveDictationAudio(captureDump: Boolean, debugBuild: Boolean, pcm16: ByteArray, sampleRate: Int) {
    if (debugCaptureDumpApplies(captureDump, debugBuild)) DictationCaptureDump.write(pcm16, sampleRate)
}

/**
 * Archives the watch's codec frames for one dictation when the capture
 * dump hook is on in a debug build; a no-op otherwise. Written under the
 * same prefix as the decoded-audio dump but with its own stamp, taken
 * before the decode is routed: pair a `.wav` with the nearest earlier
 * `.spx`. A dictation routed to a server leaves no `.wav`.
 */
fun debugArchiveDictationFrames(captureDump: Boolean, debugBuild: Boolean, frames: List<ByteArray>) {
    if (debugCaptureDumpApplies(captureDump, debugBuild) && frames.isNotEmpty()) DictationCaptureDump.writeFrames(frames)
}
