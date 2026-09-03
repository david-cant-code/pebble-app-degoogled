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

private fun readAllowedCpuCount(): Int? = readAllowedCpuIds()?.size

/** The CPU ids in this process's affinity mask right now, or null if unreadable. */
internal fun readAllowedCpuIds(): List<Int>? = runCatching {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')
            ?.let(::parseCpuList)
    }
}.getOrNull()

// Maximum frequency per CPU from sysfs, read once: the values are static
// hardware facts, and the file is world-readable on the devices that
// matter. A CPU whose file cannot be read is simply absent from the map.
private val cpuMaxFrequencies: Map<Int, Long> by lazy {
    val cpus = Runtime.getRuntime().availableProcessors()
    (0 until cpus).mapNotNull { cpu ->
        runCatching {
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        }.getOrNull()?.let { cpu to it }
    }.toMap()
}

/** Maximum frequency in kHz per online CPU id, empty when sysfs is unreadable. */
internal fun cpuMaxFrequenciesKHz(): Map<Int, Long> = cpuMaxFrequencies

private fun readCpuset(): String? = runCatching {
    File("/proc/self/cpuset").readText().trim().ifEmpty { null }
}.getOrNull()

private fun readImportance(): Int? = runCatching {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    info.importance
}.getOrNull()
