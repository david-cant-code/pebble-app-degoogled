package coredevices.util.transcription

// Unreachable in practice: the iOS whisper actuals are unsupported stubs,
// and there is no engine process.
internal actual fun engineProcessGeneration(): Long = 0L

internal actual fun engineProcessBindMillis(): Long? = null

internal actual fun engineProcessSnapshot(): EngineRuntimeSnapshot? = null

internal actual fun addEngineProcessDeathListener(listener: (generation: Long) -> Unit) {}
