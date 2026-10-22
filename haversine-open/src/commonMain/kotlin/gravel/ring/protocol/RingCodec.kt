package gravel.ring.protocol

internal enum class RingChar { Control, Data, SystemInput }

internal sealed class RingRequest(
    val type: Int,
    val address: UInt,
    val offset: UInt,
    val length: UInt,
) {
    class Read(address: UInt, length: UInt = 0u, offset: UInt = 0u) : RingRequest(3, address, offset, length)
    class Write(address: UInt, val payload: ByteArray) : RingRequest(2, address, 0u, payload.size.toUInt())
    class Erase(address: UInt, length: UInt) : RingRequest(1, address, 0u, length)
    class Cancel(address: UInt, offset: UInt, length: UInt) : RingRequest(4, address, offset, length) {
        constructor(of: RingRequest) : this(of.address, of.offset, of.length)
    }

    fun encode(): ByteArray = ByteArray(13).also {
        it[0] = type.toByte()
        it.putU32(1, address.toLong())
        it.putU32(5, offset.toLong())
        it.putU32(9, length.toLong())
    }

    companion object {
        const val SIZE = 13
    }
}

internal class RingReply(
    val status: UInt,
    val reserved: UInt,
    val dataLength: UInt,
) {
    companion object {
        const val SIZE = 12

        fun decode(bytes: ByteArray): RingReply {
            require(bytes.size >= SIZE)
            return RingReply(
                status = bytes.u32(0),
                reserved = bytes.u32(4),
                dataLength = bytes.u32(8),
            )
        }
    }
}

private fun ByteArray.putU32(at: Int, value: Long) {
    this[at] = value.toByte()
    this[at + 1] = (value shr 8).toByte()
    this[at + 2] = (value shr 16).toByte()
    this[at + 3] = (value shr 24).toByte()
}

private fun ByteArray.u32(at: Int): UInt =
    ((this[at].toLong() and 0xFF) or
        ((this[at + 1].toLong() and 0xFF) shl 8) or
        ((this[at + 2].toLong() and 0xFF) shl 16) or
        ((this[at + 3].toLong() and 0xFF) shl 24)).toUInt()

internal object MemoryMap {
    const val RANGE = 0x4003_0005u
    const val COLLECTION_BASE = 0x4002_0000u
    const val ADVERT = 0x4003_000Eu
    const val CLOCK = 0x4003_0001u
    const val VERSIONS = 0x4003_0006u
    const val HARDWARE_SERIAL = 0x4003_0008u
    const val PROGRAMMED_SERIAL = 0x4000_0004u
    const val RX_RSSI = 0x4003_000Fu
    const val ERASE_ALL = 0x4003_0000u
    const val FAILSAFE_IMAGE = 0x4006_0000u

    const val MAX_REPLY_BYTES = 8 * 1024

    fun collection(index: Int): UInt = COLLECTION_BASE or (index and 0xFFFF).toUInt()
}

/**
 * Real erase of `0x40030000` was not captured. It stays off unless a test
 * or a later capture turns this on. `0x40060000` is never allowed.
 */
internal object RingFeatureFlags {
    var allowUncapturedErase: Boolean = false
}

internal class AddressNotAllowed(
    val address: UInt,
    val op: Int,
) : IllegalArgumentException("address 0x${address.toString(16)} op $op is outside v1")

internal fun RingRequest.allowed(): Boolean = addressAllowed(type, address)

internal fun addressAllowed(type: Int, address: UInt): Boolean {
    if (address == MemoryMap.FAILSAFE_IMAGE) return false
    if (type == 4) {
        return addressAllowed(3, address) || addressAllowed(2, address) || addressAllowed(1, address)
    }
    val collection = address and 0xFFFF_0000u == MemoryMap.COLLECTION_BASE
    return when (type) {
        3 -> when (address) {
            MemoryMap.RANGE, MemoryMap.ADVERT, MemoryMap.VERSIONS,
            MemoryMap.HARDWARE_SERIAL, MemoryMap.PROGRAMMED_SERIAL, MemoryMap.RX_RSSI,
            -> true
            else -> collection
        }
        2 -> address == MemoryMap.CLOCK
        1 -> address == MemoryMap.ERASE_ALL && RingFeatureFlags.allowUncapturedErase
        else -> false
    }
}

internal open class RingStatusException(val status: UInt, val address: UInt) :
    Exception("ring status 0x${status.toString(16)} at 0x${address.toString(16)}")

internal class RingMissingDataException(status: UInt, address: UInt) : RingStatusException(status, address)

internal class RingCancelledByWrite(address: UInt) : RingStatusException(0x42u, address)

internal class RingRequestTimeout(val address: UInt, cause: Throwable) :
    Exception("timed out at 0x${address.toString(16)}", cause)
