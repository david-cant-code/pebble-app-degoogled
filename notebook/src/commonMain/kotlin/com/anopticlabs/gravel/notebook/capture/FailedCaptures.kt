package com.anopticlabs.gravel.notebook.capture

/**
 * Why a capture was flagged, stored in its queue entry as [code]: the codes are a stored format,
 * so a reason is never renamed or reused.
 */
enum class CaptureFailureReason(val code: String) {
    /** The sealed recording failed to open. */
    DAMAGED("damaged"),
    UNREADABLE_AUDIO("unreadable_audio"),
    TOO_LONG("too_long"),
    NO_SPEECH("no_speech"),
    SPEECH_TIMEOUT("speech_timeout"),
    SPEECH_REFUSED("speech_refused"),
    TEXT_TOO_LONG("text_too_long"),
    TEXT_TIMEOUT("text_timeout"),
    TEXT_REFUSED("text_refused"),
    NOT_SAVED("not_saved"),

    /** The transcription server failed it again after the user's Try again. */
    SERVER_ERROR("server_error"),

    /** What a flagged entry with no code reads as: it ran out of attempts. */
    TRIES_RAN_OUT("tries_ran_out"),

    /** A code this build does not know, or a flag that named no reason. */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun of(code: String): CaptureFailureReason = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Meaningful only for an entry that [needsReview]. */
val QueuedCapture.failureReason: CaptureFailureReason
    get() = failure?.let(CaptureFailureReason::of) ?: CaptureFailureReason.TRIES_RAN_OUT

/** A flagged capture as the failed list shows it; one without its recording can only be deleted. */
data class FailedCapture(
    val id: String,
    val recordedAtMillis: Long,
    val durationMillis: Long?,
    val reason: CaptureFailureReason,
    val retryable: Boolean = true,
)

/**
 * The flagged entries, newest recording first. One without its recording ([hasRecording]) reads as
 * damaged, and one marked damaged that has its recording back reads as unknown.
 */
fun failedCaptures(queue: List<QueuedCapture>, hasRecording: (String) -> Boolean): List<FailedCapture> =
    queue.filter { it.needsReview }
        .sortedByDescending { it.queuedAtMillis }
        .map {
            val present = hasRecording(it.id)
            val reason = when {
                !present -> CaptureFailureReason.DAMAGED
                it.failureReason == CaptureFailureReason.DAMAGED -> CaptureFailureReason.UNKNOWN
                else -> it.failureReason
            }
            FailedCapture(it.id, it.queuedAtMillis, it.durationMillis, reason, present)
        }

/** Flags the entry [id] with [reason], in place; a no-op when it is absent. */
fun flagQueuedCapture(queue: List<QueuedCapture>, id: String, reason: CaptureFailureReason): List<QueuedCapture> =
    queue.map { if (it.id == id) it.copy(attempts = MAX_QUEUE_ATTEMPTS, flagged = true, failure = reason.code) else it }

/** Gives the flagged entries among [ids] fresh attempts, the server's included. */
fun retryFailedCaptures(queue: List<QueuedCapture>, ids: Set<String>): List<QueuedCapture> = queue.map {
    if (it.needsReview && it.id in ids) it.copy(attempts = 0, flagged = false, failure = null, serverTries = 0, serverTriedAtMillis = null) else it
}
