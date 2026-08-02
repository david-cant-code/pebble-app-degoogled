package coredevices.coreapp.util

import io.rebble.libpebblecommon.pebblekit.two.PebbleKitWatchIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Exercises the real Android Keystore HMAC path.
 *
 * The failure mode this guards is quiet: if key generation is rejected on a device, every
 * identifier comes back null, the provider drops every row, and connected watches simply stop
 * being visible to companion apps with nothing logged at the call site.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.PebbleKitWatchIdentityTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class PebbleKitWatchIdentityTest {

    @Test
    fun derivesAnIdentifier() {
        val identifier = PebbleKitWatchIdentity().pseudonymFor(PACKAGE_A, SERIAL)

        assertNotNull(identifier, "Keystore HMAC produced no identifier")
        assertEquals(32, identifier.length, "expected 128 bits rendered as hex")
    }

    @Test
    fun neverReturnsTheSerial() {
        val identifier = PebbleKitWatchIdentity().pseudonymFor(PACKAGE_A, SERIAL)

        assertNotEquals(SERIAL, identifier)
        assertFalse(identifier!!.contains(SERIAL))
    }

    @Test
    fun isStableForTheSameCallerAndWatch() {
        // A companion app has to be able to recognise the same watch across calls and restarts.
        assertEquals(
            PebbleKitWatchIdentity().pseudonymFor(PACKAGE_A, SERIAL),
            PebbleKitWatchIdentity().pseudonymFor(PACKAGE_A, SERIAL),
        )
    }

    @Test
    fun differsBetweenCallers() {
        // The whole point: two apps comparing notes must not find a shared key for one watch.
        assertNotEquals(
            PebbleKitWatchIdentity().pseudonymFor(PACKAGE_A, SERIAL),
            PebbleKitWatchIdentity().pseudonymFor(PACKAGE_B, SERIAL),
        )
    }

    @Test
    fun differsBetweenWatches() {
        val identity = PebbleKitWatchIdentity()

        assertNotEquals(
            identity.pseudonymFor(PACKAGE_A, SERIAL),
            identity.pseudonymFor(PACKAGE_A, "QQQ456"),
        )
    }

    @Test
    fun resolvesItsOwnIdentifierBackToTheSerial() {
        val identity = PebbleKitWatchIdentity()
        val identifier = identity.pseudonymFor(PACKAGE_A, SERIAL)!!

        assertEquals(SERIAL, identity.resolveSerial(PACKAGE_A, identifier, listOf(SERIAL, "QQQ456")))
    }

    @Test
    fun doesNotResolveAnotherCallersIdentifier() {
        val identity = PebbleKitWatchIdentity()
        val forB = identity.pseudonymFor(PACKAGE_B, SERIAL)!!

        assertNull(identity.resolveSerial(PACKAGE_A, forB, listOf(SERIAL)))
    }

    @Test
    fun stillAcceptsARealSerial() {
        // Emitting a serial is the leak; accepting one back teaches a caller nothing new, and
        // rejecting it would break callers that already hold one.
        val identity = PebbleKitWatchIdentity()

        assertEquals(SERIAL, identity.resolveSerial(PACKAGE_A, SERIAL, listOf(SERIAL)))
    }

    @Test
    fun doesNotResolveAnUnknownIdentifier() {
        val identity = PebbleKitWatchIdentity()

        assertNull(identity.resolveSerial(PACKAGE_A, "nonsense", listOf(SERIAL)))
    }

    private companion object {
        const val PACKAGE_A = "com.example.companion.a"
        const val PACKAGE_B = "com.example.companion.b"
        const val SERIAL = "ABC123456789"
    }
}
