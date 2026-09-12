package coredevices.whisper

import android.os.Build
import java.io.File

/**
 * Android actuals. The engine runs in an isolated process
 * ([WhisperEngineService]); these functions are its app-process side
 * ([WhisperEngineClient]) and keep the common surface path-based: the
 * model path is opened here and crosses as a descriptor, the audio
 * crosses as shared memory, and a handle names an engine context in that
 * process. [WhisperCpuJNI], the baseline-architecture probe behind
 * [isWhisperSupported], is the one native library this process loads;
 * the engine library is loaded only in the engine process, by
 * [WhisperJNI], after the same probe has passed there.
 *
 * Strings come back from the engine as UTF-8 byte arrays, decoded here.
 * Engine output can contain byte sequences that are not valid modified
 * UTF-8, and returning them through NewStringUTF would abort the process
 * under CheckJNI, so the shim never constructs Java strings itself.
 */

internal object WhisperCpuJNI {
    init {
        System.loadLibrary("whispercpu")
    }

    @JvmStatic
    external fun nativeIsWhisperSupported(): Boolean
}

// Missing library (repackaged APK, unexpected ABI) must read as
// "unsupported", never as a crash: the probe is called from UI code.
private val cpuSupported: Boolean by lazy {
    try {
        WhisperCpuJNI.nativeIsWhisperSupported()
    } catch (_: Throwable) {
        false
    }
}

/**
 * The audio transport, `android.os.SharedMemory`, exists from API 27
 * (the platform reference's API level for the class), so the engine is
 * unsupported on Android 8.0 and a phone there takes the same path as an
 * unsupported CPU; every other mention of the floor points here.
 * Unattached (no application context yet) reads as unsupported too, so
 * nothing binds before the app has set up.
 */
actual fun isWhisperSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && WhisperEngineClient.isAttached && cpuSupported

/**
 * The engine library's entry points. Touched only inside the engine
 * process, by [WhisperEngineService]: the library is compiled for
 * armv8.2+dotprod+fp16 and would crash at first use on older CPUs, so
 * that service checks [WhisperCpuJNI] before its first call here.
 */
internal object WhisperJNI {
    init {
        System.loadLibrary("whisperjni")
    }

    @JvmStatic
    external fun nativeInitFd(fd: Int): Long

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

actual fun whisperInit(modelPath: String): Long = WhisperEngineClient.init(File(modelPath))

actual fun whisperTranscribe(
    handle: Long,
    pcm: FloatArray,
    threads: Int,
    language: String?,
    callId: Long,
    placement: EnginePlacement,
    stats: TranscribeStats?,
): String {
    // The engine reports into a one-slot array; the stats object is
    // filled from it on every exit so a failed call still reports what
    // it was given.
    val slots = if (stats != null) intArrayOf(-1) else null
    try {
        return WhisperEngineClient.transcribe(
            handle, pcm, threads, language, callId, placement.cpuMask, placement.nice, slots,
        ).decodeToString()
    } finally {
        if (stats != null && slots != null) stats.inputSamples = slots[0]
    }
}

actual fun whisperCancel(callId: Long) = WhisperEngineClient.cancel(callId)

actual fun whisperFree(handle: Long) {
    if (handle != 0L) {
        WhisperEngineClient.free(handle)
    }
}

actual fun whisperGetLastError(): String = WhisperEngineClient.lastEngineError()

actual fun whisperBenchmark(threads: Int, placement: EnginePlacement): Long =
    WhisperEngineClient.benchmark(threads, placement.cpuMask, placement.nice)
