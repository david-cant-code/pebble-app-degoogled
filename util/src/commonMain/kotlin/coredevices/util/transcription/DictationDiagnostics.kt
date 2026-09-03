package coredevices.util.transcription

/**
 * Scheduling facts about this process at the moment an engine call starts.
 * Watch dictation has a hard 15 second budget from the firmware, and a
 * decode that fits in the foreground can miss it once the app is no longer
 * the top app, so every dictation records what the OS was giving it. Each
 * field is null when the platform could not answer, never a guess.
 *
 * @property allowedCpus number of CPUs in the process affinity mask; a
 *   restricted cpuset shrinks this below the online-CPU count that
 *   `availableProcessors` reports.
 * @property cpuset the cgroup cpuset path the process sits in (for example
 *   `/top-app`, `/foreground`, `/background`).
 * @property importance the platform's process importance value at the time
 *   of the call (Android `RunningAppProcessInfo.importance`).
 */
data class EngineRuntimeSnapshot(
    val allowedCpus: Int?,
    val cpuset: String?,
    val importance: Int?,
)

/** Reads the current [EngineRuntimeSnapshot]; must never throw. */
expect fun engineRuntimeSnapshot(): EngineRuntimeSnapshot

/**
 * Counts the CPUs named by a Linux cpulist such as `0-3,6` (the format of
 * `Cpus_allowed_list` in `/proc/self/status`). Returns null for anything
 * that is not a well-formed list, so a kernel format surprise reads as
 * "unknown" in the diagnostics rather than as a wrong number.
 */
internal fun parseCpuListCount(list: String): Int? {
    val trimmed = list.trim()
    if (trimmed.isEmpty()) return null
    var count = 0
    for (part in trimmed.split(',')) {
        val range = part.trim()
        if (range.isEmpty()) return null
        val dash = range.indexOf('-')
        if (dash < 0) {
            range.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            count += 1
        } else {
            val lo = range.substring(0, dash).toIntOrNull() ?: return null
            val hi = range.substring(dash + 1).toIntOrNull() ?: return null
            if (lo < 0 || hi < lo) return null
            count += hi - lo + 1
        }
    }
    return count
}

/**
 * One line per engine call, in a fixed `key=value` layout so a log zip from
 * a report can be read (or grepped) without the source at hand. Nulls print
 * as `?`. Kept pure so the layout is pinned by a host test.
 */
internal fun formatEngineDiagnostics(
    model: String?,
    threads: Int,
    snapshot: EngineRuntimeSnapshot,
    audioSeconds: Double,
    decodeMillis: Long,
    outcome: String,
): String = buildString {
    append("dictation engine: model=").append(model ?: "?")
    append(" threads=").append(threads)
    append(" allowedCpus=").append(snapshot.allowedCpus ?: "?")
    append(" cpuset=").append(snapshot.cpuset ?: "?")
    append(" importance=").append(snapshot.importance ?: "?")
    append(" audioSec=").append(formatSeconds(audioSeconds))
    append(" decodeMs=").append(decodeMillis)
    append(" outcome=").append(outcome)
}

/**
 * The session-side companion to [formatEngineDiagnostics]: what the watch
 * session saw, measured from the end of the recording, which is when the
 * firmware starts its result clock.
 */
fun formatSessionDiagnostics(
    audioSeconds: Double,
    sinceAudioEndMillis: Long,
    outcome: String,
): String = buildString {
    append("dictation session: audioSec=").append(formatSeconds(audioSeconds))
    append(" resultAfterMs=").append(sinceAudioEndMillis)
    append(" outcome=").append(outcome)
}

// Two decimals without a platform-specific formatter, so commonMain stays
// portable and the layout is identical on every target.
private fun formatSeconds(seconds: Double): String {
    val hundredths = kotlin.math.round(seconds * 100).toLong()
    val whole = hundredths / 100
    val frac = (hundredths % 100).toString().padStart(2, '0')
    return "$whole.$frac"
}
