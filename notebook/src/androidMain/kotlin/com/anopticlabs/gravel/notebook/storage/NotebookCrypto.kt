package com.anopticlabs.gravel.notebook.storage

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [Cryptographer] over AES-256-GCM in the [NotebookEnvelope]: every seal draws a fresh data key,
 * encrypts the file with it in software, and seals only the data key with the Keystore key.
 * Opening never creates a key, and sealing creates one only while [mayCreateKey] allows it, so a
 * lost key is [KeyUnavailableException] rather than a fresh key failing every tag. A data key is
 * not zeroed after a seal or open. The internal constructor takes any key source for host tests.
 */
class NotebookCrypto internal constructor(
    private val keyForSeal: () -> SecretKey,
    private val keyForOpen: () -> SecretKey?,
    private val retry: KeystoreRetry,
) : Cryptographer {

    constructor(keystore: NotebookKeystore, mayCreateKey: () -> Boolean) :
        this({ keystore.getOrCreateKey(mayCreateKey) }, keystore::existingKey, KeystoreRetry(beforeRetry = keystore::reloadKey))

    override fun seal(plaintext: ByteArray, location: ByteArray): ByteArray {
        val dataKey = ByteArray(NotebookEnvelope.DATA_KEY_LEN).also(random::nextBytes)
        val (wrapIv, wrappedKey) = retry.run("seal") {
            // A fresh Cipher per attempt: a pruned operation handle is dead.
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyForSeal())
            cipher.updateAAD(NotebookEnvelope.keyAad(location))
            cipher.iv to cipher.doFinal(dataKey)
        }
        val dataIv = ByteArray(NotebookEnvelope.IV_LEN).also(random::nextBytes)
        val header = NotebookEnvelope.header(wrapIv, wrappedKey, dataIv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, AES), GCMParameterSpec(TAG_BITS, dataIv))
        cipher.updateAAD(NotebookEnvelope.dataAad(header, location))
        val out = header.copyOf(NotebookEnvelope.HEADER_LEN + cipher.getOutputSize(plaintext.size))
        val written = cipher.doFinal(plaintext, 0, plaintext.size, out, NotebookEnvelope.HEADER_LEN)
        return if (NotebookEnvelope.HEADER_LEN + written == out.size) out else out.copyOf(NotebookEnvelope.HEADER_LEN + written)
    }

    override fun open(envelope: ByteArray, location: ByteArray): ByteArray {
        // Header checks are deterministic corruption and skip the Keystore path entirely.
        NotebookEnvelope.checkHeader(envelope)
        val dataKey = retry.run("open") {
            val key = keyForOpen() ?: throw KeyUnavailableException("the Notebook key is missing")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope, NotebookEnvelope.WRAP_IV_OFFSET, NotebookEnvelope.IV_LEN))
            cipher.updateAAD(NotebookEnvelope.keyAad(location))
            cipher.doFinal(envelope, NotebookEnvelope.WRAPPED_KEY_OFFSET, NotebookEnvelope.WRAPPED_KEY_LEN)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_BITS, envelope, NotebookEnvelope.DATA_IV_OFFSET, NotebookEnvelope.IV_LEN)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, AES), spec)
        cipher.updateAAD(NotebookEnvelope.dataAad(envelope, location))
        return cipher.doFinal(envelope, NotebookEnvelope.HEADER_LEN, envelope.size - NotebookEnvelope.HEADER_LEN)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES = "AES"
        const val TAG_BITS = NotebookEnvelope.TAG_LEN * 8
        val random = SecureRandom()
    }
}
