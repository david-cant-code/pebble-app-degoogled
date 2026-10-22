package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason

/** What the drain does with a queued capture whose processing failed. */
enum class CaptureFailureKind {
    /** Transient: the entry's attempts go up and a later drain tries again, until it is flagged. */
    RETRY,

    /** Not about this capture (no model, no key): the drain stops, and the entry waits without an attempt counted. */
    HOLD,

    /** Not about this capture, and not about every capture: it waits without an attempt counted while the rest go on. */
    WAIT,

    /** Would fail the same way again: the entry is flagged at once and its recording kept. */
    FLAG,

    /** The capture's next step changed (to the phone, say): it goes through the processing rules again, without an attempt counted. */
    REROUTE,
}

/** What the user can do about a hold, for the screen to offer. */
enum class CaptureRemedy {
    /** The text model page explains the hold or fixes it. */
    TEXT_MODEL,

    /** The speech recognition settings change what the Notebook transcribes with. */
    SPEECH_SETTINGS,

    /** The transcription server failed: its settings, and a Try again that sends to it once more. */
    SERVER_CHECK,
}

/** A line for the screen with what the user can do about it; see [CaptureSpeech.notice]. */
data class CaptureNotice(val reason: String, val remedy: CaptureRemedy? = null)

/** [reason] is what a [CaptureFailureKind.FLAG] stores with the entry; [waitsForNetwork] marks a hold or wait a network change may end. */
class CaptureProcessingException(
    val kind: CaptureFailureKind,
    message: String,
    cause: Throwable? = null,
    val remedy: CaptureRemedy? = null,
    val reason: CaptureFailureReason? = null,
    val waitsForNetwork: Boolean = false,
) : Exception(message, cause)
