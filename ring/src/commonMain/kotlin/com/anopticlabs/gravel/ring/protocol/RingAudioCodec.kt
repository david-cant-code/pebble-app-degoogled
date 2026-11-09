package com.anopticlabs.gravel.ring.protocol

/**
 * Decodes the ring's compressed audio into unsigned 16-bit samples. [config]'s high nibble is the escape
 * threshold, its low nibble the low bits dropped from every sample; [bitCount] bits of [stream] from [from]
 * follow, most significant bit of each byte first. Each code is a correction to the running difference
 * between samples: a 1 bit for none, a unary magnitude with a sign bit, or an escape followed by the raw
 * value. State starts at zero for every call.
 */
internal fun decodeRingAudio(config: Int, bitCount: Long, stream: ByteArray, from: Int, to: Int): IntArray {
    if (bitCount > (to - from).toLong() * 8) throw RingProtocolException("$bitCount audio bits in ${to - from} bytes")
    val escapeAt = config ushr 4 and 0xF
    val dropped = config and 0xF
    val width = 16 - dropped
    val mask = (1 shl width) - 1
    val bits = BitReader(stream, from, bitCount)
    val samples = IntArray(bitCount.toInt())
    var count = 0
    var delta = 0
    var word = 0
    while (bits.remaining > 0) {
        val correction = if (bits.next() == 1) {
            0
        } else {
            var n = 0
            var escaped = false
            while (true) {
                if (escapeAt <= n + 1) {
                    escaped = true
                    break
                }
                n++
                if (bits.next() == 1) break
            }
            when {
                escaped -> bits.next(width)
                bits.next() == 1 -> -n and mask
                else -> n and mask
            }
        }
        delta = (delta + correction) and mask
        word = (word + delta) and mask
        samples[count++] = word shl dropped
    }
    return samples.copyOf(count)
}

private class BitReader(private val bytes: ByteArray, private val from: Int, private val bitCount: Long) {
    private var position = 0L

    val remaining: Long get() = bitCount - position

    fun next(): Int {
        if (position >= bitCount) throw RingProtocolException("the audio bitstream ends inside a code")
        val byte = bytes[from + (position ushr 3).toInt()].toInt()
        val bit = byte ushr (7 - (position and 7).toInt()) and 1
        position++
        return bit
    }

    /** [count] bits as one value, the first bit read the most significant. */
    fun next(count: Int): Int {
        var value = 0
        repeat(count) { value = value shl 1 or next() }
        return value
    }
}
