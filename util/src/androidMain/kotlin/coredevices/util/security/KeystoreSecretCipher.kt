package coredevices.util.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import co.touchlab.kermit.Logger
import java.security.KeyStore
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
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
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

    override fun decrypt(stored: String): String? = runCatching {
        // Never create a key here: on a device that has never encrypted anything, or after the
        // key was cleared, generating a fresh one would turn "cannot decrypt" into a confusing
        // authentication failure further down.
        val key = existingKey() ?: return null
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        val ivSize = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivSize)
        val ciphertext = combined.copyOfRange(1 + ivSize, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ciphertext).decodeToString()
    }.onFailure { logger.w(it) { "Decryption failed" } }.getOrNull()

    private companion object {
        val logger = Logger.withTag("KeystoreSecretCipher")

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gravel_secret_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
