package coredevices.util.transcription

// Unreachable in practice: the iOS whisper actuals are unsupported stubs.
actual fun engineRuntimeSnapshot(): EngineRuntimeSnapshot =
    EngineRuntimeSnapshot(allowedCpus = null, cpuset = null, importance = null)
