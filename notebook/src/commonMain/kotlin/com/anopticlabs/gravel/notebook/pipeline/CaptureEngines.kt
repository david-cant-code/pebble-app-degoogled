package com.anopticlabs.gravel.notebook.pipeline

/** The speech engine's long-form cap; a longer capture is flagged rather than cut. */
const val MAX_CAPTURE_MILLIS = 5 * 60 * 1000

const val CAPTURE_SAMPLE_RATE = 16_000

/** A queued recording's file as 16 kHz mono little-endian PCM16; failures as [CaptureProcessingException]. */
fun interface CaptureAudioDecoder {
    fun decode(audio: ByteArray): ByteArray
}

/**
 * Where captures are transcribed under the speech setting: the phone, the server, the server once the phone has tried,
 * or the phone for what the server cannot take.
 */
enum class SpeechRoute { PHONE, SERVER, PHONE_THEN_SERVER, SERVER_THEN_PHONE }

/**
 * What the speech step knows of a capture besides its audio. [phoneFits] frees what it can and says whether a
 * transcription on the phone fits in memory now; [markPhoneTried] records on the entry that the phone has tried it,
 * which [phoneTried] then reads, so a later attempt under [SpeechRoute.PHONE_THEN_SERVER] goes straight to the server.
 * [markServerTry] records a failed try to reach the server at the given time, which [serverTries] and
 * [serverTriedAtMillis] then read, and says whether the record was written.
 */
class SpeechCapture(
    val id: String,
    val phoneTried: Boolean,
    val phoneFits: suspend () -> Boolean,
    val markPhoneTried: suspend () -> Unit,
    val long: Boolean = false,
    val fromWatch: Boolean = false,
    val serverTries: Int = 0,
    val serverTriedAtMillis: Long? = null,
    val markServerTry: suspend (atMillis: Long) -> Boolean = { false },
)

/** Each call fails as [CaptureProcessingException]; [checkReady] with [CaptureFailureKind.HOLD] when nothing can run now. */
interface CaptureSpeech {
    /** Checked before a drain pass's first capture. */
    suspend fun checkReady()

    fun route(): SpeechRoute

    /** The transcript of [pcm16], at most [MAX_CAPTURE_MILLIS] of [CAPTURE_SAMPLE_RATE] audio. */
    suspend fun transcribe(pcm16: ByteArray, capture: SpeechCapture): String

    /** What the screen should say while captures go on despite it, read after each drain pass. */
    fun notice(): CaptureNotice? = null
}

/** Each call fails as [CaptureProcessingException]; [checkReady] with [CaptureFailureKind.HOLD] when nothing can run now. */
interface CaptureText {
    suspend fun checkReady()

    /** Loads the model, so the generation after it starts at once. */
    suspend fun prepare()

    suspend fun generate(system: String, user: String, maxTokens: Int): String

    /** Frees the loaded model, if any, once no call is using it; the next call loads it again. */
    suspend fun release()
}

/** Whether a transcription started now would leave the phone short of memory. */
fun interface CaptureMemory {
    fun shortForSpeech(): Boolean
}

/**
 * True when using [needBytes] more would take [availableBytes] under [thresholdBytes], the
 * system's low-memory threshold (android16-release, `ProcessList.getMemoryInfo`: `availMem` is
 * the kernel's MemAvailable, `threshold` the memory level of the home app).
 */
fun memoryShortFor(needBytes: Long, availableBytes: Long, thresholdBytes: Long): Boolean =
    availableBytes - needBytes < thresholdBytes
