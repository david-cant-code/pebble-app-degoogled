package io.rebble.libpebblecommon.pebblekit.two

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import co.touchlab.kermit.Logger
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Per-caller pseudonymous identifiers for watches on the PebbleKit 2 surface.
 *
 * The watch serial is a permanent hardware identifier. Handing it to every companion app would
 * give any two of them a shared key to correlate the same user across otherwise unrelated
 * installs, which is the tracking primitive this fork exists to avoid. Each calling package
 * instead sees a different, stable identifier for the same watch, so an app can still recognise
 * "the watch I talked to last time" while two apps comparing notes learn nothing.
 *
 * Derived with HMAC-SHA256 under a key that never leaves the Android Keystore, so the mapping is
 * not reversible by a caller and cannot be recomputed off-device even given a full copy of app
 * storage.
 */
class PebbleKitWatchIdentity {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val keyLock = Any()

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
                .run {
                    init(
                        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build()
                    )
                    generateKey()
                }
    }

    /**
     * Returns null rather than falling back to the serial: a caller receiving no identifier is a
     * degraded response, whereas a caller receiving the real serial is the exact disclosure this
     * class exists to prevent.
     */
    fun pseudonymFor(callingPackage: String, serial: String): String? = runCatching {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(getOrCreateKey())
        mac.update(callingPackage.encodeToByteArray())
        // Domain separator, so ("a.b", "cd") and ("a", "bcd") cannot collide onto one identifier.
        mac.update(0)
        mac.update(serial.encodeToByteArray())
        mac.doFinal().take(ID_BYTES).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }.onFailure { logger.e(it) { "Could not derive a watch identifier" } }.getOrNull()

    /**
     * Maps an identifier supplied by [callingPackage] back to a real serial.
     *
     * Real serials are also accepted. Emitting them is what leaks, and a caller that already
     * holds one learns nothing by passing it back, so rejecting them would break existing
     * integrations for no gain.
     */
    fun resolveSerial(
        callingPackage: String,
        identifier: String,
        connectedSerials: List<String>,
    ): String? {
        if (identifier in connectedSerials) return identifier
        return connectedSerials.firstOrNull { pseudonymFor(callingPackage, it) == identifier }
    }

    private companion object {
        val logger = Logger.withTag("PebbleKitWatchIdentity")

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gravel_pebblekit_watch_id_v1"
        const val MAC_ALGORITHM = "HmacSHA256"

        /** 128 bits of a SHA-256 MAC: collision risk is negligible for a handful of watches. */
        const val ID_BYTES = 16
    }
}
