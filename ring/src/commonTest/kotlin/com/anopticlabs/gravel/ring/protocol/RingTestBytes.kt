package com.anopticlabs.gravel.ring.protocol

fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

fun u16(value: Int): ByteArray = bytes(value, value ushr 8)

fun u32(value: Long): ByteArray = ByteArray(4).also { it.putU32(0, value) }

/** A record with the length width the parser expects for [tag]. */
fun record(tag: Int, payload: ByteArray): ByteArray {
    val length = if (tag == 0x50 || tag == 0x51) u32(payload.size.toLong()) else u16(payload.size)
    return bytes(tag) + length + payload
}

/** A collection in the form the ring sends: a 24-bit length of the whole collection and a zero byte. */
fun collection(vararg records: ByteArray): ByteArray {
    val body = records.fold(ByteArray(0)) { all, next -> all + next }
    val size = body.size + 4
    return bytes(size, size ushr 8, size ushr 16, 0) + body
}

fun compressedRecord(config: Int, bitCount: Long, rate: Long, stream: ByteArray): ByteArray =
    record(0x51, bytes(config) + u32(bitCount) + u32(rate) + stream)

fun partRecord(startIndex: Long, final: Boolean): ByteArray = record(0x52, u32(startIndex) + bytes(1, if (final) 1 else 0))

/** Bits as a string of 0 and 1, packed most significant bit first, with the count of bits. */
fun bitString(bits: String): Pair<Long, ByteArray> {
    val clean = bits.filter { it == '0' || it == '1' }
    val out = ByteArray((clean.length + 7) / 8)
    clean.forEachIndexed { i, c -> if (c == '1') out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
    return clean.length.toLong() to out
}

/**
 * Encodes [words] (values of 16 minus the dropped bits) the way [decodeRingAudio] reads them: the inverse of
 * this client's decoder, written for round trips, not a reference for the ring's own encoder.
 */
fun encodeRingAudio(config: Int, words: IntArray): Pair<Long, ByteArray> {
    val escapeAt = config ushr 4 and 0xF
    val width = 16 - (config and 0xF)
    val modulus = 1 shl width
    val bits = StringBuilder()
    var delta = 0
    var word = 0
    for (next in words) {
        val nextDelta = (next - word) and (modulus - 1)
        val correction = (nextDelta - delta) and (modulus - 1)
        val signed = if (correction >= modulus / 2) correction - modulus else correction
        val magnitude = kotlin.math.abs(signed)
        when {
            correction == 0 -> bits.append('1')
            magnitude in 1..<escapeAt -> bits.append('0').append("0".repeat(magnitude - 1)).append('1').append(if (signed < 0) '1' else '0')
            else -> bits.append('0').append("0".repeat(maxOf(escapeAt - 1, 0))).append(correction.toString(2).padStart(width, '0'))
        }
        delta = nextDelta
        word = next
    }
    return bitString(bits.toString())
}

/** A deterministic sequence of small steps with occasional jumps across the whole range. */
fun testWords(count: Int, width: Int, seed: Long): IntArray {
    var state = seed
    var word = 0
    val mask = (1 shl width) - 1
    return IntArray(count) {
        state = state * 6364136223846793005L + 1442695040888963407L
        val roll = (state ushr 33).toInt()
        word = if (roll % 9 == 0) roll ushr 3 and mask else (word + roll % 7 - 3) and mask
        word
    }
}
