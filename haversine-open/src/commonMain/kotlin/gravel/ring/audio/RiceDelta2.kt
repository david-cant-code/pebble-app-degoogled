package gravel.ring.audio

import coredevices.haversine.DataDecodeException
import gravel.ring.u32le

internal class MsbBitReader(
    private val data: ByteArray,
    private val start: Int,
    private val limit: Long,
) {
    private var index = start
    private var bit = 0
    private var consumed = 0L
    val remaining: Long get() = limit - consumed

    fun next(): Int {
        if (consumed >= limit || index >= data.size) {
            throw DataDecodeException("bitstream overrun", data)
        }
        val value = (data[index].toInt() ushr (7 - bit)) and 1
        bit++
        if (bit == 8) {
            bit = 0
            index++
        }
        consumed++
        return value
    }

    fun take(count: Int): Int {
        var value = 0
        repeat(count) { value = (value shl 1) or next() }
        return value
    }
}

internal class DecodedAudio(
    val samples: ShortArray,
    val sampleRate: UInt,
)

/**
 * Tag `0x51`, written from issue #23 section 7. Samples are re-centred by
 * subtracting 0x8000 so the rest of the app can treat them as signed PCM.
 * `removeDCBias` still runs on the joined recording.
 */
internal fun decodeAudioRecord(payload: ByteArray): DecodedAudio {
    if (payload.size < 9) throw DataDecodeException("audio header short", payload)
    val config = payload[0].toInt() and 0xFF
    val escape = (config ushr 4) and 0xF
    val dropped = config and 0xF
    val bitCount = payload.u32le(1)
    val rate = payload.u32le(5).toUInt()
    if (bitCount > (payload.size - 9) * 8L) {
        throw DataDecodeException("bit count past end", payload)
    }
    val width = 16 - dropped
    val mask = (1 shl width) - 1
    val bits = MsbBitReader(payload, start = 9, limit = bitCount)
    val out = ArrayList<Short>()
    var delta = 0
    var word = 0
    while (bits.remaining > 0) {
        val value = if (bits.next() == 1) {
            0
        } else {
            var run = 0
            var escaped = false
            while (true) {
                if (escape <= run + 1) {
                    escaped = true
                    break
                }
                val bit = bits.next()
                run++
                if (bit == 1) break
            }
            if (escaped) bits.take(width) else if (bits.next() == 1) (mask + 1 - run) and mask else run
        }
        delta = (delta + value) and mask
        word = (word + delta) and mask
        out += ((word shl dropped) - 0x8000).toShort()
    }
    return DecodedAudio(out.toShortArray(), rate)
}
