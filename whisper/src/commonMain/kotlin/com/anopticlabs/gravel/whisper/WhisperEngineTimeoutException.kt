package com.anopticlabs.gravel.whisper

import coredevices.whisper.WhisperEngineUnavailableException

/** The engine did not answer a call within its deadline and its process was ended for it. */
class WhisperEngineTimeoutException(message: String, cause: Throwable? = null) :
    WhisperEngineUnavailableException(message, cause)
