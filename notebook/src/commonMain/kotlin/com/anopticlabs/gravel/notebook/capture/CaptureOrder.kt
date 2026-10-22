package com.anopticlabs.gravel.notebook.capture

import com.anopticlabs.gravel.notebook.pipeline.SpeechRoute

/** Recordings longer than this are long: they go after short ones, and a pause or the charger rule can hold them. */
const val LONG_CAPTURE_MILLIS = 30_000L

/** A recording of unknown length counts as long; a recording from the watch never does. */
val QueuedCapture.isLong: Boolean get() = !fromWatch && (durationMillis ?: Long.MAX_VALUE) > LONG_CAPTURE_MILLIS

/** What the user paused: nothing, the long recordings, or everything but recordings from the watch. */
enum class ProcessingPause { NONE, LONG, ALL }

/**
 * What may start now. [longWaitsForPower] is the user's charger setting, [pluggedIn] whether the
 * phone is on power, [approved] the long recordings the user asked to process on battery, and
 * [speech] where recordings are transcribed.
 */
data class ProcessingRules(
    val pause: ProcessingPause = ProcessingPause.NONE,
    val longWaitsForPower: Boolean = false,
    val pluggedIn: Boolean = true,
    val approved: Set<String> = emptySet(),
    val speech: SpeechRoute = SpeechRoute.PHONE,
)

/**
 * Whether [entry]'s next transcription is known to be on the server: a capture the phone's memory turns away is not,
 * and one the server could not be reached for [MAX_SERVER_TRIES] times goes to the phone.
 */
fun ProcessingRules.toServer(entry: QueuedCapture): Boolean = when (speech) {
    SpeechRoute.PHONE -> false
    SpeechRoute.SERVER -> true
    SpeechRoute.PHONE_THEN_SERVER -> entry.phoneTried
    SpeechRoute.SERVER_THEN_PHONE -> entry.serverTries < MAX_SERVER_TRIES
}

fun ProcessingRules.mayStart(entry: QueuedCapture): Boolean = when (pause) {
    ProcessingPause.ALL -> entry.fromWatch
    ProcessingPause.LONG -> !entry.isLong
    ProcessingPause.NONE -> !waitsForPower(entry)
}

/** Held by the charger rule alone: what the plugged-in line counts and Process now approves. A capture [toServer] never waits for it. */
fun ProcessingRules.waitsForPower(entry: QueuedCapture): Boolean =
    pause == ProcessingPause.NONE && entry.isLong && longWaitsForPower && !pluggedIn && entry.id !in approved && !toServer(entry)

/** The entries of [pending] that may start now: short ones first, each group oldest first. */
fun processingOrder(pending: List<QueuedCapture>, rules: ProcessingRules): List<QueuedCapture> =
    pending.filter(rules::mayStart).sortedWith(compareBy({ it.isLong }, { it.queuedAtMillis }))

/**
 * The length of a RIFF WAVE file's audio from its `fmt ` and `data` chunks, or null for anything
 * else, a malformed header or a byte rate that disagrees with the sample rate and block size
 * included. A `data` size past the end of [bytes], or one of 0 as streaming writers leave it, is
 * read as the rest of the file.
 */
fun wavDurationMillis(bytes: ByteArray): Long? {
    if (bytes.size < 12 || !bytes.hasTag(0, "RIFF") || !bytes.hasTag(8, "WAVE")) return null
    var byteRate = 0L
    var offset = 12L
    while (offset + 8 <= bytes.size) {
        val at = offset.toInt()
        val size = bytes.u32(at + 4)
        val body = offset + 8
        when {
            bytes.hasTag(at, "fmt ") && size >= 16 && body + 16 <= bytes.size -> {
                val rate = bytes.u32(at + 12)
                val blockAlign = bytes.u32(at + 20) and 0xFFFF
                byteRate = bytes.u32(at + 16).takeIf { it > 0 && it == rate * blockAlign } ?: return null
            }
            bytes.hasTag(at, "data") -> {
                if (byteRate <= 0) return null
                val rest = bytes.size - body
                val available = if (size == 0L) rest else minOf(size, rest)
                return available * 1000 / byteRate
            }
        }
        // Chunks are padded to an even size.
        offset = body + size + (size and 1L)
    }
    return null
}

private fun ByteArray.hasTag(at: Int, tag: String): Boolean =
    at + 4 <= size && (0 until 4).all { this[at + it] == tag[it].code.toByte() }

private fun ByteArray.u32(at: Int): Long =
    (0 until 4).fold(0L) { acc, i -> acc or ((this[at + i].toLong() and 0xFF) shl (8 * i)) }
