package coredevices.coreapp.indexfeed

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

enum class TranscriptState {
    Pending,
    Done,
    NotTranscribed,
}

data class IndexRecording(
    val id: String,
    val ringId: String,
    val iteration: Int,
    val collectionIndex: Int,
    val recordedAtEpochSeconds: Long,
    val durationMillis: Long,
    val buttonSequence: String?,
    val sampleRate: Int,
    val audioFileName: String,
    val audioPath: String,
    val transcript: String?,
    val state: TranscriptState,
    val detail: String?,
)

data class IncomingRecording(
    val ringId: String,
    val collectionIndex: Int,
    val recordedAtEpochSeconds: Long,
    val durationMillis: Long,
    val buttonSequence: String?,
    val sampleRate: Int,
    val wav: ByteArray,
)

sealed class AcceptResult {
    data class Inserted(val recording: IndexRecording) : AcceptResult()
    data class Duplicate(val recording: IndexRecording) : AcceptResult()
}

/** A re-pair resets the ring's collection counter, so the key includes the iteration. */
internal fun recordingId(ringId: String, iteration: Int, collectionIndex: Int): String =
    "${normalizeRingId(ringId)}|$iteration|$collectionIndex"

internal fun normalizeRingId(id: String): String =
    id.replace(":", "").lowercase().ifBlank { "unknown" }

fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val seconds = (total % 60).toString().padStart(2, '0')
    return "${total / 60}:$seconds"
}

fun formatRecordedAt(epochSeconds: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val local = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone)
    val month = (local.month.ordinal + 1).toString().padStart(2, '0')
    val day = local.day.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.year}-$month-$day $hour:$minute"
}

fun transcriptLabel(recording: IndexRecording): String = when (recording.state) {
    TranscriptState.Pending -> "Transcribing…"
    TranscriptState.Done -> recording.transcript?.takeIf { it.isNotBlank() } ?: "No speech detected"
    TranscriptState.NotTranscribed -> when (recording.detail) {
        "unavailable" -> "Not transcribed. Download a speech model in Settings → Speech Recognition → Manage Offline Models, then retry."
        else -> "Not transcribed"
    }
}

fun canRetryTranscription(recording: IndexRecording): Boolean =
    recording.state == TranscriptState.NotTranscribed
