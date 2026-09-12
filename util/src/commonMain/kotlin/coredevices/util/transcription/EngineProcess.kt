package coredevices.util.transcription

/*
 * What the transcription service reads about the process the engine runs
 * in, beside the :whisper bindings themselves. On Android the engine
 * lives in its own isolated process, which can die under a decode, while
 * idle, or fail to start; the service keeps the model resident across
 * that and reports which process a decode ran in. On a platform without
 * a separate engine process every answer here is the "no process" one.
 */

/**
 * The generation of the engine process behind the current handles: one
 * higher each time a fresh engine process is bound, 0 while none has
 * been. Recorded beside a handle at init, it tells a death report for a
 * generation whether that handle came from the process that died.
 */
internal expect fun engineProcessGeneration(): Long

/**
 * Milliseconds the most recent engine process bind took, spawn included;
 * null before any bind or where the engine has no process of its own.
 */
internal expect fun engineProcessBindMillis(): Long?

/**
 * The engine process's own scheduling facts, or null when no engine
 * process is bound. Never binds one, and never throws: a diagnostics
 * read must not fail the dictation it explains.
 */
internal expect fun engineProcessSnapshot(): EngineRuntimeSnapshot?

/**
 * Registers [listener] to run once per engine process death, with the
 * generation of the process that died, on whichever thread observed the
 * death; the listener must not block. No-op where the engine has no
 * process of its own.
 */
internal expect fun addEngineProcessDeathListener(listener: (generation: Long) -> Unit)
