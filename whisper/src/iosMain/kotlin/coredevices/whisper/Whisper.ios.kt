package coredevices.whisper

/**
 * iOS stubs. This fork is Android-only and its iOS sources are
 * unmaintained; these actuals exist so the commonMain consumers keep
 * compiling for the iOS targets, and they answer honestly rather than
 * pretending an engine exists.
 */

actual fun isWhisperSupported(): Boolean = false

actual fun whisperInit(modelPath: String): Long =
    throw UnsupportedOperationException("The whisper engine is Android-only in this fork")

actual fun whisperTranscribe(
    handle: Long,
    pcm: FloatArray,
    threads: Int,
    language: String?,
    callId: Long,
    placement: EnginePlacement,
    stats: TranscribeStats?,
): String =
    throw UnsupportedOperationException("The whisper engine is Android-only in this fork")

// No-op rather than a throw: cancellation runs from generic cleanup paths
// that must stay safe even where no engine ever initializes.
actual fun whisperCancel(callId: Long) = Unit

actual fun whisperFree(handle: Long) = Unit

actual fun whisperGetLastError(): String = "The whisper engine is Android-only in this fork"

actual fun whisperBenchmark(threads: Int, placement: EnginePlacement): Long =
    throw UnsupportedOperationException("The whisper engine is Android-only in this fork")
