package com.anopticlabs.gravel.llm

import java.io.File

/*
 * Facts about the calling process from procfs, for the engine's runtime
 * report; an unreadable or oddly formatted file answers null.
 */

/** The `Cpus_allowed_list` field of `/proc/self/status`, or null. */
internal fun readCpusAllowedList(): String? = runCatching {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()?.ifEmpty { null }
    }
}.getOrNull()

internal fun readCpuset(): String? = runCatching {
    File("/proc/self/cpuset").readText().trim().ifEmpty { null }
}.getOrNull()

internal fun readOomScoreAdj(): Int? = runCatching {
    File("/proc/self/oom_score_adj").readText().trim().toInt()
}.getOrNull()
