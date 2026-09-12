package coredevices.whisper

/**
 * The engine could not be reached. On Android the engine runs in its own
 * process, and this is thrown when that process cannot be started, is
 * not connected, or died under a call. It is distinct from an engine
 * failure with the process alive, which is a RuntimeException carrying
 * the engine's error text: a caller holding an engine handle must treat
 * the handle as gone and initialize again, and a caller deciding
 * availability reports local recognition unavailable rather than broken.
 * Never thrown on iOS, where no engine exists.
 */
class WhisperEngineUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
