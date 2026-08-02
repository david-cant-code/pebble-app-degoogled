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
 * Both methods return null rather than throwing. A secret that cannot be decrypted (wrong
 * device, key destroyed, corrupt value) is indistinguishable from having no secret, and callers
 * should treat it as absent and re-acquire it.
 */
interface SecretCipher {
    /** Returns an opaque encoded ciphertext, or null if the secret could not be encrypted. */
    fun encrypt(plaintext: String): String?

    /** Returns the original plaintext, or null if [stored] could not be decrypted. */
    fun decrypt(stored: String): String?
}
