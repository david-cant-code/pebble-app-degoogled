package coredevices.util.transcription

import android.app.ActivityManager
import java.io.File

/**
 * Android answers straight from procfs and ActivityManager, no Context
 * needed: `/proc/self` is always readable by the owning process, and
 * `getMyMemoryState` is static. Every read is fenced so a SELinux surprise
 * or a format change on some OEM kernel degrades to a null field instead
 * of failing the dictation it is meant to explain.
 */
actual fun engineRuntimeSnapshot(): EngineRuntimeSnapshot = EngineRuntimeSnapshot(
    allowedCpus = readAllowedCpuCount(),
    cpuset = readCpuset(),
    importance = readImportance(),
)

private fun readAllowedCpuCount(): Int? = runCatching {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')
            ?.let(::parseCpuListCount)
    }
}.getOrNull()

private fun readCpuset(): String? = runCatching {
    File("/proc/self/cpuset").readText().trim().ifEmpty { null }
}.getOrNull()

private fun readImportance(): Int? = runCatching {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    info.importance
}.getOrNull()
