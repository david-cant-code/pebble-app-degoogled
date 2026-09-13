package coredevices.whisper

import java.io.File

/**
 * Facts about the calling process from procfs, read by the engine
 * process for its runtime report and by the app process for its own
 * diagnostics snapshot, so both sides of a `proc=` line read the same
 * files the same way. Every read is fenced: a file that is unreadable
 * or oddly formatted on some kernel answers null, never a failure of
 * the call that asked.
 */

/** The `Cpus_allowed_list` field of `/proc/self/status`, unparsed and trimmed, or null. */
fun readCpusAllowedList(): String? = runCatching {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()?.ifEmpty { null }
    }
}.getOrNull()

/** This process's cgroup cpuset path, or null. */
fun readCpuset(): String? = runCatching {
    File("/proc/self/cpuset").readText().trim().ifEmpty { null }
}.getOrNull()

/** This process's `oom_score_adj`, or null. */
fun readOomScoreAdj(): Int? = runCatching {
    File("/proc/self/oom_score_adj").readText().trim().toInt()
}.getOrNull()
