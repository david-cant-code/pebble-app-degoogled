package com.anopticlabs.gravel.llm

/*
 * The two native libraries of :llm-native: the baseline-architecture
 * probe [LlmCpuJNI], the one library the app process loads, and the
 * engine [LlmJNI], loaded only in the engine process after the probe has
 * passed there. Text crosses as UTF-8 byte arrays both ways (llm_jni.cpp
 * states why).
 */

// The init blocks here are the knowing exception to the repo's rule: an
// object of externals has no other place to load its library, and no DI
// graph constructs these.
internal object LlmCpuJNI {
    init {
        System.loadLibrary("llmcpu")
    }

    @JvmStatic
    external fun nativeIsLlmSupported(): Boolean
}

// A missing library (repackaged APK, unexpected ABI) reads as unsupported.
private val cpuSupported: Boolean by lazy {
    try {
        LlmCpuJNI.nativeIsLlmSupported()
    } catch (_: Throwable) {
        false
    }
}

/** True when the client is attached and the CPU meets the compiled feature floor (armv8.2 dotprod + fp16). */
fun isLlmSupported(): Boolean = LlmEngineClient.isAttached && cpuSupported

/** The engine library's entry points; [LlmEngineService] checks [LlmCpuJNI] before the first one. */
internal object LlmJNI {
    init {
        System.loadLibrary("llmjni")
    }

    @JvmStatic
    external fun nativeInitFd(fd: Int, contextTokens: Int): Long

    @JvmStatic
    external fun nativeFormatChat(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>): ByteArray?

    @JvmStatic
    external fun nativeGenerate(
        handle: Long,
        prompt: ByteArray,
        maxTokens: Int,
        threads: Int,
        callId: Long,
        cpuMask: Long,
        nice: Int,
        stats: LongArray?,
    ): ByteArray?

    @JvmStatic
    external fun nativeCancel(callId: Long)

    @JvmStatic
    external fun nativeFree(handle: Long)

    @JvmStatic
    external fun nativeGetLastError(): ByteArray
}
