package coredevices.coreapp.indexfeed

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

const val INDEX_EXPORT_NAME = "index-recordings.zip"

data class ExportRecording(
    val ringId: String,
    val iteration: Int,
    val collectionIndex: Int,
    val recordedAtEpochSeconds: Long,
    val durationMillis: Long,
    val buttonSequence: String?,
    val state: TranscriptState,
    val transcript: String?,
    val audio: ByteArray?,
)

/**
 * One folder per recording, named from the UTC timestamp, plus a manifest
 * and a combined transcript file. [zipEntries] is the JVM zip writer.
 */
fun exportRecordingsZip(rows: List<ExportRecording>, zone: TimeZone = TimeZone.UTC): ByteArray =
    zipEntries(exportEntries(rows, zone))

internal fun exportEntries(rows: List<ExportRecording>, zone: TimeZone = TimeZone.UTC): List<Pair<String, ByteArray>> {
    val manifestRows = rows.map { it.toManifestRow(zone) }
    val entries = mutableListOf<Pair<String, ByteArray>>()
    entries += "manifest.json" to json.encodeToString(ExportManifest(manifestRows)).encodeToByteArray()
    entries += "manifest.csv" to manifestCsv(manifestRows).encodeToByteArray()
    entries += "transcripts.txt" to combinedTranscripts(manifestRows).encodeToByteArray()
    rows.zip(manifestRows).forEach { (row, manifest) ->
        val folder = manifest.folder
        entries += "$folder/metadata.json" to json.encodeToString(manifest).encodeToByteArray()
        entries += "$folder/transcript.txt" to (row.transcript ?: "").encodeToByteArray()
        if (row.audio != null) entries += "$folder/audio.wav" to row.audio
    }
    return entries
}

internal expect fun zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray

internal fun folderName(row: ExportRecording, zone: TimeZone = TimeZone.UTC): String {
    val local = Instant.fromEpochSeconds(row.recordedAtEpochSeconds).toLocalDateTime(zone)
    val month = (local.month.ordinal + 1).toString().padStart(2, '0')
    val day = local.day.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    val second = local.second.toString().padStart(2, '0')
    val stamp = "${local.year}-$month-${day}T$hour$minute${second}Z"
    return "recordings/$stamp-i${row.iteration}-c${row.collectionIndex}-${row.ringId}"
}

private fun ExportRecording.toManifestRow(zone: TimeZone) = ExportManifestRow(
    folder = folderName(this, zone),
    recordedAtEpochSeconds = recordedAtEpochSeconds,
    durationMillis = durationMillis,
    ringId = ringId,
    iteration = iteration,
    collectionIndex = collectionIndex,
    buttonSequence = buttonSequence,
    transcriptionStatus = state.name,
    transcription = transcript,
    audioFile = if (audio != null) "audio.wav" else null,
)

private fun manifestCsv(rows: List<ExportManifestRow>): String = buildString {
    appendLine(
        "folder,recordedAtEpochSeconds,durationMillis,ringId,iteration,collectionIndex,buttonSequence,transcriptionStatus,transcription",
    )
    rows.forEach { row ->
        append(csv(row.folder))
        append(',')
        append(row.recordedAtEpochSeconds)
        append(',')
        append(row.durationMillis)
        append(',')
        append(csv(row.ringId))
        append(',')
        append(row.iteration)
        append(',')
        append(row.collectionIndex)
        append(',')
        append(csv(row.buttonSequence))
        append(',')
        append(csv(row.transcriptionStatus))
        append(',')
        appendLine(csv(row.transcription))
    }
}

private fun combinedTranscripts(rows: List<ExportManifestRow>): String {
    if (rows.isEmpty()) return "No recordings.\n"
    return buildString {
        rows.forEachIndexed { index, row ->
            if (index > 0) append("---\n")
            append(row.folder)
            append('\n')
            append(row.transcription.orEmpty())
            append("\n\n")
        }
    }
}

private fun csv(value: String?): String {
    val text = value ?: ""
    val quoted = text.contains(',') || text.contains('"') || text.contains('\n') || text.contains('\r')
    if (!quoted) return text
    return "\"" + text.replace("\"", "\"\"") + "\""
}

@Serializable
private data class ExportManifest(val recordings: List<ExportManifestRow>)

@Serializable
internal data class ExportManifestRow(
    val folder: String,
    val recordedAtEpochSeconds: Long,
    val durationMillis: Long,
    val ringId: String,
    val iteration: Int,
    val collectionIndex: Int,
    val buttonSequence: String?,
    val transcriptionStatus: String,
    val transcription: String?,
    val audioFile: String?,
)

private val json = Json { encodeDefaults = true }
