package coredevices.util.models

import android.os.Build
import coredevices.util.Platform

actual fun Platform.supportsNPU(): Boolean = false

private val HEAVY_MODELS = listOf(
    "Tensor G3",
    "Tensor G4",
    "Tensor G5",
    "Tensor G6",
    "SM8650", // Snapdragon 8 Gen 3
    "SM8750", // Snapdragon 8 Elite
    "SM8850", // Snapdragon 8 Elite Gen 5
)
actual fun Platform.supportsHeavyCPU(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return false
    }
    val soc = Build.SOC_MODEL
    return HEAVY_MODELS.any { soc.contains(it, ignoreCase = true) }
}
// Total physical RAM without needing a Context: MemTotal from
// /proc/meminfo, which Android keeps readable by apps. Failure degrades to
// 0, which the recommendation treats as the low tier, the safe direction.
actual fun Platform.totalRamBytes(): Long = try {
    java.io.File("/proc/meminfo").useLines { lines ->
        lines.firstOrNull { it.startsWith("MemTotal:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.times(1024L)
            ?: 0L
    }
} catch (e: Exception) {
    0L
}

actual fun Platform.prefersEnglishModels(): Boolean =
    java.util.Locale.getDefault().language == "en"
