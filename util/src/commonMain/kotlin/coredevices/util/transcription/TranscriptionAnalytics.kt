package coredevices.util.transcription

import coredevices.analytics.CoreAnalytics
import coredevices.whisper.WhisperEngineUnavailableException
import kotlinx.coroutines.TimeoutCancellationException

const val TRANSCRIPTION_SUCCESS_EVENT = "transcription.success"
const val TRANSCRIPTION_FAILURE_EVENT = "transcription.failure"

fun CoreAnalytics.logTranscriptionSuccess(service: String) {
    logEvent(TRANSCRIPTION_SUCCESS_EVENT, mapOf("service" to service))
}

fun CoreAnalytics.logTranscriptionFailure(service: String, reason: String, desc: String? = null) {
    logEvent(TRANSCRIPTION_FAILURE_EVENT, mapOf("service" to service, "reason" to reason, "desc" to (desc?.take(128) ?: "<none>")))
}

/**
 * A stable token for a transcription failure, shared by the analytics
 * event and the `outcome=` field of the dictation diagnostics lines. The
 * app's own exception classes are named here rather than through their
 * class names, which the release build's minifier rewrites; the fallback
 * class name is for platform and library exceptions, which keep theirs.
 */
fun transcriptionFailureReason(e: Throwable): String = when (e) {
    is WhisperEngineUnavailableException -> "engine_unavailable"
    is TranscriptionException.NotEnoughMemory -> "not_enough_memory"
    is TranscriptionException.TranscriptionServiceUnavailable -> "service_unavailable"
    is TranscriptionException.TranscriptionNetworkError -> "network_error"
    is TranscriptionException.TranscriptionRequiresDownload -> "requires_download"
    is TranscriptionException.NoSupportedLanguage -> "no_supported_language"
    is TranscriptionException.NoSpeechDetected -> "no_speech_${e.type}"
    is TranscriptionException.TranscriptionInProgress -> "in_progress"
    is TranscriptionException.TranscriptionServiceError -> "service_error"
    is TimeoutCancellationException -> "timeout"
    else -> e::class.simpleName ?: "unknown"
}