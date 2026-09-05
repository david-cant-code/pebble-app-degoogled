package io.rebble.libpebblecommon.voice

import io.rebble.libpebblecommon.packets.Result

sealed class TranscriptionResult {
    data class Success(
        val words: List<TranscriptionWord>,
    ) : TranscriptionResult()

    data object Failed : TranscriptionResult()

    data class ConnectionError(val message: String) : TranscriptionResult()
    data class Error(val message: String) : TranscriptionResult()
    data object Disabled : TranscriptionResult()
}

/**
 * The most words a dictation result may carry, and the longest word in
 * bytes: far above any dictation of the watch's recording window, and far
 * below the 16-bit length fields of the result packet, which the
 * serializer overflows without a clean error. A transcript comes from a
 * network peer on the server paths, so the bound is applied to every
 * result before it is encoded.
 */
const val MAX_TRANSCRIPT_WORDS = 256
const val MAX_TRANSCRIPT_WORD_BYTES = 128

/** This result when it fits the watch protocol's bounds, else an [TranscriptionResult.Error]. */
fun TranscriptionResult.boundedForProtocol(): TranscriptionResult {
    if (this !is TranscriptionResult.Success) return this
    val oversized = words.size > MAX_TRANSCRIPT_WORDS ||
        words.any { it.word.encodeToByteArray().size > MAX_TRANSCRIPT_WORD_BYTES }
    return if (oversized) TranscriptionResult.Error("Transcript exceeds the watch protocol's bounds") else this
}

internal fun TranscriptionResult.toProtocol(): Result {
    return when (this) {
        is TranscriptionResult.Success -> Result.Success
        TranscriptionResult.Failed, is TranscriptionResult.Error -> Result.FailRecognizerError
        is TranscriptionResult.ConnectionError -> Result.FailServiceUnavailable
        TranscriptionResult.Disabled -> Result.FailDisabled
    }
}
