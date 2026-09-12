package coredevices.util.transcription

import android.util.Log
import coredevices.whisper.WhisperEngineClient
import coredevices.whisper.WhisperEngineRuntime

private const val TAG = "EngineProcess"

internal actual fun engineProcessGeneration(): Long = WhisperEngineClient.processGeneration()

internal actual fun engineProcessBindMillis(): Long? = WhisperEngineClient.lastBindMillis()

internal actual fun engineProcessSnapshot(): EngineRuntimeSnapshot? =
    engineProcessRuntime()?.let(::engineProcessSnapshot)

internal actual fun addEngineProcessDeathListener(listener: (generation: Long) -> Unit) =
    WhisperEngineClient.addDeathListener(listener)

/**
 * The CPU ids the engine process may run on right now, or null when no
 * engine process is bound or its mask is unreadable there. The decode
 * runs in that process, so this is the mask the thread count must come
 * from; `/proc/self` here describes the wrong process.
 */
internal fun engineProcessCpuIds(): List<Int>? =
    engineProcessRuntime()?.cpusAllowedList?.let(::parseCpuList)

/**
 * The engine process's report about itself, or null when none is bound
 * or the report failed. Reads only; a bind belongs to the engine calls,
 * never to a diagnostics read. Fenced like the host reads beside it: a
 * failed report degrades to the host's facts, it never fails a dictation.
 */
private fun engineProcessRuntime(): WhisperEngineRuntime? = try {
    WhisperEngineClient.runtime(bindIfNeeded = false)
} catch (e: RuntimeException) {
    Log.w(TAG, "engine process runtime unavailable: ${e.message}")
    null
}

/** The engine process's placement facts in the snapshot layout, unreadable fields null. */
internal fun engineProcessSnapshot(runtime: WhisperEngineRuntime): EngineRuntimeSnapshot = EngineRuntimeSnapshot(
    allowedCpus = runtime.cpusAllowedList?.let(::parseCpuListCount),
    cpuset = runtime.cpuset,
    importance = runtime.importance,
    process = EngineRuntimeSnapshot.PROCESS_ENGINE,
)
