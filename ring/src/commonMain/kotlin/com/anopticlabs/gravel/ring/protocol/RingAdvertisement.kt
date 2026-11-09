package com.anopticlabs.gravel.ring.protocol

/** The company id the ring's manufacturer data is advertised under. */
const val RING_COMPANY_ID = 0x0EEA

private const val MANUFACTURER_DATA_SIZE = 6
private const val FAILSAFE_IMAGE = 0xDEADDEADL
private const val FACTORY_TEST_IMAGE = 0xDEADBEEFL
private const val NEEDS_SERVICING = 0x40
private const val RECORDING = 0x20

/**
 * What a ring advertises. [collectionCount] is the low 8 bits of its collection counter, so a change means
 * new collections; [needsServicing] asks for its clock to be set; [recording] is set while its button is held.
 */
data class RingAdvertisement(
    val fingerprint: Long,
    val collectionCount: Int,
    val needsServicing: Boolean,
    val recording: Boolean,
) {
    /** False for a ring running its failsafe or factory-test image. */
    val pairable: Boolean get() = fingerprint != FAILSAFE_IMAGE && fingerprint != FACTORY_TEST_IMAGE

    val factoryTestImage: Boolean get() = fingerprint == FACTORY_TEST_IMAGE
}

/** The advertisement in [data], the manufacturer data without its company id, or null when it is too short. */
fun parseRingAdvertisement(data: ByteArray): RingAdvertisement? {
    if (data.size < MANUFACTURER_DATA_SIZE) return null
    val flags = data.u8(5)
    return RingAdvertisement(
        fingerprint = data.u32(0),
        collectionCount = data.u8(4),
        needsServicing = (flags and NEEDS_SERVICING) != 0,
        recording = (flags and RECORDING) != 0,
    )
}

/** The answer to [RingRequest.ReadAdvertisement]: the advertisement as one manufacturer-data AD structure. */
fun parseRingAdvertisementRead(data: ByteArray): RingAdvertisement {
    val size = 4 + MANUFACTURER_DATA_SIZE
    if (data.size != size || data.u8(0) != size - 1 || data.u8(1) != 0xFF || data.u16(2) != RING_COMPANY_ID) {
        throw RingProtocolException("the advertisement read is not the ring's manufacturer data")
    }
    return parseRingAdvertisement(data.copyOfRange(4, size))!!
}
