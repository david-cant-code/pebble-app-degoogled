package com.anopticlabs.gravel.notebook.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookEnvelopeTest {

    private val wrapIv = ByteArray(12) { it.toByte() }
    private val wrappedKey = ByteArray(48) { (it + 100).toByte() }
    private val dataIv = ByteArray(12) { (it + 200).toByte() }

    @Test
    fun theHeaderIsMagicVersionKeyWrapNonceWrappedKeyAndDataNonce() {
        val header = NotebookEnvelope.header(wrapIv, wrappedKey, dataIv)

        assertEquals(77, header.size)
        assertContentEquals("GNF1".encodeToByteArray(), header.copyOfRange(0, 4))
        assertEquals(0x01, header[4])
        assertContentEquals(wrapIv, header.copyOfRange(5, 17))
        assertContentEquals(wrappedKey, header.copyOfRange(17, 65))
        assertContentEquals(dataIv, header.copyOfRange(65, 77))
        assertEquals(77 + 16, NotebookEnvelope.MIN_LEN)
    }

    @Test
    fun headerRejectsPartsOfTheWrongLength() {
        assertFailsWith<IllegalArgumentException> { NotebookEnvelope.header(ByteArray(16), wrappedKey, dataIv) }
        assertFailsWith<IllegalArgumentException> { NotebookEnvelope.header(wrapIv, ByteArray(32), dataIv) }
        assertFailsWith<IllegalArgumentException> { NotebookEnvelope.header(wrapIv, wrappedKey, ByteArray(8)) }
    }

    @Test
    fun checkHeaderRejectsTruncatedBadMagicAndOtherVersions() {
        val envelope = NotebookEnvelope.header(wrapIv, wrappedKey, dataIv) + ByteArray(16)
        NotebookEnvelope.checkHeader(envelope)

        assertFailsWith<InvalidFormatException> { NotebookEnvelope.checkHeader(envelope.copyOfRange(0, NotebookEnvelope.MIN_LEN - 1)) }
        assertFailsWith<InvalidFormatException> { NotebookEnvelope.checkHeader(envelope.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertFailsWith<InvalidFormatException> { NotebookEnvelope.checkHeader(envelope.copyOf().also { it[4] = 0x00 }) }
        assertFailsWith<InvalidFormatException> { NotebookEnvelope.checkHeader(envelope.copyOf().also { it[4] = 0x02 }) }
    }

    @Test
    fun isOurFormatNeedsMagicVersionAndRoomForATag() {
        val envelope = NotebookEnvelope.header(wrapIv, wrappedKey, dataIv) + ByteArray(16)
        assertTrue(NotebookEnvelope.isOurFormat(envelope))
        assertFalse(NotebookEnvelope.isOurFormat(ByteArray(0)))
        assertFalse(NotebookEnvelope.isOurFormat(envelope.copyOfRange(0, NotebookEnvelope.MIN_LEN - 1)))
        assertFalse(NotebookEnvelope.isOurFormat(ByteArray(128)))
        assertFalse(NotebookEnvelope.isOurFormat(envelope.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertFalse(NotebookEnvelope.isOurFormat(envelope.copyOf().also { it[4] = 0x02 }))
    }

    @Test
    fun theTwoAadsAreDomainSeparatedAndTheDataAadCarriesTheHeader() {
        val location = "notes/x".encodeToByteArray()
        val envelope = NotebookEnvelope.header(wrapIv, wrappedKey, dataIv) + ByteArray(40)

        assertContentEquals("gn1:key:notes/x".encodeToByteArray(), NotebookEnvelope.keyAad(location))
        val dataAad = NotebookEnvelope.dataAad(envelope, location)
        assertContentEquals(envelope.copyOfRange(0, 77), dataAad.copyOfRange(0, 77))
        assertContentEquals("gn1:data:notes/x".encodeToByteArray(), dataAad.copyOfRange(77, dataAad.size))
    }
}
