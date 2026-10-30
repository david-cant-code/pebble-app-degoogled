package com.anopticlabs.gravel.notebook.capture

import com.anopticlabs.gravel.notebook.pipeline.CAPTURE_SAMPLE_RATE
import com.anopticlabs.gravel.notebook.pipeline.MAX_CAPTURE_MILLIS

/** A recording shorter than this is discarded as too short to hold speech. */
const val MIN_RECORDING_MILLIS = 1_000

const val MAX_RECORDING_SAMPLES = MAX_CAPTURE_MILLIS / 1000 * CAPTURE_SAMPLE_RATE

private const val MIN_RECORDING_SAMPLES = MIN_RECORDING_MILLIS / 1000 * CAPTURE_SAMPLE_RATE

/** The size of the header [RecordingBuffer.toWav] and [pcm16Wav] write ahead of the samples. */
const val WAV_HEADER_BYTES = 44

/**
 * A phone recording held in memory as mono PCM16 at [CAPTURE_SAMPLE_RATE], taking samples up to
 * [maxSamples] and none after; [toWav] makes the file the capture queue stores.
 */
class RecordingBuffer(private val maxSamples: Int = MAX_RECORDING_SAMPLES) {
    private val chunks = ArrayList<ByteArray>()

    var samples: Int = 0
        private set

    val isFull: Boolean get() = samples >= maxSamples

    val isTooShort: Boolean get() = samples < MIN_RECORDING_SAMPLES

    val millis: Long get() = samples * 1000L / CAPTURE_SAMPLE_RATE

    /** Takes the first [count] of [pcm], or as many as fit under the cap; returns how many it took. */
    fun append(pcm: ShortArray, count: Int): Int {
        require(count in 0..pcm.size) { "count $count outside 0..${pcm.size}" }
        val taken = minOf(count, maxSamples - samples)
        if (taken <= 0) return 0
        val bytes = ByteArray(taken * 2)
        for (i in 0 until taken) {
            val s = pcm[i].toInt()
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        chunks += bytes
        samples += taken
        return taken
    }

    /** A canonical RIFF WAVE file: a 44-byte header, then the samples little-endian. */
    fun toWav(): ByteArray {
        val out = wavWithHeader(samples * 2, CAPTURE_SAMPLE_RATE)
        var at = WAV_HEADER_BYTES
        for (chunk in chunks) {
            chunk.copyInto(out, at)
            at += chunk.size
        }
        return out
    }
}

/** [pcm], mono 16-bit little-endian samples at [sampleRate], as a canonical RIFF WAVE file. */
fun pcm16Wav(pcm: ByteArray, sampleRate: Int): ByteArray {
    require(pcm.size % 2 == 0) { "PCM16 of ${pcm.size} bytes" }
    return wavWithHeader(pcm.size, sampleRate).also { pcm.copyInto(it, WAV_HEADER_BYTES) }
}

/** A file of the right size whose first [WAV_HEADER_BYTES] are the header for [dataBytes] of mono PCM16. */
private fun wavWithHeader(dataBytes: Int, sampleRate: Int): ByteArray {
    val out = ByteArray(WAV_HEADER_BYTES + dataBytes)
    var at = 0
    fun ascii(text: String) = text.forEach { out[at++] = it.code.toByte() }
    fun int32(v: Int) = repeat(4) { out[at++] = (v shr (8 * it)).toByte() }
    fun int16(v: Int) = repeat(2) { out[at++] = (v shr (8 * it)).toByte() }
    ascii("RIFF")
    int32(36 + dataBytes)
    ascii("WAVE")
    ascii("fmt ")
    int32(16)
    int16(1)
    int16(1)
    int32(sampleRate)
    int32(sampleRate * 2)
    int16(2)
    int16(16)
    ascii("data")
    int32(dataBytes)
    return out
}
