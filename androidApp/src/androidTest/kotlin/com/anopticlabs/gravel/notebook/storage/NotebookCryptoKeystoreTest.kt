package com.anopticlabs.gravel.notebook.storage

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Notebook codec against the real Android Keystore: what the host suite's software key
 * cannot show, which is the key's attributes and placement, the cached handle, and how the
 * key store behaves under concurrent operations. Round trips use the production alias, which
 * is never deleted; the tests that delete a key use throwaway aliases.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.storage.NotebookCryptoKeystoreTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class NotebookCryptoKeystoreTest {

    private val location = "test".encodeToByteArray()
    private val crypto = appKeyCrypto()

    @After
    fun deleteThrowawayKeys() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in listOf(THROWAWAY_A, THROWAWAY_B)) {
            ks.deleteEntry(alias)
            NotebookKeystore(alias).invalidateKeyCacheForTest()
        }
    }

    @Test
    fun roundTripsThroughTheKeystore() {
        val sizes = listOf(ByteArray(0), byteArrayOf(0x42), ByteArray(1024) { it.toByte() }, ByteArray(4_000_000) { (it * 31).toByte() })
        for (plaintext in sizes + ByteArray(QueueStore.MAX_BLOB_BYTES) { it.toByte() }) {
            val sealed = crypto.seal(plaintext, location)
            assertTrue(NotebookEnvelope.isOurFormat(sealed))
            assertContentEquals(plaintext, crypto.open(sealed, location))
        }
    }

    @Test
    fun theKeyPersistsAcrossInstancesAndNoncesDiffer() {
        val a = crypto.seal("persist".encodeToByteArray(), location)
        val b = appKeyCrypto().seal("persist".encodeToByteArray(), location)
        val wrapIv = NotebookEnvelope.WRAP_IV_OFFSET until NotebookEnvelope.WRAPPED_KEY_OFFSET
        assertFalse(a.sliceArray(wrapIv).contentEquals(b.sliceArray(wrapIv)))
        assertContentEquals("persist".encodeToByteArray(), appKeyCrypto().open(a, location))
    }

    @Test
    fun theKeyHasTheDeclaredAttributesAndLivesInSecureHardware() {
        val key = appKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo

        assertEquals(256, info.keySize)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes.toList())
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_NONE), info.encryptionPaddings.toList())
        assertFalse(info.isUserAuthenticationRequired)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                    info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                "security level ${info.securityLevel}",
            )
        } else {
            @Suppress("DEPRECATION")
            assertTrue(info.isInsideSecureHardware)
        }
    }

    @Test
    fun aTamperedEnvelopeAndAWrongKeyFailAuthentication() {
        val sealed = crypto.seal("payload".encodeToByteArray(), location)
        for (i in listOf(NotebookEnvelope.WRAP_IV_OFFSET, NotebookEnvelope.WRAPPED_KEY_OFFSET + 3, NotebookEnvelope.HEADER_LEN + 1)) {
            val damaged = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0xff).toByte() }
            assertFailsWith<AEADBadTagException>("flip at $i") { crypto.open(damaged, location) }
        }

        val fromA = NotebookCrypto(NotebookKeystore(THROWAWAY_A)) { true }.seal("payload".encodeToByteArray(), location)
        val cryptoB = NotebookCrypto(NotebookKeystore(THROWAWAY_B)) { true }.also { it.seal(ByteArray(0), location) }
        assertFailsWith<AEADBadTagException> { cryptoB.open(fromA, location) }
    }

    @Test
    fun theKeystoreSealOfTheDataKeyIsBoundToTheLocationOnItsOwn() {
        // The data part is built for location "b"; only the data key's seal names "a".
        val dataKey = ByteArray(NotebookEnvelope.DATA_KEY_LEN).also { SecureRandom().nextBytes(it) }
        val wrap = Cipher.getInstance("AES/GCM/NoPadding")
        wrap.init(Cipher.ENCRYPT_MODE, appKey())
        wrap.updateAAD(NotebookEnvelope.keyAad("a".encodeToByteArray()))
        val wrapped = wrap.doFinal(dataKey)
        val dataIv = ByteArray(NotebookEnvelope.IV_LEN).also { SecureRandom().nextBytes(it) }
        val header = NotebookEnvelope.header(wrap.iv, wrapped, dataIv)
        val data = Cipher.getInstance("AES/GCM/NoPadding")
        data.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, dataIv))
        data.updateAAD(NotebookEnvelope.dataAad(header, "b".encodeToByteArray()))
        val envelope = header + data.doFinal("payload".encodeToByteArray())

        assertFailsWith<AEADBadTagException> { crypto.open(envelope, "b".encodeToByteArray()) }
    }

    @Test
    fun aCallerSuppliedNonceIsRefusedOnEncrypt() {
        // The key requires randomized encryption; a fixed nonce would break GCM.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        assertFailsWith<InvalidAlgorithmParameterException> {
            cipher.init(Cipher.ENCRYPT_MODE, appKey(), GCMParameterSpec(128, ByteArray(12)))
        }
    }

    @Test
    fun aLostKeyIsReportedMissingAndOpeningCreatesNoKey() {
        val keystore = NotebookKeystore(THROWAWAY_A)
        val sealed = NotebookCrypto(keystore) { true }.seal("payload".encodeToByteArray(), location)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(THROWAWAY_A)
        keystore.invalidateKeyCacheForTest()

        assertFailsWith<KeyUnavailableException> { NotebookCrypto(keystore) { true }.open(sealed, location) }
        assertFalse(ks.containsAlias(THROWAWAY_A), "opening must never create a key")
    }

    @Test
    fun aLostKeyIsNotReplacedWhileSealedFilesRemain() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "notebook-key-guard").apply { deleteRecursively() }
        try {
            val keystore = NotebookKeystore(THROWAWAY_A)
            val files = EncryptedFiles(root, NotebookFiles.crypto(keystore, root))
            files.writeText(File(root, "reminders"), "before")
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(THROWAWAY_A)
            keystore.invalidateKeyCacheForTest()

            assertFailsWith<KeyUnavailableException> { files.writeText(File(root, "folders_list"), "after") }
            assertFalse(ks.containsAlias(THROWAWAY_A), "a write must not replace a lost key while sealed files remain")
            assertFalse(File(root, "folders_list").exists())

            File(root, "reminders").renameTo(File(root, "${EncryptedFiles.QUARANTINE_DIR}/reminders").also { it.parentFile!!.mkdirs() })
            files.writeText(File(root, "folders_list"), "after")
            assertTrue(ks.containsAlias(THROWAWAY_A))
            assertEquals("after", files.readText(File(root, "folders_list"), strict = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theHandleIsCachedAcrossCallsInstancesAndConcurrentColdCallers() {
        val keystore = NotebookKeystore(THROWAWAY_A)
        val first = keystore.getOrCreateKey { true }
        assertSame(first, keystore.getOrCreateKey { true })
        assertSame(first, NotebookKeystore(THROWAWAY_A).getOrCreateKey { true })

        keystore.invalidateKeyCacheForTest()
        val handles = runBlocking {
            (1..64).map { async(Dispatchers.IO) { NotebookKeystore(THROWAWAY_A).getOrCreateKey { true } } }.awaitAll()
        }
        handles.forEach { assertSame(handles.first(), it) }
    }

    @Test
    fun concurrentOperationsEitherSucceedOrFailTransientlyNeverAsCorruption() {
        // StrongBox prunes operations past its slot count; each job must return the exact
        // plaintext or a TransientCryptoException, never an authentication failure.
        val plaintext = ByteArray(2048) { (it * 7).toByte() }
        val sealed = crypto.seal(plaintext, location)
        runBlocking {
            (1..40).map { i ->
                async(Dispatchers.IO) {
                    try {
                        val opened = if (i % 4 == 0) crypto.open(crypto.seal(plaintext, location), location) else crypto.open(sealed, location)
                        assertContentEquals(plaintext, opened)
                    } catch (e: TransientCryptoException) {
                        // Classified transient: left in place by the stores, retried later.
                    }
                }
            }.awaitAll()
        }
    }

    private companion object {
        const val THROWAWAY_A = "gravel_notebook_kek_test_a"
        const val THROWAWAY_B = "gravel_notebook_kek_test_b"
    }
}
