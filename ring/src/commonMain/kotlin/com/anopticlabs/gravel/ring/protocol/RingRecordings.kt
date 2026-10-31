package com.anopticlabs.gravel.ring.protocol

import kotlin.math.abs

/** A finished recording: signed 16-bit [samples] at [sampleRate] Hz with their mean subtracted. */
class RingRecording internal constructor(val capturedAtUnixSeconds: Long?, val sampleRate: Int, val samples: ShortArray) {
    val durationMillis: Long get() = samples.size * 1000L / sampleRate
}

enum class RingRejection { INCOMPLETE, MIXED_SAMPLE_RATES, TOO_LONG }

/**
 * Collections grouped into recordings, each listed with the indexes of the collections it was made of:
 * [finished] recordings, [pending] collections still waiting for their final part, and [rejected] groups.
 */
class RingAssembly(
    val finished: List<Pair<List<Int>, RingRecording>>,
    val pending: List<Int>,
    val rejected: List<Pair<List<Int>, RingRejection>>,
)

/**
 * Groups [collections], each with its collection index and in the order they were read, into recordings. A
 * recording's parts sit at consecutive indexes and share a start index, and its final part closes it; a group
 * that the next collection does not continue is rejected as incomplete, and the group still open at the end
 * is pending. Parts whose sample rates differ from the first part's by more than 1% are rejected, else the
 * first part's rate is used. A recording longer than [maxDurationMillis] is rejected before its samples are
 * joined; the first part dates it.
 */
fun assembleRingRecordings(collections: List<Pair<Int, RingCollection>>, maxDurationMillis: Long): RingAssembly {
    require(maxDurationMillis in 0..Int.MAX_VALUE * 1000L / MAX_RING_SAMPLE_RATE) { "$maxDurationMillis ms does not fit one array" }
    val finished = mutableListOf<Pair<List<Int>, RingRecording>>()
    val rejected = mutableListOf<Pair<List<Int>, RingRejection>>()
    var group = mutableListOf<Pair<Int, RingCollection>>()
    for (entry in collections) {
        val (index, collection) = entry
        require(index in 0..0xFFFF) { "collection index $index is not 16-bit" }
        val last = group.lastOrNull()
        if (last != null && (index != nextRingIndex(last.first) || collection.startIndex != last.second.startIndex)) {
            rejected += group.map { it.first } to RingRejection.INCOMPLETE
            group = mutableListOf()
        }
        group += entry
        if (!collection.final) continue
        val indexes = group.map { it.first }
        val parts = group.map { it.second }
        group = mutableListOf()
        val rate = parts.first().audio.sampleRate.toLong()
        val samples = parts.sumOf { it.audio.samples.size.toLong() }
        when {
            parts.any { abs(it.audio.sampleRate - rate) * 100 > rate } -> rejected += indexes to RingRejection.MIXED_SAMPLE_RATES
            samples * 1000 > maxDurationMillis * rate -> rejected += indexes to RingRejection.TOO_LONG
            else -> finished += indexes to joined(parts, rate.toInt(), samples.toInt())
        }
    }
    return RingAssembly(finished, group.map { it.first }, rejected)
}

private fun joined(parts: List<RingCollection>, rate: Int, size: Int): RingRecording {
    var sum = 0L
    for (part in parts) for (sample in part.audio.samples) sum += sample
    val mean = if (size == 0) 0 else (sum / size).toInt()
    val samples = ShortArray(size)
    var i = 0
    for (part in parts) {
        for (sample in part.audio.samples) {
            samples[i++] = (sample - mean).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
    return RingRecording(parts.first().capturedAtUnixSeconds, rate, samples)
}
