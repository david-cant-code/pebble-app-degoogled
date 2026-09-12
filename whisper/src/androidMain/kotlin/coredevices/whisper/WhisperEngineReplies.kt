package coredevices.whisper

import coredevices.whisper.WhisperEngineProtocol.STATUS_BUSY
import coredevices.whisper.WhisperEngineProtocol.STATUS_ENGINE_ERROR
import coredevices.whisper.WhisperEngineProtocol.STATUS_STALE_HANDLE

/**
 * The pure decisions [WhisperEngineClient] makes over what it sends and
 * over values it has already read from an engine reply, kept apart from
 * the Parcel code so the host suite can feed them hostile values: the
 * engine process parses untrusted model bytes, so everything it sends
 * back is untrusted, and these are the checks that stand between its
 * reply and the app. The engine side's counterparts are in
 * WhisperEngineRequests.kt.
 */

/**
 * The exception a failure status maps to, which is what callers act on:
 * a stale handle means the engine process the handle came from is gone,
 * so it is reported as an unavailable engine, as is a status this
 * process does not know; a busy handle is a serialization failure in
 * this process; an engine error keeps the engine's text.
 */
internal fun exceptionForStatus(operation: String, status: Int, message: String): RuntimeException = when (status) {
    STATUS_STALE_HANDLE -> WhisperEngineUnavailableException("whisper $operation failed: $message")
    STATUS_BUSY -> IllegalStateException("whisper $operation failed: $message")
    STATUS_ENGINE_ERROR -> RuntimeException("whisper $operation failed: $message")
    else -> WhisperEngineUnavailableException("whisper $operation failed: unknown status $status")
}

/**
 * Refuses a reply payload over its bound as an unavailable engine: a
 * process that answers past the protocol's bounds is not one this
 * process reads from, whatever else the reply says.
 */
internal fun checkReplyBound(kind: String, size: Int, max: Int) {
    if (size > max) {
        throw WhisperEngineUnavailableException("engine reply $kind of $size exceeds the $max bound")
    }
}

/**
 * The byte size of the shared-memory region that carries [samples]
 * floats: a region must have a size, so an empty clip still crosses in
 * the smallest one, and a count whose bytes overflow the region size is
 * refused before anything is allocated.
 */
internal fun audioRegionBytes(samples: Int): Int {
    val byteCount = samples.toLong() * Float.SIZE_BYTES
    require(samples >= 0 && byteCount <= Int.MAX_VALUE) { "audio of $samples samples exceeds the region limit" }
    return byteCount.toInt().coerceAtLeast(Float.SIZE_BYTES)
}

/**
 * The sample count to record for a transcribe call: the engine's echo
 * [reported] only when it is the count this process sent ([sent]) or the
 * -1 that means the engine never reached its input; any other value is
 * a reply this process did not ask for and reads as unknown. The count
 * feeds the per-model speed record, so a forged one would steer the
 * model recommendation.
 */
internal fun boundedSampleEcho(reported: Int, sent: Int): Int =
    if (reported == -1 || reported == sent) reported else -1

/**
 * Engine-supplied text with every ISO control character replaced by a
 * question mark. The text lands in exception messages and in the
 * diagnostics lines of the log a user attaches to a report, and those
 * lines are one per line by contract, so an embedded line break from
 * the engine process must not be able to forge or split one.
 */
internal fun sanitizeEngineText(text: String): String {
    if (text.none { it.isISOControl() }) return text
    return buildString(text.length) {
        for (ch in text) append(if (ch.isISOControl()) '?' else ch)
    }
}
