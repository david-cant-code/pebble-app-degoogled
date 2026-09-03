package coredevices.util.transcription

actual suspend fun getFreeMemoryMB(): Long {
    // get available memory in MB
    val runtime = Runtime.getRuntime()
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    return maxMemory - usedMemory
}

actual val PLATFORM_MIN_TRANSCRIPTION_MEMORY_MB: Long = 20

// Policy and bound live in tieredThreadCount; this actual only supplies the
// platform inputs. The affinity mask is read at call time because it
// changes with the process state (foreground service, background) during
// a session; the per-core frequencies are static. Without a readable mask
// the count falls back to the online-CPU count under the same cap.
actual fun transcriptionThreadCount(): Int {
    val allowed = readAllowedCpuIds()
    return if (allowed.isNullOrEmpty()) {
        engineThreadCount(allowedCpus = null, onlineCpus = Runtime.getRuntime().availableProcessors())
    } else {
        tieredThreadCount(allowed, cpuMaxFrequenciesKHz())
    }
}