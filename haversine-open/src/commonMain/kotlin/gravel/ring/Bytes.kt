package gravel.ring

internal fun ByteArray.u16le(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u32le(at: Int): Long =
    (this[at].toLong() and 0xFF) or
        ((this[at + 1].toLong() and 0xFF) shl 8) or
        ((this[at + 2].toLong() and 0xFF) shl 16) or
        ((this[at + 3].toLong() and 0xFF) shl 24)

internal fun ByteArray.putU32le(at: Int, value: Long) {
    this[at] = value.toByte()
    this[at + 1] = (value shr 8).toByte()
    this[at + 2] = (value shr 16).toByte()
    this[at + 3] = (value shr 24).toByte()
}

internal fun normalizeRingId(id: String): String = id.replace(":", "").uppercase()

internal fun ringIdsEqual(a: String, b: String): Boolean = normalizeRingId(a) == normalizeRingId(b)

internal class ByteSink {
    private var buf = ByteArray(32)
    var size: Int = 0
        private set

    fun append(bytes: ByteArray) {
        ensure(size + bytes.size)
        bytes.copyInto(buf, size)
        size += bytes.size
    }

    fun snapshot(): ByteArray = buf.copyOf(size)

    operator fun get(index: Int): Byte = buf[index]

    private fun ensure(needed: Int) {
        if (needed <= buf.size) return
        var next = buf.size
        while (next < needed) next *= 2
        buf = buf.copyOf(next)
    }
}
