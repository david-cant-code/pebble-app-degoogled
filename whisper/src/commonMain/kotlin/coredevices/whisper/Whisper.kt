package coredevices.whisper

/**
 * The complete engine surface for on-device speech recognition. Seven
 * functions on purpose: this is the fork's replacement for a much larger
 * proprietary binding surface, and everything the app needs from the
 * engine fits here. Anything not expressible through these functions
 * belongs in the Kotlin service layer, not in new native entry points.
 *
 * Threading contract: callers serialize [whisperInit], [whisperTranscribe]
 * and [whisperFree] per handle (the transcription service holds a mutex
 * across every native call). [whisperSetCancel] is the one function safe
 * to call concurrently; it flips a process-wide flag the engine polls, and
 * a single flag suffices exactly because transcriptions never overlap.
 */

/**
 * True when the engine libraries are present and the CPU meets the
 * compiled feature floor (armv8.2 dotprod + fp16). Checked by a separate
 * baseline-architecture probe library, so this is safe to call on any
 * device; no engine code is mapped until it has returned true.
 */
expect fun isWhisperSupported(): Boolean

/**
 * Loads a ggml model file and returns an engine handle. Throws with the
 * engine's error text on failure. The caller owns the handle and must
 * release it with [whisperFree].
 */
expect fun whisperInit(modelPath: String): Long

/**
 * Transcribes 16 kHz mono float PCM and returns plain text ("" means no
 * speech found). [language] is an ISO 639-1 code; null lets the engine
 * detect the language. Throws with the engine's error text on failure,
 * including cancellation via [whisperSetCancel].
 */
expect fun whisperTranscribe(handle: Long, pcm: FloatArray, threads: Int, language: String?): String

/** Requests (true) or clears (false) cancellation of the in-flight transcription. */
expect fun whisperSetCancel(cancel: Boolean)

/** Releases an engine handle. Safe to call with 0. */
expect fun whisperFree(handle: Long)

/** The engine's last recorded failure reason, for error propagation. */
expect fun whisperGetLastError(): String

/**
 * Converts little-endian signed 16-bit PCM (the watch pipeline's wire
 * format) to the [-1, 1) float samples the engine consumes. Lives in
 * commonMain as plain Kotlin so the conversion is host-testable without
 * any native library.
 */
fun pcm16ToFloats(bytes: ByteArray): FloatArray {
    require(bytes.size % 2 == 0) {
        "PCM16 input must be an even number of bytes, got ${bytes.size}"
    }
    val out = FloatArray(bytes.size / 2)
    for (i in out.indices) {
        val lo = bytes[2 * i].toInt() and 0xFF
        val hi = bytes[2 * i + 1].toInt() and 0xFF
        // Reassemble the sample then reinterpret the low 16 bits as
        // signed; 32768 is the divisor (not 32767) so -32768 maps exactly
        // to -1.0 and no sample can exceed the unit range.
        out[i] = ((hi shl 8) or lo).toShort() / 32768f
    }
    return out
}
