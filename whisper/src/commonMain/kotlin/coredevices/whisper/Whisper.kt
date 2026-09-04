package coredevices.whisper

/**
 * The complete engine surface for on-device speech recognition. Nine
 * functions: this is the fork's replacement for a much larger proprietary
 * binding surface, and everything the app needs from the engine fits
 * here (six for the speech model, two for the voice activity detector,
 * one model-free speed probe). Anything not expressible through these
 * functions belongs in the Kotlin service layer, not in new native entry
 * points.
 *
 * Threading contract: callers serialize [whisperInit], [whisperTranscribe]
 * and [whisperFree] per handle, and [whisperVadInit], [whisperVadFree] and
 * any [whisperTranscribe] that passes the detector per detector handle
 * (the transcription service holds one mutex across every native call).
 * [whisperCancel] is the one function safe to call concurrently; it
 * targets a specific in-flight call by its [callId], so a cancellation
 * can never revoke a different call's pending abort (the case that arises
 * when an abandoned wedged call still runs alongside a fresh one).
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
 * Where one engine call runs. The engine's worker threads inherit the
 * calling thread's scheduling, so the native side applies this to the
 * calling thread for the duration of the call and restores it after.
 *
 * @property cpuMask bit i set = CPU i allowed; 0 leaves the affinity mask
 *   untouched. Bits outside the process's cpuset are ignored by the
 *   kernel, and a mask that leaves nothing is refused and logged.
 * @property nice nice value for the call (negative is higher priority);
 *   0 leaves the priority untouched. A refused change is logged and
 *   ignored.
 */
data class EnginePlacement(val cpuMask: Long = 0L, val nice: Int = 0) {
    companion object {
        /** Default scheduling: whatever the calling thread already has. */
        val DEFAULT = EnginePlacement()
    }
}

/**
 * Loads a ggml Silero voice activity detector and returns its handle, or
 * throws with the engine's error text. The caller owns the handle and
 * releases it with [whisperVadFree]. Independent of any speech model
 * handle: one detector serves every model.
 */
expect fun whisperVadInit(modelPath: String): Long

/** Releases a detector handle. Safe to call with 0. */
expect fun whisperVadFree(handle: Long)

/**
 * What one [whisperTranscribe] call reports back about itself when the
 * caller passes an instance.
 *
 * @property decodedSamples the samples the engine was given after the
 *   detector's cut (the input size without a detector, 0 for a clip the
 *   detector found no speech in); -1 until the call reaches that point.
 *   Decode cost follows this count, not the input length, which is what
 *   makes a per-second-of-speech timing possible on a padded recording.
 */
class TranscribeStats {
    var decodedSamples: Int = -1
}

/**
 * Transcribes 16 kHz mono float PCM and returns plain text ("" means no
 * speech found). [language] is an ISO 639-1 code; null lets the engine
 * detect the language. [callId] identifies this call for [whisperCancel];
 * it must be unique among all calls that can be in flight at once (the
 * service uses a monotonic counter). [placement] scopes the calling
 * thread's affinity and priority to this call. With a non-zero
 * [vadHandle] the audio is cut to its speech segments before the decode
 * and a clip with no speech returns "" without an encoder pass; a
 * detector failure decodes the untrimmed audio. [stats], when given, is
 * filled in by the call. Throws with the engine's error text on failure,
 * including cancellation via [whisperCancel].
 */
expect fun whisperTranscribe(
    handle: Long,
    pcm: FloatArray,
    threads: Int,
    language: String?,
    callId: Long,
    placement: EnginePlacement = EnginePlacement.DEFAULT,
    vadHandle: Long = 0L,
    stats: TranscribeStats? = null,
): String

/**
 * Requests cancellation of the in-flight [whisperTranscribe] call with the
 * matching [callId]. Cancellation is per call, not process-wide, so it
 * only aborts that call; the engine polls it between inference passes and
 * the call clears its own request on return.
 */
expect fun whisperCancel(callId: Long)

/** Releases an engine handle. Safe to call with 0. */
expect fun whisperFree(handle: Long)

/**
 * Times one synthetic encoder block (the base model's shape over 512
 * frames, random weights, no model file) on [threads] engine threads and
 * returns the median nanoseconds per evaluation: a measure of how fast
 * this phone runs the engine, which the service layer calibrates into
 * per-model dictation estimates before any model is downloaded. Runs for
 * about a second of CPU. [placement] scopes the calling thread as for
 * [whisperTranscribe]. Throws with the engine's error text on failure.
 */
expect fun whisperBenchmark(threads: Int, placement: EnginePlacement = EnginePlacement.DEFAULT): Long

/** The engine's last recorded failure reason, for error propagation. */
expect fun whisperGetLastError(): String

/**
 * Decodes little-endian signed 16-bit PCM (the watch pipeline's wire
 * format) into samples. Split from [shortsToFloats] so a resampler can
 * operate on the integer samples between the two steps; both live in
 * commonMain as plain Kotlin so the conversions are host-testable without
 * any native library.
 */
fun pcm16ToShorts(bytes: ByteArray): ShortArray {
    require(bytes.size % 2 == 0) {
        "PCM16 input must be an even number of bytes, got ${bytes.size}"
    }
    val out = ShortArray(bytes.size / 2)
    for (i in out.indices) {
        val lo = bytes[2 * i].toInt() and 0xFF
        val hi = bytes[2 * i + 1].toInt() and 0xFF
        // Reassemble the sample then reinterpret the low 16 bits as signed.
        out[i] = ((hi shl 8) or lo).toShort()
    }
    return out
}

/**
 * Integer samples to the [-1, 1) floats the engine consumes. 32768 is the
 * divisor (not 32767) so -32768 maps exactly to -1.0 and no sample can
 * exceed the unit range.
 */
fun shortsToFloats(samples: ShortArray): FloatArray =
    FloatArray(samples.size) { samples[it] / 32768f }

/** [pcm16ToShorts] and [shortsToFloats] composed, for the no-resample path. */
fun pcm16ToFloats(bytes: ByteArray): FloatArray = shortsToFloats(pcm16ToShorts(bytes))
