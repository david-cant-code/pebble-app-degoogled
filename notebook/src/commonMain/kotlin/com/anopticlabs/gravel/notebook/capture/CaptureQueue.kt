package com.anopticlabs.gravel.notebook.capture

import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/**
 * The durable record of a captured recording, written before any processing; its audio blob is
 * keyed by [id]. [zoneId] is the time zone the recording was made in, see [captureZone];
 * [durationMillis] its length when known, see [isLong]; [failure] the code of why it was flagged, see [failureReason];
 * [phoneTried] that the phone's speech step already failed it under a phone-then-server route; [serverTries] how
 * often the server could not be reached for it under a server-then-phone route, the last at [serverTriedAtMillis].
 */
@Serializable
data class QueuedCapture(
    val id: String,
    val originalFileName: String,
    val queuedAtMillis: Long,
    val attempts: Int = 0,
    val flagged: Boolean = false,
    val zoneId: String? = null,
    val durationMillis: Long? = null,
    val fromWatch: Boolean = false,
    val failure: String? = null,
    val phoneTried: Boolean = false,
    val serverTries: Int = 0,
    val serverTriedAtMillis: Long? = null,
)

const val MAX_ZONE_ID_CHARS = 64

private const val WATCH_RECORDING_PREFIX = "watch_"

/** A new recording's file name; one made on the watch is named so a copy kept outside the queue is queued as one again. */
fun captureFileName(capturedAtMillis: Long, extension: String, fromWatch: Boolean): String =
    "${if (fromWatch) WATCH_RECORDING_PREFIX else "recording_"}$capturedAtMillis.$extension"

fun isWatchRecordingName(name: String): Boolean = name.startsWith(WATCH_RECORDING_PREFIX)

private val CAPTURE_FILE_NAME = Regex("^(?:watch|recording)_(\\d{1,15})\\.[A-Za-z0-9]+$")

/** The start time a [captureFileName] carries, or null for any other name. */
fun capturedAtFromName(name: String): Long? = CAPTURE_FILE_NAME.matchEntire(name)?.groupValues?.get(1)?.toLong()

/** The zone [zoneId] names, or null when it is absent, too long, or not a zone this runtime knows. */
fun captureZone(zoneId: String?): TimeZone? {
    if (zoneId == null || zoneId.length > MAX_ZONE_ID_CHARS) return null
    return try {
        TimeZone.of(zoneId)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Attempts after which an entry is flagged for the user instead of retried. */
const val MAX_QUEUE_ATTEMPTS = 10

/** An entry the drain no longer picks up on its own. */
val QueuedCapture.needsReview: Boolean get() = flagged || attempts >= MAX_QUEUE_ATTEMPTS

/** Appends a fresh entry unless [id] is already queued, so a retried write never doubles an entry. */
fun enqueueCapture(queue: List<QueuedCapture>, id: String, originalFileName: String, queuedAtMillis: Long): List<QueuedCapture> =
    if (queue.any { it.id == id }) queue else queue + QueuedCapture(id, originalFileName, queuedAtMillis)

fun removeQueuedCapture(queue: List<QueuedCapture>, id: String): List<QueuedCapture> =
    queue.filterNot { it.id == id }

fun markPhoneTried(queue: List<QueuedCapture>, id: String): List<QueuedCapture> =
    queue.map { if (it.id == id) it.copy(phoneTried = true) else it }

/** Failed tries to reach the server after which a long recording goes to the phone, and the least time between two. */
const val MAX_SERVER_TRIES = 3
const val SERVER_TRY_SPACING_MILLIS = 15 * 60 * 1000L

fun markServerTry(queue: List<QueuedCapture>, id: String, atMillis: Long): List<QueuedCapture> =
    queue.map { if (it.id == id) it.copy(serverTries = it.serverTries + 1, serverTriedAtMillis = atMillis) else it }

/** Marks the foreground entry [id] as having failed once, in place; a no-op when it is absent. */
fun markQueuedCaptureFailed(queue: List<QueuedCapture>, id: String): List<QueuedCapture> =
    queue.map { if (it.id == id) it.copy(attempts = 1) else it }

/**
 * The queue after a drain: [succeeded] entries are removed, [bumped] entries take their new attempt
 * count, are flagged from [MAX_QUEUE_ATTEMPTS] and lose any failure code, and entries added during the drain are kept.
 */
fun applyQueueDrainResults(current: List<QueuedCapture>, succeeded: Set<String>, bumped: Map<String, Int>): List<QueuedCapture> =
    current.mapNotNull { entry ->
        when (entry.id) {
            in succeeded -> null
            in bumped -> {
                val attempts = bumped.getValue(entry.id)
                entry.copy(attempts = attempts, flagged = attempts >= MAX_QUEUE_ATTEMPTS, failure = null)
            }
            else -> entry
        }
    }
