package com.anopticlabs.gravel.whisper

/** One stretch of a long-form transcript; times are milliseconds from the recording's start. */
data class WhisperSegment(val startMillis: Long, val endMillis: Long, val text: String)

/** A long-form call's segments, or none and [complete] false when the call was stopped before it finished. */
data class WhisperLongFormResult(val segments: List<WhisperSegment>, val complete: Boolean)

/** The audio for one long-form call could not be allocated. */
class WhisperMemoryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Bounds and wire layout of a long-form call, shared with the shim. The
 * reply is a flag byte (0 finished, 1 stopped), then per segment its start
 * and end in centiseconds as little-endian int64s, its text's byte length
 * as a little-endian int32, and the UTF-8 text.
 */
object WhisperLongForm {
    const val SAMPLE_RATE = 16_000
    const val MAX_RECORDING_MILLIS = 5 * 60 * 1000
    const val MAX_CALL_SAMPLES = MAX_RECORDING_MILLIS / 1000 * SAMPLE_RATE

    /** The shim refuses a transcript that would pass this. */
    const val MAX_REPLY_BYTES = 256 * 1024

    internal const val REPLY_HEADER_BYTES = 1
    internal const val SEGMENT_HEADER_BYTES = 8 + 8 + 4
}

/**
 * Reads a packed reply for a recording of [audioMillis], refusing anything
 * outside the layout; times are clamped into the recording (a model can
 * place one in the padding past the audio's end), and a stopped call's
 * segments are dropped.
 */
internal fun parseLongFormReply(packed: ByteArray, audioMillis: Long): WhisperLongFormResult {
    require(audioMillis in 0..WhisperLongForm.MAX_RECORDING_MILLIS) { "a recording of $audioMillis ms" }
    require(packed.size in WhisperLongForm.REPLY_HEADER_BYTES..WhisperLongForm.MAX_REPLY_BYTES) {
        "a packed result of ${packed.size} bytes"
    }
    val complete = when (packed[0].toInt()) {
        0 -> true
        1 -> false
        else -> throw IllegalArgumentException("unknown result flag ${packed[0]}")
    }
    val clamp = { centis: Long ->
        require(centis >= 0) { "a negative time" }
        (minOf(centis, audioMillis / 10 + 1) * 10).coerceAtMost(audioMillis)
    }
    val segments = ArrayList<WhisperSegment>()
    var at = WhisperLongForm.REPLY_HEADER_BYTES
    while (at < packed.size) {
        require(packed.size - at >= WhisperLongForm.SEGMENT_HEADER_BYTES) { "a segment header cut short at byte $at" }
        val start = clamp(packed.littleEndian(at, 8))
        val end = maxOf(start, clamp(packed.littleEndian(at + 8, 8)))
        val length = packed.littleEndian(at + 16, 4)
        at += WhisperLongForm.SEGMENT_HEADER_BYTES
        require(length <= packed.size - at) { "a segment text of $length bytes past the end" }
        segments += WhisperSegment(start, end, packed.decodeToString(at, at + length.toInt()))
        at += length.toInt()
    }
    return WhisperLongFormResult(if (complete) segments else emptyList(), complete)
}

// An int64 with its top bit set comes back negative, which the caller refuses.
private fun ByteArray.littleEndian(offset: Int, bytes: Int): Long {
    var value = 0L
    for (i in bytes - 1 downTo 0) value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
    return value
}
