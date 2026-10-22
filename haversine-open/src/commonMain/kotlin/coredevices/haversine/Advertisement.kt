package coredevices.haversine

import gravel.ring.u32le
import kotlin.time.Instant

/**
 * Manufacturer payload with the company id already removed, as Kable and
 * libindex's scanner pass it. Shorter than 4 bytes is not a fingerprint.
 *
 * ASSUMPTION: 4 or 5 bytes still yield a fingerprint; the count and flag
 * bits stay unknown until byte 4 and byte 5 are present.
 */
class KMPHaversineAdvertisement internal constructor(
    val rssi: Float,
    val timestamp: Instant,
    val fingerprint: Long,
    val collectionCount: Int,
    val needsServicing: Boolean,
    val recordingInProgress: Boolean,
) {
    companion object {
        fun parseToStateFingerprint(data: ByteArray): Long? {
            if (data.size < 4) return null
            return data.u32le(0)
        }
    }
}

fun fingerprintMatchesFailsafe(fingerprint: Long): Boolean =
    (fingerprint and 0xFFFF_FFFFL) == FAILSAFE_FINGERPRINT

fun fingerprintMatchesPTF(fingerprint: Long): Boolean =
    (fingerprint and 0xFFFF_FFFFL) == PTF_FINGERPRINT

internal const val FAILSAFE_FINGERPRINT = 0xDEAD_DEADL
internal const val PTF_FINGERPRINT = 0xDEAD_BEEFL

/**
 * ASSUMPTION: a 10-byte memory image is `09 FF EA 0E` plus the 6-byte
 * advert state, so the flag byte is at index 9. A bare 6-byte state keeps
 * the flag byte at index 5. TODO: compare with a `0x4003000E` read.
 */
internal fun memoryFlagByte(bytes: ByteArray): Int = when {
    bytes.size >= 10 && bytes[0] == 0x09.toByte() && bytes[1] == 0xFF.toByte() -> bytes[9].toInt() and 0xFF
    bytes.size >= 6 -> bytes[5].toInt() and 0xFF
    else -> 0
}

internal fun memoryRecording(bytes: ByteArray): Boolean = memoryFlagByte(bytes) and (1 shl 5) != 0

internal fun memoryNeedsServicing(bytes: ByteArray): Boolean = memoryFlagByte(bytes) and (1 shl 6) != 0

internal fun parseAdvertisement(
    payload: ByteArray,
    rssi: Float,
    timestamp: Instant,
): KMPHaversineAdvertisement? {
    val fingerprint = KMPHaversineAdvertisement.parseToStateFingerprint(payload) ?: return null
    val count = if (payload.size >= 5) payload[4].toInt() and 0xFF else 0
    val flags = if (payload.size >= 6) payload[5].toInt() and 0xFF else 0
    return KMPHaversineAdvertisement(
        rssi = rssi,
        timestamp = timestamp,
        fingerprint = fingerprint,
        collectionCount = count,
        needsServicing = flags and (1 shl 6) != 0,
        recordingInProgress = flags and (1 shl 5) != 0,
    )
}
