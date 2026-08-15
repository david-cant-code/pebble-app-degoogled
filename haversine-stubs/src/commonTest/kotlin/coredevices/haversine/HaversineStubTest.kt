package coredevices.haversine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HaversineStubTest {
    // The two entry points reachable without a satellite instance must keep
    // answering "no ring here": libindex's scanner ignores an advertisement
    // whose fingerprint parses to null, and nothing may ever look like a
    // failsafe-mode ring. Anything else would let the dead Ring runtime
    // conjure a device out of arbitrary BLE manufacturer data.
    @Test
    fun noAdvertisementEverParsesToAFingerprint() {
        assertNull(KMPHaversineAdvertisement.parseToStateFingerprint(ByteArray(0)))
        assertNull(KMPHaversineAdvertisement.parseToStateFingerprint(ByteArray(32) { 0xFF.toByte() }))
    }

    @Test
    fun noFingerprintMatchesTheFailsafePattern() {
        assertFalse(fingerprintMatchesFailsafe(0L))
        assertFalse(fingerprintMatchesFailsafe(-1L))
        assertFalse(fingerprintMatchesFailsafe(Long.MAX_VALUE))
    }
}
