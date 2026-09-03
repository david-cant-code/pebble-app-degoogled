package coredevices.util.transcription

/**
 * Upper bound on engine threads for one transcription. Measured on two
 * phone-class chips, no core set decoded faster with more than four
 * threads, and every count above the usable cores collapsed by two
 * orders of magnitude (ggml's workers synchronize on spinning barriers,
 * so a preempted worker stalls every barrier for a scheduler slice).
 */
internal const val MAX_ENGINE_THREADS = 4

/**
 * Engine thread count from what the process may actually run on.
 * [allowedCpus] is the size of the process affinity mask (null when it
 * could not be read); [onlineCpus] is the platform's online-CPU count,
 * which is what `availableProcessors` reports on ART regardless of the
 * mask. The mask wins: a process in a restricted cpuset (background, and
 * some OEM foreground groups) would otherwise spin more ggml workers than
 * it has cores, and the engine's spin barriers turn that oversubscription
 * into a multi-fold slowdown rather than a proportional one.
 */
internal fun engineThreadCount(allowedCpus: Int?, onlineCpus: Int): Int =
    (allowedCpus?.takeIf { it > 0 } ?: onlineCpus).coerceIn(1, MAX_ENGINE_THREADS)

/**
 * Thread count for a heterogeneous core set: the number of allowed cores
 * in the fastest frequency tier, plus the next tier when the fastest is
 * a single core, bounded by [MAX_ENGINE_THREADS]. The engine's barriers
 * run at the pace of the slowest participating core, so a count that
 * spills onto a slower tier decodes slower than fewer threads on the
 * fast tier alone; measured on two chips, this rule matched the fastest
 * configuration on every core set tried (all cores, the OEM foreground
 * set, the little-core background set). [maxFreqKHzByCpu] maps CPU id to
 * its maximum frequency; cores without a reading are treated as their
 * own lowest tier. Falls back to [engineThreadCount] over the allowed
 * count when the topology is unknown.
 */
internal fun tieredThreadCount(allowedCpuIds: List<Int>?, maxFreqKHzByCpu: Map<Int, Long>): Int {
    if (allowedCpuIds.isNullOrEmpty()) return engineThreadCount(allowedCpuIds?.size, onlineCpus = 1)
    val freqs = allowedCpuIds.map { maxFreqKHzByCpu[it] ?: 0L }
    if (freqs.all { it == 0L }) return engineThreadCount(allowedCpuIds.size, onlineCpus = allowedCpuIds.size)
    val tiers = freqs.groupBy { it }.toSortedMap(reverseOrder()).values.map { it.size }
    val fastest = tiers.first()
    val count = if (fastest >= 2 || tiers.size == 1) fastest else fastest + tiers[1]
    return count.coerceIn(1, MAX_ENGINE_THREADS)
}

/**
 * The count the engine actually runs with: [measured] from
 * [tieredThreadCount], unless the debug single-thread override is set
 * and this is a debug build. The build check is repeated here rather
 * than trusted from the settings UI, so a stored override can never act
 * in a release install that inherited the debug build's settings.
 */
internal fun effectiveThreadCount(singleThreadOverride: Boolean, debugBuild: Boolean, measured: Int): Int =
    if (singleThreadOverride && debugBuild) 1 else measured
