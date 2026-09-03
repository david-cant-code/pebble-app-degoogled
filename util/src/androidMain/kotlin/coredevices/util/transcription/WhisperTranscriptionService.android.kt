package coredevices.util.transcription

actual suspend fun getFreeMemoryMB(): Long {
    // get available memory in MB
    val runtime = Runtime.getRuntime()
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    return maxMemory - usedMemory
}

actual val PLATFORM_MIN_TRANSCRIPTION_MEMORY_MB: Long = 20

// Policy and bound live in engineThreadCount; this actual only supplies the
// two platform inputs, read at call time because the mask changes with the
// process state (foreground service, background) during a session.
actual fun transcriptionThreadCount(): Int = engineThreadCount(
    allowedCpus = engineRuntimeSnapshot().allowedCpus,
    onlineCpus = Runtime.getRuntime().availableProcessors(),
)