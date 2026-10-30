package com.anopticlabs.gravel.transcription

import coredevices.util.transcription.TranscriptionException

/** A long-form call ran past its deadline. */
class LongFormTimeoutException(modelUsed: String?) : TranscriptionException.TranscriptionServiceUnavailable(modelUsed)
