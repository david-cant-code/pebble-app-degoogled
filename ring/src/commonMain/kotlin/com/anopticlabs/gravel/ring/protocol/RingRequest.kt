package com.anopticlabs.gravel.ring.protocol

const val REQUEST_SIZE = 13

private const val READ = 3
private const val WRITE = 2

private const val RANGE_ADDRESS = 0x4003_0005L
private const val COLLECTION_BASE = 0x4002_0000L
private const val ADVERTISEMENT_ADDRESS = 0x4003_000EL
private const val CLOCK_ADDRESS = 0x4003_0001L

/** The largest collection the ring stores: one flash page. */
const val MAX_COLLECTION_SIZE = 4096

/**
 * A request on the ring's control channel. Only the requests sync needs can be built: reads of the stored
 * range, a collection and the advertisement, and the one write, which sets the ring's clock. [maxDataLength]
 * bounds the bytes a reply to a read may carry; a write's [payload] follows it on the data channel.
 */
sealed class RingRequest(
    private val type: Int,
    val address: Long,
    val length: Long,
    val maxDataLength: Int,
    val payload: ByteArray = ByteArray(0),
) {
    data object ReadRange : RingRequest(READ, RANGE_ADDRESS, length = 4, maxDataLength = 4)

    /** The whole collection at [index], 0 to 65535 (a read of length 0 is the whole object). */
    class ReadCollection(val index: Int) :
        RingRequest(READ, collectionAddress(index), length = 0, maxDataLength = MAX_COLLECTION_SIZE) {
        override fun toString() = "ReadCollection($index)"
    }

    data object ReadAdvertisement : RingRequest(READ, ADVERTISEMENT_ADDRESS, length = 10, maxDataLength = 10)

    class SetClock private constructor(val unixSeconds: Long, payload: ByteArray) :
        RingRequest(WRITE, CLOCK_ADDRESS, length = 4, maxDataLength = 0, payload) {
        override fun toString() = "SetClock($unixSeconds)"

        companion object {
            fun at(unixSeconds: Long): SetClock {
                require(unixSeconds in 0..U32_MAX) { "the ring's clock holds unsigned 32-bit seconds" }
                return SetClock(unixSeconds, ByteArray(4).also { it.putU32(0, unixSeconds) })
            }
        }
    }

    val isWrite: Boolean get() = type == WRITE

    fun encode(): ByteArray = ByteArray(REQUEST_SIZE).also {
        it[0] = type.toByte()
        it.putU32(1, address)
        it.putU32(5, 0)
        it.putU32(9, length)
    }
}

private fun collectionAddress(index: Int): Long {
    require(index in 0..0xFFFF) { "collection index $index is not 16-bit" }
    return COLLECTION_BASE or index.toLong()
}
