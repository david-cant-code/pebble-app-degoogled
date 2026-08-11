package coredevices.util.transcription

actual suspend fun getFreeMemoryMB(): Long {
    // get available memory in MB
    val runtime = Runtime.getRuntime()
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    return maxMemory - usedMemory
}

actual val PLATFORM_MIN_TRANSCRIPTION_MEMORY_MB: Long = 20

// Bounded at 6: whisper's threading gains flatten past a few big cores, and
// grabbing every core steals from the audio and UI threads during dictation.
actual fun transcriptionThreadCount(): Int =
    Runtime.getRuntime().availableProcessors().coerceIn(1, 6)