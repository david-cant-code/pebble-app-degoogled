package coredevices.util

import android.content.Context
import android.content.pm.ApplicationInfo
import org.koin.mp.KoinPlatform

// Read once from the application info flags through the Koin-provided
// Context; with no Koin context (host tests, tooling) the answer is false,
// the closed direction for a debug-only gate.
private val debugBuild: Boolean by lazy {
    runCatching {
        val context = KoinPlatform.getKoin().get<Context>().applicationContext
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }.getOrDefault(false)
}

actual fun isDebugBuild(): Boolean = debugBuild
