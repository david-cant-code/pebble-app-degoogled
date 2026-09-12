package coredevices.util.transcription

/**
 * Scheduling facts about one process at the moment an engine call starts.
 * Watch dictation has a hard 15 second budget from the firmware, and a
 * decode that fits in the foreground can miss it once the app is no longer
 * the top app, so every dictation records what the OS was giving it. Each
 * fact is null when the platform could not answer, never a guess.
 *
 * @property allowedCpus number of CPUs in the process affinity mask; a
 *   restricted cpuset shrinks this below the number of CPUs the phone
 *   has.
 * @property cpuset the cgroup cpuset path the process sits in (for example
 *   `/top-app`, `/foreground`, `/background`).
 * @property importance the platform's process importance value at the time
 *   of the call (Android `RunningAppProcessInfo.importance`); the engine
 *   process cannot read its own, so it is null there.
 * @property oomScoreAdj the kernel-facing rating the platform sets from
 *   the same state (`/proc/self/oom_score_adj`, lower is safer: 0 for the
 *   foreground app, 900 and above for cached apps), which the low-memory
 *   killer reads; readable from the engine process, so it is the rating
 *   that answers for a decode there.
 * @property process which process the facts describe: [PROCESS_ENGINE]
 *   for the engine process the decode runs in, [PROCESS_HOST] for the app
 *   process, which stands in while no engine process is bound.
 */
data class EngineRuntimeSnapshot(
    val allowedCpus: Int?,
    val cpuset: String?,
    val importance: Int?,
    val oomScoreAdj: Int?,
    val process: String,
) {
    companion object {
        const val PROCESS_ENGINE = "engine"
        const val PROCESS_HOST = "host"
    }
}

/** Reads the [EngineRuntimeSnapshot] of this process; must never throw. */
expect fun engineRuntimeSnapshot(): EngineRuntimeSnapshot

/**
 * Counts the CPUs named by a Linux cpulist such as `0-3,6` (the format of
 * `Cpus_allowed_list` in `/proc/self/status`). Returns null for anything
 * that is not a well-formed list, so a kernel format surprise reads as
 * "unknown" in the diagnostics rather than as a wrong number.
 */
internal fun parseCpuListCount(list: String): Int? = parseCpuList(list)?.size

/**
 * The CPU ids named by a Linux cpulist such as `0-3,6`, in ascending
 * order, or null for anything that is not a well-formed list. This is
 * the id set an engine placement can choose from.
 */
internal fun parseCpuList(list: String): List<Int>? {
    val trimmed = list.trim()
    if (trimmed.isEmpty()) return null
    val ids = ArrayList<Int>()
    for (part in trimmed.split(',')) {
        val range = part.trim()
        if (range.isEmpty()) return null
        val dash = range.indexOf('-')
        if (dash < 0) {
            ids += range.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        } else {
            val lo = range.substring(0, dash).toIntOrNull() ?: return null
            val hi = range.substring(dash + 1).toIntOrNull() ?: return null
            if (lo < 0 || hi < lo) return null
            for (id in lo..hi) ids += id
        }
    }
    return ids.distinct().sorted()
}

/**
 * One line per engine call, in a fixed `key=value` layout so a log zip from
 * a report can be read (or grepped) without the source at hand. Nulls print
 * as `?`. Kept pure so the layout is pinned by a host test.
 *
 * `proc` names the process the four placement facts describe (see
 * [EngineRuntimeSnapshot.process]). `initWaitMs` is how long the call
 * blocked for the model to come up before the decode: zero once the
 * model is resident, and otherwise the part of the cold path in
 * [formatColdPathDiagnostics] that did not overlap the recording, which
 * is what the dictation pays on top of it.
 */
internal fun formatEngineDiagnostics(
    model: String?,
    threads: Int,
    snapshot: EngineRuntimeSnapshot,
    audioSeconds: Double,
    initWaitMillis: Long,
    decodeMillis: Long,
    outcome: String,
): String = buildString {
    append("dictation engine: model=").append(model ?: "?")
    append(" threads=").append(threads)
    appendPlacement(snapshot)
    append(" audioSec=").append(formatSeconds(audioSeconds))
    append(" initWaitMs=").append(initWaitMillis)
    append(" decodeMs=").append(decodeMillis)
    append(" outcome=").append(outcome)
}

/**
 * One line per cold model load, the once-per-process work a dictation can
 * end up waiting on: the model path resolve (the provider re-hashes the
 * installed file before its first use), the engine init (the first call
 * binds the engine process, then loads the model there) and the warm-up
 * pass. `bindMs` is the part of `engineInitMs` spent bringing the engine
 * process up, zero when the load found it already bound, so `engineInitMs`
 * less `bindMs` is the model load itself. The snapshot is read as the
 * load starts, because the hash is CPU-bound and the process can sit in
 * a different cpuset then than at the decode. A term the load never
 * reached prints as `?`. Same fixed-layout contract as
 * [formatEngineDiagnostics], pinned by the same host test.
 */
internal fun formatColdPathDiagnostics(
    model: String?,
    snapshot: EngineRuntimeSnapshot,
    modelPathMillis: Long?,
    bindMillis: Long?,
    engineInitMillis: Long?,
    warmUpMillis: Long?,
    outcome: String,
): String = buildString {
    append("dictation coldpath: model=").append(model ?: "?")
    appendPlacement(snapshot)
    append(" modelPathMs=").append(modelPathMillis ?: "?")
    append(" bindMs=").append(bindMillis ?: "?")
    append(" engineInitMs=").append(engineInitMillis ?: "?")
    append(" warmUpMs=").append(warmUpMillis ?: "?")
    append(" outcome=").append(outcome)
}

// The placement facts share one layout on both lines, `proc` first because
// it says which process the four after it describe.
private fun StringBuilder.appendPlacement(snapshot: EngineRuntimeSnapshot) {
    append(" proc=").append(snapshot.process)
    append(" allowedCpus=").append(snapshot.allowedCpus ?: "?")
    append(" cpuset=").append(snapshot.cpuset ?: "?")
    append(" importance=").append(snapshot.importance ?: "?")
    append(" oomAdj=").append(snapshot.oomScoreAdj ?: "?")
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
