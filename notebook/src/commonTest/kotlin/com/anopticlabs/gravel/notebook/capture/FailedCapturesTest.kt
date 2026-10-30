package com.anopticlabs.gravel.notebook.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class FailedCapturesTest {

    private fun entry(id: String, at: Long = 0, attempts: Int = 0, flagged: Boolean = false, failure: String? = null, durationMillis: Long? = null) =
        QueuedCapture(id, "$id.wav", at, attempts, flagged, durationMillis = durationMillis, failure = failure)

    @Test
    fun theStoredCodesStayAsWritten() {
        assertEquals(
            listOf(
                "damaged", "unreadable_audio", "too_long", "no_speech", "speech_timeout", "speech_refused",
                "text_too_long", "text_timeout", "text_refused", "not_saved", "server_error", "tries_ran_out", "unknown",
            ),
            CaptureFailureReason.entries.map { it.code },
        )
    }

    @Test
    fun aMissingCodeMeansTheTriesRanOutAndAnUnknownOneIsUnknown() {
        assertEquals(CaptureFailureReason.TRIES_RAN_OUT, entry("a", attempts = MAX_QUEUE_ATTEMPTS).failureReason)
        assertEquals(CaptureFailureReason.NO_SPEECH, entry("a", flagged = true, failure = "no_speech").failureReason)
        assertEquals(CaptureFailureReason.UNKNOWN, entry("a", flagged = true, failure = "from_a_later_build").failureReason)
    }

    @Test
    fun theFailedListIsTheFlaggedEntriesNewestFirstAndOneWithoutItsRecordingIsDamaged() {
        val queue = listOf(
            entry("old", at = 1, attempts = MAX_QUEUE_ATTEMPTS),
            entry("waiting", at = 2, attempts = MAX_QUEUE_ATTEMPTS - 1),
            entry("new", at = 3, flagged = true, failure = "no_speech", durationMillis = 7_000),
            entry("back", at = 0, flagged = true, failure = "damaged"),
        )
        assertEquals(
            listOf(
                FailedCapture("new", 3, 7_000, CaptureFailureReason.NO_SPEECH),
                FailedCapture("old", 1, null, CaptureFailureReason.DAMAGED, retryable = false),
                FailedCapture("back", 0, null, CaptureFailureReason.UNKNOWN),
            ),
            failedCaptures(queue) { it != "old" },
        )
    }

    @Test
    fun flaggingStoresTheReasonOnTheOneEntry() {
        val flagged = flagQueuedCapture(listOf(entry("a", attempts = 1), entry("b")), "a", CaptureFailureReason.TEXT_TIMEOUT)
        assertEquals(listOf(entry("a", attempts = MAX_QUEUE_ATTEMPTS, flagged = true, failure = "text_timeout"), entry("b")), flagged)
        assertEquals(flagged, flagQueuedCapture(flagged, "absent", CaptureFailureReason.DAMAGED))
    }

    @Test
    fun aRetryGivesFreshAttemptsToTheNamedFlaggedEntriesOnly() {
        val queue = listOf(
            entry("quiet", flagged = true, attempts = MAX_QUEUE_ATTEMPTS, failure = "no_speech"),
            entry("spent", attempts = MAX_QUEUE_ATTEMPTS),
            entry("waiting", attempts = 3),
        )
        assertEquals(listOf(queue[0], entry("spent"), queue[2]), retryFailedCaptures(queue, setOf("spent", "waiting")))
        assertEquals(listOf(entry("quiet"), entry("spent"), queue[2]), retryFailedCaptures(queue, setOf("quiet", "spent")))
    }

    @Test
    fun aRetryGivesTheServerFreshTriesToo() {
        val tried = entry("a", flagged = true, attempts = MAX_QUEUE_ATTEMPTS).copy(serverTries = MAX_SERVER_TRIES, serverTriedAtMillis = 5L)
        assertEquals(listOf(entry("a")), retryFailedCaptures(listOf(tried), setOf("a")))
    }

    @Test
    fun aNewAttemptCountDropsTheFailureCode() {
        val queue = listOf(entry("a", flagged = true, attempts = MAX_QUEUE_ATTEMPTS, failure = "no_speech"))
        assertEquals(listOf(entry("a", attempts = 4)), applyQueueDrainResults(queue, emptySet(), mapOf("a" to 4)))
    }
}
