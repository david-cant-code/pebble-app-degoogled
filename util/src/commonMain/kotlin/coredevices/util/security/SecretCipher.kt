package coredevices.util.security

/**
 * Encrypts short secrets so they are not written to app storage in the clear.
 *
 * What this defends against is off-device exposure: a copy of the app's data that has left the
 * running device, such as an Auto Backup or device-transfer blob, a backup restored onto
 * different hardware, or the private data directory read out of a pulled image. The key is
 * device-bound and non-exportable, so none of those copies decrypt.
 *
 * What it deliberately does not defend against is code already running as this app on an
 * unlocked device: that code can simply ask the cipher to decrypt. Binding the key to user
 * authentication would raise that bar, but the app needs its account token in background sync
 * with no user present, so the key is not authentication-bound. Claiming otherwise would be the
 * kind of control that looks protective without being so.
 *
 * Neither method throws. Decryption failures are classified rather than collapsed to one
 * value because callers act on the difference: a value that can never be decrypted here
 * (wrong device, key destroyed, corrupt value) is safe to discard, while a value the
 * keystore merely failed to decrypt right now must be kept, since deleting it would turn a
 * transient provider error into permanent loss of the secret.
 */
interface SecretCipher {
    /** Returns an opaque encoded ciphertext, or null if the secret could not be encrypted. */
    fun encrypt(plaintext: String): String?

    fun decrypt(stored: String): DecryptResult
}

sealed interface DecryptResult {
    data class Success(val plaintext: String) : DecryptResult

    /**
     * [stored] can never be decrypted here: the key is absent, authentication failed (a
     * ciphertext written under a different device's key), or the value is malformed.
     */
    data object Unrecoverable : DecryptResult

    /**
     * The keystore failed right now; the same value may decrypt fine on a later attempt, so
     * the caller must not destroy it.
     */
    data object TransientFailure : DecryptResult
}
