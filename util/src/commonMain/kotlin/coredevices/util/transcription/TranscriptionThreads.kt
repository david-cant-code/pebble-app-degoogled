package coredevices.util.transcription

/**
 * Upper bound on engine threads for one transcription: whisper's threading
 * gains flatten past a few big cores, and grabbing every core steals from
 * the audio and UI threads during dictation.
 */
internal const val MAX_ENGINE_THREADS = 6

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
 * The count the engine actually runs with: [measured] from
 * [engineThreadCount], unless the debug single-thread override is set
 * and this is a debug build. The build check is repeated here rather
 * than trusted from the settings UI, so a stored override can never act
 * in a release install that inherited the debug build's settings.
 */
internal fun effectiveThreadCount(singleThreadOverride: Boolean, debugBuild: Boolean, measured: Int): Int =
    if (singleThreadOverride && debugBuild) 1 else measured
