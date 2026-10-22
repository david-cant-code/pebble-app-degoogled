package com.anopticlabs.gravel.notebook.storage

import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The codec over a software key; the Keystore-specific behavior is in the device suite. */
class NotebookCryptoTest {

    private val kek = fixedKey(3)
    private val crypto = softwareCrypto(kek)
    private val location = "test".encodeToByteArray()

    @Test
    fun sealThenOpenRoundTripsEveryLength() {
        for (plaintext in listOf(ByteArray(0), byteArrayOf(0x42), ByteArray(1024) { it.toByte() }, ByteArray(1_000_000) { (it * 31).toByte() })) {
            assertContentEquals(plaintext, crypto.open(crypto.seal(plaintext, location), location))
        }
    }

    @Test
    fun everySealDrawsAFreshDataKeyAndFreshNonces() {
        val plaintext = "hello world".encodeToByteArray()
        val a = crypto.seal(plaintext, location)
        val b = crypto.seal(plaintext, location)
        for ((from, to) in listOf(
            NotebookEnvelope.WRAP_IV_OFFSET to NotebookEnvelope.WRAPPED_KEY_OFFSET,
            NotebookEnvelope.WRAPPED_KEY_OFFSET to NotebookEnvelope.DATA_IV_OFFSET,
            NotebookEnvelope.DATA_IV_OFFSET to NotebookEnvelope.HEADER_LEN,
            NotebookEnvelope.HEADER_LEN to a.size,
        )) {
            assertFalse(a.copyOfRange(from, to).contentEquals(b.copyOfRange(from, to)), "bytes $from until $to repeat")
        }
        val keyA = unwrapDataKey(a)
        val keyB = unwrapDataKey(b)
        assertFalse(keyA.contentEquals(keyB), "the data key repeats")
        assertFalse(keyA.all { it == 0.toByte() } || keyB.all { it == 0.toByte() }, "an all-zero data key")
        assertContentEquals(plaintext, crypto.open(a, location))
        assertContentEquals(plaintext, crypto.open(b, location))
    }

    @Test
    fun theEnvelopeIsGnf1Version1WithAHeaderAndATag() {
        val sealed = crypto.seal("a".encodeToByteArray(), location)
        assertTrue(NotebookEnvelope.isOurFormat(sealed))
        assertEquals(77, NotebookEnvelope.HEADER_LEN)
        assertEquals(NotebookEnvelope.HEADER_LEN + 1 + NotebookEnvelope.TAG_LEN, sealed.size)
        assertContentEquals("GNF1".encodeToByteArray(), sealed.copyOfRange(0, 4))
        assertEquals(0x01, sealed[4])
    }

    @Test
    fun aKeystoreFailureAtTheKeyIsRetriedAndThenTransient() {
        var failures = 2
        val flaky = { if (failures-- > 0) throw ProviderException("busy") else kek }
        val recovering = NotebookCrypto(flaky, flaky, KeystoreRetry(sleep = {}))
        val sealed = recovering.seal("payload".encodeToByteArray(), location)
        failures = 2
        assertContentEquals("payload".encodeToByteArray(), recovering.open(sealed, location))

        val down = { throw ProviderException("busy") }
        val failing = NotebookCrypto(down, down, KeystoreRetry(sleep = {}))
        assertFailsWith<TransientCryptoException> { failing.seal("payload".encodeToByteArray(), location) }
        assertFailsWith<TransientCryptoException> { failing.open(sealed, location) }
    }

    @Test
    fun aRetryLooksTheKeyUpAgainAndACachedHandleThatStoppedWorkingIsReplaced() {
        val payload = "payload".encodeToByteArray()
        val first = DyingKey(kek)
        var lookUp: () -> SecretKey? = { first }
        val keystore = NotebookKeystore("host-test-reload") { lookUp() }
        try {
            val crypto = NotebookCrypto(keystore) { true }
            val sealed = crypto.seal(payload, location)

            first.dead = true
            lookUp = { null }
            assertFailsWith<TransientCryptoException> { crypto.open(sealed, location) }
            lookUp = { throw ProviderException("unreachable") }
            assertFailsWith<TransientCryptoException> { crypto.open(sealed, location) }
            assertSame(first, keystore.existingKey())

            val second = DyingKey(kek)
            lookUp = { second }
            assertContentEquals(payload, crypto.open(sealed, location))
            assertSame(second, keystore.existingKey())

            second.dead = true
            lookUp = { kek }
            assertContentEquals(payload, crypto.open(crypto.seal(payload, location), location))
            assertSame(kek, keystore.existingKey())
        } finally {
            keystore.invalidateKeyCacheForTest()
        }
    }

    @Test
    fun aFlippedByteInEveryPartAfterTheVersionFailsAuthentication() {
        val sealed = crypto.seal("payload".encodeToByteArray(), location)
        val offsets = listOf(
            NotebookEnvelope.WRAP_IV_OFFSET,
            NotebookEnvelope.WRAPPED_KEY_OFFSET,
            NotebookEnvelope.DATA_IV_OFFSET - 1,
            NotebookEnvelope.DATA_IV_OFFSET,
            NotebookEnvelope.HEADER_LEN,
            sealed.size - 1,
        )
        for (i in offsets) {
            val damaged = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFailsWith<AEADBadTagException>("flip at $i") { crypto.open(damaged, location) }
        }
    }

    @Test
    fun aDifferentLocationFailsAuthentication() {
        val sealed = crypto.seal("payload".encodeToByteArray(), "notes/a".encodeToByteArray())
        assertFailsWith<AEADBadTagException> { crypto.open(sealed, "notes/b".encodeToByteArray()) }
    }

    @Test
    fun aWrappedKeyTakenFromAnotherFileAtTheSameLocationFailsAuthentication() {
        val a = crypto.seal("first".encodeToByteArray(), location)
        val b = crypto.seal("other".encodeToByteArray(), location)
        a.copyInto(b, NotebookEnvelope.WRAP_IV_OFFSET, NotebookEnvelope.WRAP_IV_OFFSET, NotebookEnvelope.DATA_IV_OFFSET)
        assertFailsWith<AEADBadTagException> { crypto.open(b, location) }
    }

    @Test
    fun theDataSealIsBoundToTheHeaderAndTheLocation() {
        val plaintext = "payload".encodeToByteArray()
        assertContentEquals(plaintext, crypto.open(referenceEnvelope(plaintext, "a"), "a".encodeToByteArray()))

        val otherLocation = referenceEnvelope(plaintext, "a") { header -> header + "gn1:data:b".encodeToByteArray() }
        assertFailsWith<AEADBadTagException> { crypto.open(otherLocation, "a".encodeToByteArray()) }
        val noHeader = referenceEnvelope(plaintext, "a") { "gn1:data:a".encodeToByteArray() }
        assertFailsWith<AEADBadTagException> { crypto.open(noHeader, "a".encodeToByteArray()) }
    }

    @Test
    fun aDifferentKeyFailsAuthentication() {
        val sealed = softwareCrypto(fixedKey(1)).seal("payload".encodeToByteArray(), location)
        assertFailsWith<AEADBadTagException> { softwareCrypto(fixedKey(2)).open(sealed, location) }
    }

    @Test
    fun openingWithoutAKeyIsKeyUnavailableAndSealingStillWorks() {
        val sealed = crypto.seal("payload".encodeToByteArray(), location)
        val noKeyToOpen = softwareCrypto(kek, keyForOpen = { null })
        assertFailsWith<KeyUnavailableException> { noKeyToOpen.open(sealed, location) }
        assertTrue(NotebookEnvelope.isOurFormat(noKeyToOpen.seal("new".encodeToByteArray(), location)))
    }

    @Test
    fun headerDamageAndAnotherVersionAreFormatFailures() {
        val sealed = crypto.seal("payload".encodeToByteArray(), location)
        assertFailsWith<InvalidFormatException> { crypto.open(sealed.copyOf().also { it[0] = 'X'.code.toByte() }, location) }
        assertFailsWith<InvalidFormatException> { crypto.open(sealed.copyOf().also { it[4] = 0x02 }, location) }
        assertFailsWith<InvalidFormatException> { crypto.open(sealed.copyOfRange(0, NotebookEnvelope.MIN_LEN - 1), location) }
    }

    @Test
    fun anEnvelopeFromAnIndependentImplementationOpens() {
        // Built outside this codebase (Python cryptography 50.0.0, AESGCM) from the documented layout:
        // key-encryption key 32 x 0x07, data key 0x10..0x2f, key-wrap nonce 0x00..0x0b, data
        // nonce 0x20..0x2b, location "golden", plaintext "golden".
        val golden = ("474e463101" + "000102030405060708090a0b" +
            "0890fb63091ccf5e6fabff87fe4572e5c11d94ad3c4751163ee74991a2fa432ac1b4e543b4086787da8be790fbaf2ade" +
            "202122232425262728292a2b" + "a03f2928a05d921fd045a9b66f5631ab5c213070aa1d").hexToBytes()
        assertContentEquals("golden".encodeToByteArray(), softwareCrypto(fixedKey(7)).open(golden, "golden".encodeToByteArray()))
    }

    /** The documented layout built with plain JCE, with the data seal's AAD open to the caller. */
    private fun referenceEnvelope(
        plaintext: ByteArray,
        location: String,
        dataAad: (header: ByteArray) -> ByteArray = { it + "gn1:data:$location".encodeToByteArray() },
    ): ByteArray {
        val random = SecureRandom()
        val dataKey = ByteArray(32).also(random::nextBytes)
        val wrapIv = ByteArray(12).also(random::nextBytes)
        val dataIv = ByteArray(12).also(random::nextBytes)
        val wrapped = gcmSeal(kek, wrapIv, "gn1:key:$location".encodeToByteArray(), dataKey)
        val header = "GNF1".encodeToByteArray() + byteArrayOf(0x01) + wrapIv + wrapped + dataIv
        return header + gcmSeal(SecretKeySpec(dataKey, "AES"), dataIv, dataAad(header), plaintext)
    }

    private fun unwrapDataKey(envelope: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, envelope.copyOfRange(NotebookEnvelope.WRAP_IV_OFFSET, NotebookEnvelope.WRAPPED_KEY_OFFSET)))
            updateAAD("gn1:key:".encodeToByteArray() + location)
            doFinal(envelope.copyOfRange(NotebookEnvelope.WRAPPED_KEY_OFFSET, NotebookEnvelope.DATA_IV_OFFSET))
        }

    private fun gcmSeal(key: SecretKey, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(plaintext)
        }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** A key handle that fails at every cipher init once [dead], as one whose Keystore connection is gone. */
    private class DyingKey(private val real: SecretKey) : SecretKey {
        @Volatile var dead = false
        override fun getAlgorithm(): String = real.algorithm
        override fun getFormat(): String = real.format
        override fun getEncoded(): ByteArray = if (dead) throw ProviderException("handle outlived its connection") else real.encoded
    }
}
