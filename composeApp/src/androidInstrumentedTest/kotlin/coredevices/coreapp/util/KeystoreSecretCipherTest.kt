package coredevices.coreapp.util

import coredevices.util.security.DecryptResult
import coredevices.util.security.KeystoreSecretCipher
import org.junit.Test
import java.security.KeyStore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

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
        assertEquals(SECRET, cipher.decryptOrNull(encrypted!!))
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
        assertEquals(SECRET, cipher.decryptOrNull(first!!))
        assertEquals(SECRET, cipher.decryptOrNull(second!!))
    }

    @Test
    fun survivesANewCipherInstance() {
        // The token is read by a fresh object graph on every cold start, so the key has to be
        // looked up from the Keystore rather than held only in the instance that created it.
        val encrypted = KeystoreSecretCipher().encrypt(SECRET)

        assertEquals(SECRET, KeystoreSecretCipher().decryptOrNull(encrypted!!))
    }

    @Test
    fun rejectsATamperedCiphertextAsUnrecoverable() {
        // Stands in for a value that arrived from another device or was modified at rest: GCM
        // authentication must reject it, and deterministically, so the caller may discard it.
        val cipher = KeystoreSecretCipher()
        val encrypted = cipher.encrypt(SECRET)!!
        val tampered = encrypted.dropLast(2) + if (encrypted.endsWith("AA")) "BB" else "AA"

        assertEquals(DecryptResult.Unrecoverable, cipher.decrypt(tampered))
    }

    @Test
    fun garbageInputIsUnrecoverable() {
        // Ensure the key exists first, so this exercises the malformed-input classification
        // rather than the missing-key branch.
        val cipher = KeystoreSecretCipher()
        cipher.encrypt(SECRET)

        assertEquals(DecryptResult.Unrecoverable, cipher.decrypt("not-base64-at-all!!"))
    }

    @Test
    fun missingKeyIsUnrecoverableAndDecryptCreatesNoKey() {
        // A fresh device, or a backup restored onto new hardware, has the ciphertext but not
        // the key. Decrypt must report that as unrecoverable WITHOUT generating a key: a fresh
        // key cannot decrypt the value anyway, and its existence would turn every later read
        // into a misleading authentication failure instead of a clean "nothing stored here".
        val cipher = KeystoreSecretCipher()
        val encrypted = cipher.encrypt(SECRET)!!
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(KEY_ALIAS)

        assertEquals(DecryptResult.Unrecoverable, cipher.decrypt(encrypted))
        assertFalse(
            keyStore.containsAlias(KEY_ALIAS),
            "decrypt generated a key; it must never create one",
        )
    }

    private fun KeystoreSecretCipher.decryptOrNull(stored: String): String? =
        (decrypt(stored) as? DecryptResult.Success)?.plaintext

    private companion object {
        const val SECRET = "account-bearer-token-0123456789"

        /** Mirrors KeystoreSecretCipher.KEY_ALIAS, which is deliberately not public API. */
        const val KEY_ALIAS = "gravel_secret_v1"
    }
}
