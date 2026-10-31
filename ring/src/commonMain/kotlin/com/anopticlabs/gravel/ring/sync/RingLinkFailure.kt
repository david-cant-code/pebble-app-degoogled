package com.anopticlabs.gravel.ring.sync

/** What a failed connection to the ring calls for. */
enum class RingLinkFailure { RETRY, PAIR_AGAIN, OTHER }

private const val GATT_ERROR = 133
private const val BOND_LOST = 137
private const val INSUFFICIENT_AUTHENTICATION = 5
private const val INSUFFICIENT_ENCRYPTION = 15

/**
 * Reads the status Android reported for a failed link, from an operation or from the disconnect (the
 * description does not say which): 133 is worth retrying, a lost bond or failed authentication needs pairing
 * again.
 */
fun ringLinkFailure(status: Int?): RingLinkFailure = when (status) {
    GATT_ERROR -> RingLinkFailure.RETRY
    BOND_LOST, INSUFFICIENT_AUTHENTICATION, INSUFFICIENT_ENCRYPTION -> RingLinkFailure.PAIR_AGAIN
    else -> RingLinkFailure.OTHER
}
