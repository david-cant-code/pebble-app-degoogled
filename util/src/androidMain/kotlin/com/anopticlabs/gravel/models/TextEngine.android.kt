package com.anopticlabs.gravel.models

import android.os.Looper
import android.util.Log
import com.anopticlabs.gravel.llm.LlmEngineClient
import com.anopticlabs.gravel.llm.isLlmSupported
import coredevices.util.transcription.cpuMaxFrequenciesKHz
import coredevices.util.transcription.parseCpuList
import coredevices.util.transcription.readAllowedCpuIds
import coredevices.util.transcription.threadCountFor

actual fun isTextEngineSupported(): Boolean = isLlmSupported()

/**
 * Dictation's thread rule over the text engine process's CPUs (this process's before the first
 * bind), the count [LlmSpeedCalibration] was measured at. Throws on the main thread.
 */
fun textGenerationThreadCount(): Int {
    check(Looper.myLooper() != Looper.getMainLooper()) { "textGenerationThreadCount reads the engine process" }
    return threadCountFor(
        allowedCpuIds = textEngineCpuIds() ?: readAllowedCpuIds(),
        maxFreqKHzByCpu = cpuMaxFrequenciesKHz(),
        possibleCpus = Runtime.getRuntime().availableProcessors(),
    )
}

private fun textEngineCpuIds(): List<Int>? = try {
    LlmEngineClient.runtime(bindIfNeeded = false)?.cpusAllowedList?.let(::parseCpuList)
} catch (e: RuntimeException) {
    Log.w("TextEngine", "text engine runtime unavailable: ${e.message}")
    null
}
