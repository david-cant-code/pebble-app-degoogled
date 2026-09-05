package coredevices.util.transcription

import android.content.Context
import org.koin.mp.KoinPlatform

// App-private files, never external storage: the captures are voice data
// and stay inside the sandbox unless the developer pulls them by hand. The
// backup rules exclude this directory from cloud backup and device transfer.
internal actual fun dictationCaptureDirectory(): String? = runCatching {
    KoinPlatform.getKoin().get<Context>().applicationContext
        .filesDir.resolve("debug-captures").absolutePath
}.getOrNull()
