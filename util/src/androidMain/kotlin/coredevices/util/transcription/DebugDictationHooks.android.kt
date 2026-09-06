package coredevices.util.transcription

import android.content.Context
import coredevices.util.isDebugBuild
import org.koin.mp.KoinPlatform

// The clip ships only in the debug variant's assets (androidApp
// src/debug/assets); a release build has no such asset and reads null even
// before the build check.
private const val DEBUG_CLIP_ASSET = "debug-dictation-clip.raw"

actual fun debugDictationClip(): ByteArray? {
    if (!isDebugBuild()) return null
    return runCatching {
        KoinPlatform.getKoin().get<Context>().applicationContext.assets
            .open(DEBUG_CLIP_ASSET).use { it.readBytes() }
    }.getOrNull()
}
