package com.anopticlabs.gravel.notebook.storage

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import co.touchlab.kermit.Logger
import java.security.KeyStore
import java.security.ProviderException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** What [NotebookKeystore.resetKey] did: cleared the key or the handle of an absent one, found it working, kept one that fails or could not be looked up without being forced, or failed to delete it. */
enum class KeyReset { DELETED, KEY_WORKS, KEPT, FAILED }

/** What a lookup of the key found: no key, a key that encrypts and decrypts, one that fails, or a lookup that failed. */
enum class KeyLookup { ABSENT, WORKS, FAILS, UNREADABLE }

/** Where the Notebook key is kept: a secure element, the processor's trusted environment, secure hardware Android does not name, software, or a place it does not report. */
enum class KeyPlacement { STRONGBOX, TEE, SECURE_HARDWARE, SOFTWARE, UNKNOWN }

/**
 * The Notebook's AES-256-GCM key in the Android Keystore: StrongBox, else generated without it; no user
 * authentication, so background work can read and write; the handle cached because the lookup
 * is a binder call. Needs API 28 (android-37.0 api-versions.xml: setIsStrongBoxBacked and
 * StrongBoxUnavailableException since="28"). [alias] and [lookUp] are test seams.
 */
class NotebookKeystore internal constructor(private val alias: String, private val lookUp: (String) -> SecretKey?) {

    constructor(alias: String = ALIAS) : this(alias, { keystoreKey(it) })

    /**
     * The key for sealing; when none exists one is generated only if [mayCreate] says so, else
     * [KeyUnavailableException]. Both run under the lock that serializes generation.
     */
    fun getOrCreateKey(mayCreate: () -> Boolean): SecretKey = existingKey() ?: synchronized(LOCK) {
        // Serializes first callers so two of them cannot race two keys into one alias.
        cachedKeys[alias] ?: run {
            if (!mayCreate()) throw KeyUnavailableException("the Notebook key is missing while sealed files remain")
            generateKey().also { cachedKeys[alias] = it }
        }
    }

    /** The key for opening, or null when none exists: opening never creates a key. */
    fun existingKey(): SecretKey? {
        cachedKeys[alias]?.let { return it }
        return synchronized(LOCK) {
            cachedKeys[alias] ?: lookUp(alias)?.also { cachedKeys[alias] = it }
        }
    }

    /**
     * Looks the key up again and caches the handle found; a lookup that finds none or fails keeps
     * the cached one. A handle keeps the Keystore connection of its lookup and fails once that
     * connection is gone, while a lookup connects anew (android16-release
     * AndroidKeyStoreProvider.makeAndroidKeyStoreSecretKeyFromKeyEntryResponse,
     * KeyStoreSecurityLevel.createOperation; AndroidKeyStoreSpi.engineLoad, KeyStore2.getService).
     * Runs under the lock [deleteKey] takes: a lookup outside it could cache the handle of a key
     * deleted meanwhile.
     */
    internal fun reloadKey() {
        synchronized(LOCK) {
            try {
                lookUp(alias)?.let { cachedKeys[alias] = it }
            } catch (e: Exception) {
                logger.w(e) { "the key could not be looked up again" }
            }
        }
    }

    /** Where the key is kept; null when there is no key yet or the Keystore cannot say. */
    fun placement(): KeyPlacement? = try {
        val key = existingKey() ?: return null
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        @Suppress("DEPRECATION")
        placementOf(Build.VERSION.SDK_INT, { info.securityLevel }, { info.isInsideSecureHardware })
    } catch (e: Exception) {
        null
    }

    /**
     * Clears the key so [getOrCreateKey] may make a new one; see [resetWith]. A missing key is certain
     * from API 31, where getKey returns null only for KEY_NOT_FOUND (android12-release keystore2
     * AndroidKeyStoreSpi.engineGetKey); on API 28-30 it also returns null when the key store cannot be
     * reached (pie-release, android10-release and android11-release KeyStore.contains catch RemoteException).
     */
    fun resetKey(force: Boolean): KeyReset = resetWith(
        force,
        absentIsCertain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        lookup = {
            val key = lookUp(alias)
            when {
                key == null -> KeyLookup.ABSENT
                works(key) -> KeyLookup.WORKS
                else -> KeyLookup.FAILS
            }
        },
        delete = ::deleteKey,
        forget = { synchronized(LOCK) { cachedKeys.remove(alias) } },
    )

    private fun works(key: SecretKey): Boolean = try {
        KeystoreRetry().run("key check") {
            val sealed = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, key)
                doFinal(ByteArray(16)) to iv
            }
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, sealed.second))
                doFinal(sealed.first)
            }
        }
        true
    } catch (e: Exception) {
        logger.w(e) { "the key failed a test round trip" }
        false
    }

    /** Deletes the key, if there is one, and forgets its handle; [getOrCreateKey] may then make a new one. */
    fun deleteKey() = synchronized(LOCK) {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        cachedKeys.remove(alias)
    }

    /** Test-only: a test that deleted its throwaway alias calls this so the next call recreates the key. */
    fun invalidateKeyCacheForTest() {
        synchronized(LOCK) { cachedKeys.remove(alias) }
    }

    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw UnsupportedOperationException("Notebook storage needs API 28, running on ${Build.VERSION.SDK_INT}")
        }
        return generateWithStrongBoxRetry(
            strongBox = { generateKey(strongBox = true) },
            tee = { generateKey(strongBox = false) },
            sleep = { Thread.sleep(it) },
            unavailable = { it is StrongBoxUnavailableException },
        )
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        /**
         * A key Android reports absent is only forgotten, since there is nothing to delete, when that
         * report is [absentIsCertain]; otherwise it counts as a lookup that failed. One that works is
         * kept; one that fails, or whose lookup fails, is deleted only with [force].
         */
        internal fun resetWith(force: Boolean, absentIsCertain: Boolean, lookup: () -> KeyLookup, delete: () -> Unit, forget: () -> Unit): KeyReset {
            val found = try {
                lookup().let { if (it == KeyLookup.ABSENT && !absentIsCertain) KeyLookup.UNREADABLE else it }
            } catch (e: Exception) {
                logger.w(e) { "the key could not be looked up" }
                KeyLookup.UNREADABLE
            }
            return when (found) {
                KeyLookup.ABSENT -> KeyReset.DELETED.also { forget() }
                KeyLookup.WORKS -> KeyReset.KEY_WORKS
                KeyLookup.FAILS, KeyLookup.UNREADABLE -> if (!force) KeyReset.KEPT else try {
                    delete()
                    KeyReset.DELETED
                } catch (e: Exception) {
                    logger.w(e) { "the key could not be deleted" }
                    KeyReset.FAILED
                }
            }
        }

        /**
         * The placement Android reports: the security level from API 31 (android-37.0
         * api-versions.xml, KeyInfo.getSecurityLevel since="31"; UNKNOWN_SECURE is at least the
         * TEE, android16-release KeyProperties), before that only whether the key is in secure
         * hardware (KeyInfo.isInsideSecureHardware).
         */
        internal fun placementOf(sdk: Int, level: () -> Int, insideSecureHardware: () -> Boolean): KeyPlacement = when {
            sdk >= Build.VERSION_CODES.S -> when (level()) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyPlacement.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyPlacement.TEE
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeyPlacement.SECURE_HARDWARE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyPlacement.SOFTWARE
                else -> KeyPlacement.UNKNOWN
            }
            insideSecureHardware() -> KeyPlacement.SECURE_HARDWARE
            else -> KeyPlacement.SOFTWARE
        }

        private fun keystoreKey(alias: String): SecretKey? =
            KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey

        const val STRONGBOX_RETRIES = KeystoreRetry.MAX_RETRIES
        const val STRONGBOX_BACKOFF_MS = KeystoreRetry.BACKOFF_MS
        private val logger = Logger.withTag("NotebookKeystore")

        /**
         * Placement is decided once per install, so a busy secure element gets a few attempts
         * before the key is generated without StrongBox for good; an unavailable one falls back at once.
         */
        internal fun generateWithStrongBoxRetry(
            strongBox: () -> SecretKey,
            tee: () -> SecretKey,
            sleep: (Long) -> Unit,
            unavailable: (ProviderException) -> Boolean,
        ): SecretKey {
            var attempt = 0
            while (true) {
                try {
                    return strongBox()
                } catch (e: ProviderException) {
                    if (unavailable(e)) {
                        logger.i { "StrongBox unavailable; generating the key without StrongBox" }
                        return tee()
                    }
                    if (attempt >= STRONGBOX_RETRIES) {
                        logger.w(e) { "StrongBox generation failed after $attempt retries; generating the key without StrongBox" }
                        return tee()
                    }
                    attempt++
                    sleep(STRONGBOX_BACKOFF_MS * attempt)
                }
            }
        }

        /** Persisted state: the data key of every Notebook file on every device is sealed under this alias. */
        const val ALIAS = "gravel_notebook_kek_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PROVIDER = "AndroidKeyStore"
        private val LOCK = Any()
        private val cachedKeys = ConcurrentHashMap<String, SecretKey>()
    }
}
