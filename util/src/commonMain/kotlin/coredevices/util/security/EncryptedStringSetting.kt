package coredevices.util.security

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings

private val logger = Logger.withTag("EncryptedStringSetting")

/**
 * A single string setting that is encrypted at rest via [SecretCipher].
 *
 * Values are tagged with [PREFIX] so a value written by an older build, before this class
 * existed, is distinguishable from a ciphertext and can be upgraded in place on first read
 * instead of silently logging the user out.
 */
class EncryptedStringSetting(
    private val settings: Settings,
    private val cipher: SecretCipher,
    private val key: String,
) {
    fun get(): String? {
        val stored = settings.getStringOrNull(key) ?: return null

        if (!stored.startsWith(PREFIX)) {
            // Written in the clear by a pre-encryption build. Re-write it encrypted now; if that
            // fails, leave the plaintext alone rather than destroying a working session, since
            // that is no worse than the state we just found.
            val upgraded = cipher.encrypt(stored)
            if (upgraded != null) {
                settings.putString(key, PREFIX + upgraded)
            } else {
                logger.e { "Could not upgrade plaintext '$key' to encrypted storage" }
            }
            return stored
        }

        return when (val result = cipher.decrypt(stored.removePrefix(PREFIX))) {
            is DecryptResult.Success -> result.plaintext

            DecryptResult.Unrecoverable -> {
                // Undecryptable almost always means this value arrived from another device via
                // backup or transfer. Drop it so it does not sit at rest forever being retried.
                logger.w { "Discarding undecryptable '$key'" }
                settings.remove(key)
                null
            }

            DecryptResult.TransientFailure -> {
                // The ciphertext may be perfectly recoverable, so removing it here would turn
                // a keystore hiccup into a permanently lost secret (a forced re-login, for the
                // account token). Keep it and let a later read try again.
                logger.w { "Temporarily failed to decrypt '$key'; keeping it" }
                null
            }
        }
    }

    fun set(value: String?) {
        if (value == null) {
            settings.remove(key)
            return
        }

        val encrypted = cipher.encrypt(value)
        if (encrypted == null) {
            // Falling back to plaintext would quietly reintroduce the exposure this class exists
            // to close, so store nothing. The caller keeps the value in memory, so the current
            // session still works and only persistence across restart is lost.
            logger.e { "Could not encrypt '$key'; refusing to store it in the clear" }
            settings.remove(key)
            return
        }
        settings.putString(key, PREFIX + encrypted)
    }

    private companion object {
        /**
         * Marks a value as ciphertext. Includes a version so the encoding can change later
         * without another round of "is this plaintext?" guesswork.
         */
        const val PREFIX = "enc1:"
    }
}
