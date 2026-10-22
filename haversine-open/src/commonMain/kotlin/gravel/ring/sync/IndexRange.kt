package gravel.ring.sync

/**
 * Pending collection indices inside a wrapping u16 window.
 *
 * [stored] is the last fully handled index, inclusive. null starts at
 * [start]. A stored index that sits in the gap (the following index is not
 * in the window either) transfers the whole window with rollover set.
 * A window that contains both 65535 and 0 is split; the second segment
 * starts at 0 with rollover set.
 */
internal fun pendingIndices(start: Int, end: Int, stored: Int?): List<IndexSegment> {
    val all = walk(start, end)
    if (all.isEmpty()) return emptyList()
    val slice: List<Int>
    val gap: Boolean
    if (stored == null) {
        slice = all
        gap = false
    } else {
        val next = (stored + 1) and 0xFFFF
        val at = all.indexOf(next)
        if (at >= 0) {
            slice = all.subList(at, all.size)
            gap = false
        } else if (next == (end and 0xFFFF)) {
            return emptyList()
        } else {
            slice = all
            gap = true
        }
    }
    if (slice.isEmpty()) return emptyList()
    return splitWrap(slice, firstRollover = gap)
}

internal class IndexSegment(
    val rollover: Boolean,
    val indices: List<Int>,
) {
    val range: IntRange get() = indices.first()..indices.last()
}

private fun walk(start: Int, end: Int): List<Int> {
    val from = start and 0xFFFF
    val to = end and 0xFFFF
    if (from == to) return emptyList()
    val out = ArrayList<Int>(16)
    var i = from
    while (i != to) {
        out += i
        i = (i + 1) and 0xFFFF
        if (out.size > 65535) break
    }
    return out
}

private fun splitWrap(indices: List<Int>, firstRollover: Boolean): List<IndexSegment> {
    val out = ArrayList<IndexSegment>()
    var current = ArrayList<Int>()
    var rollover = firstRollover
    for (index in indices) {
        if (current.isNotEmpty() && index < current.last()) {
            out += IndexSegment(rollover, current.toList())
            current = ArrayList()
            rollover = true
        }
        current += index
    }
    if (current.isNotEmpty()) out += IndexSegment(rollover, current.toList())
    return out
}
