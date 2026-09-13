package coredevices.util.transcription

import coredevices.analytics.CoreAnalytics
import coredevices.whisper.WhisperEngineUnavailableException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Instant

private class RecordingAnalytics : CoreAnalytics {
    val events = mutableListOf<Pair<String, Map<String, Any>?>>()
    override fun logEvent(name: String, parameters: Map<String, Any>?) {
        events.add(name to parameters)
    }
    override suspend fun logHeartbeatState(name: String, value: Boolean, timestamp: Instant) {}
    override suspend fun processHeartbeat() {}
    override fun updateLastConnectedSerial(serial: String?) {}
    override fun updateRingTransferDurationMetric(duration: Duration) {}
    override fun updateRingLifetimeCollectionCount(serial: String, count: Int) {}
}

class TranscriptionAnalyticsTest {
    @Test
    fun successEventIncludesService() {
        val analytics = RecordingAnalytics()
        analytics.logTranscriptionSuccess("wisprflow")
        assertEquals(
            listOf<Pair<String, Map<String, Any>?>>(
                TRANSCRIPTION_SUCCESS_EVENT to mapOf("service" to "wisprflow")
            ),
            analytics.events,
        )
    }

    @Test
    fun failureEventIncludesServiceAndReason() {
        val analytics = RecordingAnalytics()
        analytics.logTranscriptionFailure("cactus", "timeout")
        assertEquals(
            listOf<Pair<String, Map<String, Any>?>>(
                TRANSCRIPTION_FAILURE_EVENT to
                        mapOf("service" to "cactus", "reason" to "timeout", "desc" to "<none>")
            ),
            analytics.events,
        )
    }

    /**
     * The whole table of the mapping, one assertion per arm: the tokens
     * name the app's own exception classes on the analytics event and on
     * the diagnostics lines, where a class name would be a minified one.
     */
    @Test
    fun failureReasonMapsExceptionTypes() {
        assertEquals(
            "engine_unavailable",
            transcriptionFailureReason(WhisperEngineUnavailableException("gone")),
        )
        assertEquals(
            "not_enough_memory",
            transcriptionFailureReason(TranscriptionException.NotEnoughMemory()),
        )
        assertEquals(
            "service_unavailable",
            transcriptionFailureReason(TranscriptionException.TranscriptionServiceUnavailable()),
        )
        assertEquals(
            "network_error",
            transcriptionFailureReason(TranscriptionException.TranscriptionNetworkError(Exception("x"))),
        )
        assertEquals(
            "requires_download",
            transcriptionFailureReason(TranscriptionException.TranscriptionRequiresDownload("x")),
        )
        assertEquals(
            "no_supported_language",
            transcriptionFailureReason(TranscriptionException.NoSupportedLanguage()),
        )
        assertEquals(
            "no_speech_empty_result",
            transcriptionFailureReason(TranscriptionException.NoSpeechDetected("empty_result")),
        )
        assertEquals(
            "in_progress",
            transcriptionFailureReason(TranscriptionException.TranscriptionInProgress("x")),
        )
        assertEquals(
            "service_error",
            transcriptionFailureReason(TranscriptionException.TranscriptionServiceError("x")),
        )
        val timeout = assertFailsWith<TimeoutCancellationException> { runBlocking { withTimeout(1) { delay(1_000) } } }
        assertEquals("timeout", transcriptionFailureReason(timeout))
        assertEquals("IllegalStateException", transcriptionFailureReason(IllegalStateException()))
    }
}
