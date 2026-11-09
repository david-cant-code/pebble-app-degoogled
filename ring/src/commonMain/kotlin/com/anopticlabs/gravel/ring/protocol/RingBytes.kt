package com.anopticlabs.gravel.ring.protocol

/** Bytes from the ring that do not have the shape the protocol gives them. */
class RingProtocolException(message: String) : Exception(message)

// The ring's integers are little-endian.

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

internal fun ByteArray.u24(at: Int): Int = u16(at) or (u8(at + 2) shl 16)

internal fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)

internal fun ByteArray.putU32(at: Int, value: Long) {
    for (i in 0..3) this[at + i] = (value ushr (8 * i)).toByte()
}

internal const val U32_MAX = 0xFFFF_FFFFL
