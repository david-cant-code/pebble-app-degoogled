package coredevices.whisper

import coredevices.whisper.WhisperEngineProtocol.STATUS_BUSY
import coredevices.whisper.WhisperEngineProtocol.STATUS_ENGINE_ERROR
import coredevices.whisper.WhisperEngineProtocol.STATUS_STALE_HANDLE
import coredevices.whisper.WhisperEngineProtocol.TRANSACTION_BENCHMARK
import coredevices.whisper.WhisperEngineProtocol.TRANSACTION_INIT
import coredevices.whisper.WhisperEngineProtocol.TRANSACTION_TRANSCRIBE
import coredevices.whisper.WhisperEngineProtocol.UNKNOWN_INT
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
 * How long the client waits for the engine process to answer a
 * transaction before it ends that process. An engine that answers never,
 * because it is wedged or hostile, is the case each bound exists for, so
 * a bound only has to sit well above the slowest legitimate call of its
 * kind: a model load reads up to a gigabyte from flash, a decode of the
 * longest watch recording on the slowest catalog model takes tens of
 * seconds on a slow phone, the probe runs for about a second of CPU, and
 * the rest are procfs reads or a flag. A code this table does not know
 * gets the shortest.
 */
internal fun transactionDeadline(code: Int): Duration = when (code) {
    TRANSACTION_INIT -> 2.minutes
    TRANSACTION_TRANSCRIBE -> 3.minutes
    TRANSACTION_BENCHMARK -> 1.minutes
    else -> 15.seconds
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
 * The values `oom_score_adj` can hold (AOSP bionic `android16-release`,
 * `libc/kernel/uapi/linux/oom.h`, `OOM_SCORE_ADJ_MIN` and
 * `OOM_SCORE_ADJ_MAX`); [UNKNOWN_INT] lies outside it.
 */
internal val OOM_SCORE_ADJ_RANGE = -1000..1000

/** Longest cgroup path token accepted as a cpuset; past any path the platform's profiles join. */
internal const val MAX_CPUSET_PATH_CHARS = 64

/**
 * The engine's runtime report as this process takes it, built from the
 * values read off the reply: a field whose shape the app can predict is
 * checked against it and reads as unknown otherwise, so the report can
 * describe the engine process's placement and forge nothing else. The
 * cpulist is digits, commas and dashes (the parser that counts it checks
 * the rest); the cpuset is one path token, which matters because the
 * diagnostics lines print it as one `key=value` field of a fixed layout,
 * where a space or an `=` in it would forge a field; `oom_score_adj`
 * lies in [OOM_SCORE_ADJ_RANGE]; and a descriptor count is not negative.
 * The importance slot of the reply is not an input here: the platform
 * refuses that query to an isolated process ([WhisperEngineService]
 * states the source), so no value in it is the engine's own.
 */
internal fun boundedEngineRuntime(
    cpusAllowedList: String?,
    cpuset: String?,
    oomScoreAdj: Int,
    pid: Int,
    uid: Int,
    openFds: Int,
): WhisperEngineRuntime = WhisperEngineRuntime(
    cpusAllowedList = cpusAllowedList?.takeIf(::isCpuList),
    cpuset = cpuset?.takeIf(::isCpusetPath),
    importance = null,
    oomScoreAdj = oomScoreAdj.takeIf { it in OOM_SCORE_ADJ_RANGE },
    pid = pid,
    uid = uid,
    openFds = openFds.takeIf { it >= 0 },
)

private fun isCpuList(text: String): Boolean =
    text.isNotEmpty() && text.all { it in '0'..'9' || it == ',' || it == '-' }

// The names the platform's task profiles join under the cpuset
// controller are letters, digits, `_` and `-`, nested with `/` (AOSP
// `android16-release`,
// `system/core/libprocessgroup/profiles/task_profiles.json`, the
// `JoinCgroup` actions on the `cpuset` controller); `/proc/self/cpuset`
// prints the path from the root, so it starts with `/`. A dot is allowed
// for a vendor's name. ASCII only: a letter from another script is not
// a control character, but it is not a path here either.
private fun isCpusetPath(text: String): Boolean =
    text.length in 1..MAX_CPUSET_PATH_CHARS && text[0] == '/' &&
        text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '/' || it == '_' || it == '-' || it == '.' }

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
