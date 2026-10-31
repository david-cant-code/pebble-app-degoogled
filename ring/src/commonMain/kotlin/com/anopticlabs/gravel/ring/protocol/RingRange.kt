package com.anopticlabs.gravel.ring.protocol

private const val INDEX_MASK = 0xFFFF

/**
 * The collections a ring holds: indexes from [start] up to, not including, [end], counting modulo 65536. The
 * ring keeps collections after a sync, so the phone keeps its own position.
 */
@ConsistentCopyVisibility
data class RingRange internal constructor(val start: Int, val end: Int) {
    val size: Int get() = (end - start) and INDEX_MASK

    /**
     * The index a sync resumes at: [lastIndex], the index after the last collection stored, while it lies in
     * the range or at its end, else [start], as after the ring wrapped past it.
     */
    fun resumeAt(lastIndex: Int?): Int {
        if (lastIndex == null) return start
        require(lastIndex in 0..INDEX_MASK) { "collection index $lastIndex is not 16-bit" }
        return if (((lastIndex - start) and INDEX_MASK) <= size) lastIndex else start
    }
}

fun nextRingIndex(index: Int): Int = (index + 1) and INDEX_MASK

/** The answer to [RingRequest.ReadRange]. */
fun parseRingRange(data: ByteArray): RingRange {
    if (data.size != 4) throw RingProtocolException("a range of ${data.size} bytes")
    return RingRange(data.u16(0), data.u16(2))
}
