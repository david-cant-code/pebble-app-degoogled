package coredevices.whisper

/**
 * Android actuals over the two JNI libraries built by :whisper-native.
 *
 * Two holder objects on purpose: [WhisperCpuJNI] loads only the tiny
 * baseline-architecture probe library, and [WhisperJNI] loads the engine.
 * The engine library is compiled for armv8.2+dotprod+fp16 and would crash
 * at first use on older CPUs, so nothing may touch [WhisperJNI] before
 * [isWhisperSupported] has returned true; the lazy support flag plus the
 * service-layer gates enforce that ordering.
 *
 * Strings come back from native as UTF-8 byte arrays, decoded here.
 * Engine output can contain byte sequences that are not valid modified
 * UTF-8, and returning them through NewStringUTF would abort the process
 * under CheckJNI, so the shim never constructs Java strings itself.
 */

private object WhisperCpuJNI {
    init {
        System.loadLibrary("whispercpu")
    }

    @JvmStatic
    external fun nativeIsWhisperSupported(): Boolean
}

// Missing library (repackaged APK, unexpected ABI) must read as
// "unsupported", never as a crash: the probe is called from UI code.
private val whisperSupported: Boolean by lazy {
    try {
        WhisperCpuJNI.nativeIsWhisperSupported()
    } catch (_: Throwable) {
        false
    }
}

actual fun isWhisperSupported(): Boolean = whisperSupported

private object WhisperJNI {
    init {
        System.loadLibrary("whisperjni")
    }

    @JvmStatic
    external fun nativeInit(modelPath: String): Long

    @JvmStatic
    external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
        threads: Int,
        language: String?,
        callId: Long,
        cpuMask: Long,
        nice: Int,
        stats: IntArray?,
    ): ByteArray?

    @JvmStatic
    external fun nativeCancel(callId: Long)

    @JvmStatic
    external fun nativeFree(handle: Long)

    @JvmStatic
    external fun nativeGetLastError(): ByteArray

    @JvmStatic
    external fun nativeBenchmark(threads: Int, cpuMask: Long, nice: Int): Long
}

actual fun whisperInit(modelPath: String): Long {
    val handle = WhisperJNI.nativeInit(modelPath)
    if (handle == 0L) {
        throw RuntimeException("whisper init failed: ${whisperGetLastError()}")
    }
    return handle
}

actual fun whisperTranscribe(
    handle: Long,
    pcm: FloatArray,
    threads: Int,
    language: String?,
    callId: Long,
    placement: EnginePlacement,
    stats: TranscribeStats?,
): String {
    // The shim writes into a one-slot array; the stats object is filled
    // from it on every exit so a failed call still reports what it was given.
    val slots = if (stats != null) intArrayOf(-1) else null
    try {
        val bytes = WhisperJNI.nativeTranscribe(
            handle, pcm, threads, language, callId, placement.cpuMask, placement.nice, slots,
        ) ?: throw RuntimeException("whisper transcription failed: ${whisperGetLastError()}")
        return bytes.decodeToString()
    } finally {
        if (stats != null && slots != null) stats.inputSamples = slots[0]
    }
}

actual fun whisperCancel(callId: Long) {
    WhisperJNI.nativeCancel(callId)
}

actual fun whisperFree(handle: Long) {
    if (handle != 0L) {
        WhisperJNI.nativeFree(handle)
    }
}

actual fun whisperGetLastError(): String = WhisperJNI.nativeGetLastError().decodeToString()

actual fun whisperBenchmark(threads: Int, placement: EnginePlacement): Long {
    val ns = WhisperJNI.nativeBenchmark(threads, placement.cpuMask, placement.nice)
    if (ns <= 0L) {
        throw RuntimeException("whisper benchmark failed: ${whisperGetLastError()}")
    }
    return ns
}
