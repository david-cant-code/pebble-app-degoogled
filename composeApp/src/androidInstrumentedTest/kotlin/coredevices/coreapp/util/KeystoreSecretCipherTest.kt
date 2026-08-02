package coredevices.coreapp.util

import coredevices.util.security.KeystoreSecretCipher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Exercises the real Android Keystore. The unit tests for the storage wrapper run against a fake
 * cipher, so without this nothing would catch a Keystore misconfiguration (wrong block mode,
 * padding, or IV framing) until it silently failed to protect a real user's account token.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.KeystoreSecretCipherTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class KeystoreSecretCipherTest {

    @Test
    fun roundTripsASecret() {
        val cipher = KeystoreSecretCipher()

        val encrypted = cipher.encrypt(SECRET)

        assertNotEquals(SECRET, encrypted)
        assertEquals(SECRET, cipher.decrypt(encrypted!!))
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val encrypted = KeystoreSecretCipher().encrypt(SECRET)

        assertFalse(encrypted!!.contains(SECRET), "ciphertext leaked the plaintext")
    }

    @Test
    fun encryptingTwiceProducesDifferentCiphertexts() {
        // GCM reuses of an IV are catastrophic, so the per-operation IV must actually vary.
        // Identical outputs here would mean a fixed IV had been introduced.
        val cipher = KeystoreSecretCipher()

        val first = cipher.encrypt(SECRET)
        val second = cipher.encrypt(SECRET)

        assertNotEquals(first, second)
        assertEquals(SECRET, cipher.decrypt(first!!))
        assertEquals(SECRET, cipher.decrypt(second!!))
    }

    @Test
    fun survivesANewCipherInstance() {
        // The token is read by a fresh object graph on every cold start, so the key has to be
        // looked up from the Keystore rather than held only in the instance that created it.
        val encrypted = KeystoreSecretCipher().encrypt(SECRET)

        assertEquals(SECRET, KeystoreSecretCipher().decrypt(encrypted!!))
    }

    @Test
    fun rejectsATamperedCiphertext() {
        // Stands in for a value that arrived from another device or was modified at rest: GCM
        // authentication must reject it rather than return garbage.
        val cipher = KeystoreSecretCipher()
        val encrypted = cipher.encrypt(SECRET)!!
        val tampered = encrypted.dropLast(2) + if (encrypted.endsWith("AA")) "BB" else "AA"

        assertNull(cipher.decrypt(tampered))
    }

    @Test
    fun returnsNullForGarbageInput() {
        assertNull(KeystoreSecretCipher().decrypt("not-base64-at-all!!"))
    }

    private companion object {
        const val SECRET = "account-bearer-token-0123456789"
    }
}
