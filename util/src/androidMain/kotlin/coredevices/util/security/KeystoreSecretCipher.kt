package coredevices.util.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import co.touchlab.kermit.Logger
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretCipher] backed by AES-256-GCM with the key held in the Android Keystore.
 *
 * Uses the platform Keystore and javax.crypto directly rather than
 * androidx.security:security-crypto, whose entire API surface was deprecated in favour of
 * exactly this.
 *
 * The Keystore access pattern here (keystore init, key lock, get-or-generate under its own
 * alias) has a structural twin in libpebble3's PebbleKitWatchIdentity, which cannot depend on
 * this module. A fix to the Keystore handling in either file almost certainly applies to both.
 */
class KeystoreSecretCipher : SecretCipher {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    // Key creation is not atomic, and both the account token read at startup and a concurrent
    // sign-in can reach it, which would otherwise race two keys into the same alias and leave
    // whichever value was written first undecryptable.
    private val keyLock = Any()

    private fun existingKey(): SecretKey? = synchronized(keyLock) {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        existingKey()
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
    }

    override fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        // GCM is catastrophically broken by IV reuse, so take the one the provider generated per
        // operation and carry it alongside the ciphertext instead of choosing one here.
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        iv.copyInto(combined, 1)
        ciphertext.copyInto(combined, 1 + iv.size)
        Base64.encodeToString(combined, Base64.NO_WRAP)
    }.onFailure { logger.e(it) { "Encryption failed" } }.getOrNull()

    override fun decrypt(stored: String): DecryptResult {
        // Never create a key here: on a device that has never encrypted anything, or after the
        // key was cleared, generating a fresh one would turn "cannot decrypt" into a confusing
        // authentication failure further down. The lookup itself failing is a different case
        // from the key being absent: the keystore daemon can die or reject calls under load,
        // and classifying that as unrecoverable would let a hiccup destroy the stored value.
        val key = try {
            existingKey() ?: return DecryptResult.Unrecoverable
        } catch (e: Exception) {
            logger.w(e) { "Keystore unavailable while looking up the key" }
            return DecryptResult.TransientFailure
        }

        // Malformed input can only ever be malformed: no retry changes what the bytes are.
        val combined = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            logger.w { "Stored value is not valid Base64" }
            return DecryptResult.Unrecoverable
        }

        return try {
            val ivSize = combined[0].toInt() and 0xFF
            val iv = combined.copyOfRange(1, 1 + ivSize)
            val ciphertext = combined.copyOfRange(1 + ivSize, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            DecryptResult.Success(cipher.doFinal(ciphertext).decodeToString())
        } catch (e: AEADBadTagException) {
            // Deterministic: this key did not write this ciphertext (restored from another
            // device, or the value was tampered with). Retrying cannot change the outcome.
            logger.w(e) { "Decryption failed authentication" }
            DecryptResult.Unrecoverable
        } catch (e: IndexOutOfBoundsException) {
            logger.w { "Stored value has malformed framing" }
            DecryptResult.Unrecoverable
        } catch (e: Exception) {
            // Everything else is presumed transient (keystore/provider errors). The cost of
            // being wrong is one failed read per launch; the cost of the opposite mistake is
            // deleting a recoverable secret.
            logger.w(e) { "Decryption failed, treating as transient" }
            DecryptResult.TransientFailure
        }
    }

    private companion object {
        val logger = Logger.withTag("KeystoreSecretCipher")

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gravel_secret_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
