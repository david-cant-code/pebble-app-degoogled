package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.HEADER_LEN
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.MAX_END_BYTES
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.MAX_RECORD_BYTES
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.SEGMENT_BYTES
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.TAG_LEN
import com.anopticlabs.gravel.notebook.storage.QueueStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveStreamTest {

    private val password = "correct horse battery".toCharArray()
    private val segment = SEGMENT_BYTES + TAG_LEN
    private val end = RecordType.END.wire

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { (it * 31 + seed).toByte() }

    private fun archive(vararg records: Pair<RecordType, ByteArray>, pw: CharArray = password, random: SecureRandom = SecureRandom()): Pair<ByteArray, ArchiveTally> {
        val out = ByteArrayOutputStream()
        val writer = ArchiveWriter.open(out, pw, random)
        for ((type, body) in records) writer.write(type, body)
        val tally = writer.finish()
        return out.toByteArray() to tally
    }

    private fun readAll(input: InputStream, pw: CharArray = password): Pair<List<ArchiveRecord>, ArchiveTally> {
        val reader = ArchiveReader.open(input, pw)
        val records = generateSequence { reader.next() }.toList()
        return records to reader.verifiedTally!!
    }

    private fun readAll(data: ByteArray, pw: CharArray = password) = readAll(ByteArrayInputStream(data), pw)

    private fun failure(data: ByteArray, pw: CharArray = password): InvalidBackupException =
        assertFailsWith<InvalidBackupException> { readAll(data, pw) }

    private fun problem(data: ByteArray, pw: CharArray = password): BackupProblem = failure(data, pw).problem

    /** Fails unless reading [data] stops at the segment layer on segment [index]. */
    private fun assertTagFails(data: ByteArray, index: Int, what: String) {
        val e = failure(data)
        assertEquals(BackupProblem.DAMAGED, e.problem, what)
        assertTrue(e.message!!.endsWith("segment $index fails authentication"), "$what: ${e.message}")
    }

    /** Four segments: three full, the last short; the NOTE record ends exactly at the end of segment 1. */
    private val fourSegments by lazy {
        archive(RecordType.NOTE to bytes(2 * SEGMENT_BYTES - ArchiveFormat.RECORD_HEADER_LEN), RecordType.CAPTURE to bytes(SEGMENT_BYTES + 100)).first
    }

    private fun ByteArray.flip(at: Int) = copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

    private fun ByteArray.segmentAt(i: Int) = copyOfRange(HEADER_LEN + i * segment, minOf(size, HEADER_LEN + (i + 1) * segment))

    private fun ByteArray.segments(vararg order: Int) = order.fold(copyOf(HEADER_LEN)) { acc, i -> acc + segmentAt(i) }

    private fun frame(type: Int, body: ByteArray, length: Long = body.size.toLong()) =
        byteArrayOf(type.toByte(), (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()) + body

    private fun endBody(records: Map<Int, Int>, framed: ByteArray) =
        BackupJson.encodeToString(ArchiveEnd.serializer(), ArchiveEnd(records, sha256(framed))).encodeToByteArray()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** An archive whose plaintext is exactly [plaintext], sealed by the production segment layer. */
    private fun forge(plaintext: ByteArray): ByteArray {
        val salt = bytes(ArchiveFormat.SALT_LEN, 1)
        val prefix = bytes(ArchiveFormat.NONCE_PREFIX_LEN, 2)
        val header = ArchiveCrypto.header(salt, prefix)
        val out = ByteArrayOutputStream().apply { write(header) }
        SegmentSealer(out, ArchiveCrypto.deriveKey(password, salt), header, prefix).apply {
            write(plaintext)
            finish()
        }
        return out.toByteArray()
    }

    /** Returns at most [max] bytes per read, as a pipe-backed document provider may. */
    private class Trickle(input: InputStream, private val max: Int) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, max))
    }

    @Test
    fun recordsOfEverySizeComeBackInOrderWithTheWritersTally() {
        val records = listOf(
            RecordType.META to "{}".encodeToByteArray(),
            RecordType.NOTE to ByteArray(0),
            RecordType.CAPTURE to bytes(200_000),
            RecordType.NOTE to bytes(SEGMENT_BYTES, 3),
        )
        val (data, written) = archive(*records.toTypedArray())

        assertContentEquals(ArchiveFormat.MAGIC + byteArrayOf(0, 1, 1), data.copyOf(7))
        val (read, tally) = readAll(data)
        assertEquals(records.map { it.first }, read.map { it.type })
        records.zip(read).forEach { (w, r) -> assertContentEquals(w.second, r.body) }
        assertEquals(written, tally)
        assertEquals(mapOf(RecordType.META to 1, RecordType.NOTE to 2, RecordType.CAPTURE to 1), tally.records)
    }

    @Test
    fun aStreamEndingExactlyOnASegmentBoundaryRoundTrips() {
        val endLen = endBody(mapOf(RecordType.NOTE.wire to 1), ByteArray(0)).size
        val body = bytes(2 * SEGMENT_BYTES - 2 * ArchiveFormat.RECORD_HEADER_LEN - endLen)
        val (data, _) = archive(RecordType.NOTE to body)

        assertEquals(HEADER_LEN + 2 * segment, data.size)
        assertContentEquals(body, readAll(data).first.single().body)
    }

    @Test
    fun readsThatReturnFewBytesAtATimeYieldTheSameRecordsAndTally() {
        val endLen = endBody(mapOf(RecordType.NOTE.wire to 1), ByteArray(0)).size
        val (boundary, _) = archive(RecordType.NOTE to bytes(2 * SEGMENT_BYTES - 2 * ArchiveFormat.RECORD_HEADER_LEN - endLen))
        for (data in listOf(fourSegments, boundary)) {
            val (expected, expectedTally) = readAll(data)
            for (max in listOf(1, 7, 3 * 1024 + 5)) {
                val (records, tally) = readAll(Trickle(ByteArrayInputStream(data), max))
                assertEquals(expectedTally, tally, "max $max")
                expected.zip(records).forEach { (e, r) -> assertContentEquals(e.body, r.body, "max $max") }
            }
        }
    }

    @Test
    fun theDocumentedLayoutDecryptsWithoutTheProductionCode() {
        val (data, _) = archive(RecordType.NOTE to bytes(SEGMENT_BYTES + 10))
        val header = data.copyOf(HEADER_LEN)
        val salt = header.copyOfRange(7, 23)
        val prefix = header.copyOfRange(23, 30)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, 600_000, 256)).encoded,
            "AES",
        )
        fun open(index: Int, last: Boolean): ByteArray {
            val nonce = prefix + byteArrayOf(0, 0, 0, index.toByte(), if (last) 1 else 0)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return cipher.doFinal(data.segmentAt(index))
        }

        val first = open(0, last = false)
        assertEquals(SEGMENT_BYTES, first.size)
        assertContentEquals(frame(RecordType.NOTE.wire, ByteArray(0), SEGMENT_BYTES + 10L), first.copyOf(5))
        val last = open(1, last = true)
        assertTrue(last.decodeToString().contains("\"sha256\""))
    }

    @Test
    fun theNonceCarriesTheWholeSegmentIndexBigEndianThenTheLastFlag() {
        val prefix = bytes(ArchiveFormat.NONCE_PREFIX_LEN)
        assertContentEquals(prefix + byteArrayOf(1, 2, 3, 4, 1), ArchiveCrypto.nonce(prefix, 0x0102_0304L, last = true))
        assertContentEquals(prefix + byteArrayOf(-1, -1, -1, -1, 0), ArchiveCrypto.nonce(prefix, 0xFFFF_FFFFL, last = false))
        assertFailsWith<IllegalArgumentException> { ArchiveCrypto.nonce(prefix, 0x1_0000_0000L, last = false) }
    }

    @Test
    fun aWrongPasswordOrFirstSegmentDamageIsOneProblemAndLaterDamageIsDamage() {
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(fourSegments, "correct horse batterz".toCharArray()))
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(fourSegments.flip(HEADER_LEN + 10)))
        assertTagFails(fourSegments.flip(HEADER_LEN + segment + 10), 1, "segment 1 flipped")
        assertTagFails(fourSegments.flip(fourSegments.size - 1), 3, "last tag flipped")
    }

    @Test
    fun everyHeaderFieldIsCheckedOrBound() {
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(fourSegments.flip(8)), "salt")
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(fourSegments.flip(HEADER_LEN - 1)), "nonce prefix")
        assertEquals(BackupProblem.NEWER_FORMAT, problem(fourSegments.copyOf().also { it[6] = 3 }), "kdf id")
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(fourSegments.copyOf().also { it[6] = ArchiveFormat.KDF_RECOVERY_KEY.toByte() }), "kdf id swapped")
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(fourSegments.copyOf().also { it[5] = 0 }), "version 0")
        assertEquals(BackupProblem.NEWER_FORMAT, problem(fourSegments.copyOf().also { it[5] = 2 }))
        assertEquals(BackupProblem.NEWER_FORMAT, problem(fourSegments.copyOf().also { it[4] = 1 }), "version high byte")
        assertEquals(BackupProblem.NOT_A_BACKUP, problem("PK\u0003\u0004".encodeToByteArray() + fourSegments))
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(ByteArray(0)))
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(ArchiveFormat.MAGIC))
        for (cut in listOf(6, 7, HEADER_LEN - 1, HEADER_LEN)) assertEquals(BackupProblem.DAMAGED, problem(fourSegments.copyOf(cut)), "cut at $cut")
    }

    @Test
    fun droppedAppendedOrReorderedSegmentsFailAtTheSegmentLayer() {
        val s = fourSegments
        assertTagFails(s.copyOf(HEADER_LEN + 3 * segment), 2, "last segment dropped")
        assertTagFails(s.copyOf(s.size - 1), 3, "tail byte dropped")
        assertTagFails(s + byteArrayOf(0), 3, "byte appended")
        assertTagFails(s + s.segmentAt(1), 3, "segment appended")
        assertTagFails(s.segments(0, 2, 1, 3), 1, "middle swapped")
        assertTagFails(s.segments(0, 1, 1, 2, 3), 2, "segment repeated")
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(s.segments(1, 0, 2, 3)))
    }

    @Test
    fun segmentsAndHeadersOfAnotherArchiveUnderTheSamePasswordDoNotOpen() {
        val other = archive(RecordType.NOTE to bytes(2 * SEGMENT_BYTES - ArchiveFormat.RECORD_HEADER_LEN), RecordType.CAPTURE to bytes(SEGMENT_BYTES + 100)).first
        assertTagFails(fourSegments.copyOf(HEADER_LEN + segment) + other.segmentAt(1) + fourSegments.segmentAt(2) + fourSegments.segmentAt(3), 1, "spliced")
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(other.copyOf(HEADER_LEN) + fourSegments.copyOfRange(HEADER_LEN, fourSegments.size)))
    }

    @Test
    fun theClosingRecordMustMatchEveryRecordBeforeItAndEndTheStream() {
        val note = frame(RecordType.NOTE.wire, "n".encodeToByteArray())
        val good = note + frame(end, endBody(mapOf(4 to 1), note))
        assertEquals(1, readAll(forge(good)).first.size)

        assertEquals(BackupProblem.DAMAGED, problem(forge(note + frame(end, endBody(mapOf(4 to 2), note)))), "count")
        assertEquals(BackupProblem.DAMAGED, problem(forge(note + frame(end, endBody(mapOf(4 to 1), note + byteArrayOf(0))))), "hash")
        assertEquals(BackupProblem.DAMAGED, problem(forge(good + note)), "record after END")
        assertEquals(BackupProblem.DAMAGED, problem(forge(note)), "no END")
        assertEquals(BackupProblem.DAMAGED, problem(forge(note + frame(end, "{".encodeToByteArray()))), "END not JSON")
        assertEquals(BackupProblem.DAMAGED, problem(forge(frame(4, bytes(10), length = 11))), "ends inside a record")
        assertEquals(BackupProblem.NEWER_FORMAT, problem(forge(frame(0x7f, ByteArray(0)) + good)), "unknown type")
    }

    @Test
    fun lengthsOverTheCapsAreRefusedBeforeTheBodyIsRead() {
        val note = frame(RecordType.NOTE.wire, "n".encodeToByteArray())
        val overEnd = failure(forge(note + frame(end, ByteArray(0), length = MAX_END_BYTES + 1L)))
        assertTrue(overEnd.message!!.endsWith("closing record over its size cap"), overEnd.message)
        val overRecord = failure(forge(frame(4, ByteArray(0), length = MAX_RECORD_BYTES + 1L)))
        assertTrue(overRecord.message!!.endsWith("record over the size cap"), overRecord.message)
        assertTrue(failure(forge(frame(4, ByteArray(0), length = 0xFFFF_FFFFL))).message!!.endsWith("record over the size cap"))
    }

    @Test
    fun aCapturedRecordingUnderTheQueueCapFitsARecordWithRoomForItsEntry() {
        assertTrue(MAX_RECORD_BYTES.toLong() - QueueStore.MAX_BLOB_BYTES >= 1L shl 20)
    }

    @Test
    fun eachArchiveDrawsItsOwnSaltAndNoncePrefix() {
        val a = archive(RecordType.NOTE to bytes(10)).first
        val b = archive(RecordType.NOTE to bytes(10)).first
        assertFalse(a.copyOfRange(7, 23).contentEquals(b.copyOfRange(7, 23)), "salt")
        assertFalse(a.copyOfRange(23, HEADER_LEN).contentEquals(b.copyOfRange(23, HEADER_LEN)), "nonce prefix")
    }

    @Test
    fun aPasswordTypedComposedOrDecomposedOpensTheSameArchive() {
        val composed = Normalizer.normalize("pässwörd ñandú ünïcode", Normalizer.Form.NFC)
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        assertTrue(composed.length < decomposed.length)
        val (data, _) = archive(RecordType.NOTE to bytes(10), pw = decomposed.toCharArray())
        assertEquals(1, readAll(data, composed.toCharArray()).first.size)
    }

    @Test
    fun aPasswordThatIsNotValidTextOrTooShortAfterNormalizingIsRefused() {
        val loneSurrogate = "abcdefghijklmnop\uD800".toCharArray()
        assertFailsWith<IllegalArgumentException> { ArchiveWriter.open(ByteArrayOutputStream(), loneSurrogate) }
        assertFailsWith<IllegalArgumentException> { ArchiveWriter.open(ByteArrayOutputStream(), "abcdefghijklmnop\uDC00".toCharArray()) }
        val fourteenComposed = Normalizer.normalize("abcdefghijklmé", Normalizer.Form.NFD).toCharArray()
        assertEquals(15, fourteenComposed.size)
        assertFailsWith<IllegalArgumentException> { ArchiveWriter.open(ByteArrayOutputStream(), fourteenComposed) }
        val fourteenCodePoints = "\uD83D\uDE00".repeat(14).toCharArray()
        assertFailsWith<IllegalArgumentException> { ArchiveWriter.open(ByteArrayOutputStream(), fourteenCodePoints) }
        ArchiveWriter.open(ByteArrayOutputStream(), "\uD83D\uDE00".repeat(15).toCharArray())
        ArchiveWriter.open(ByteArrayOutputStream(), "abcdefghijklmno".toCharArray())

        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(fourSegments, loneSurrogate))
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(fourSegments, CharArray(0)))
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(fourSegments, fourteenCodePoints))
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(ByteArray(40), CharArray(0)))
    }

    @Test
    fun theReaderReturnsNothingMoreOnceVerifiedAndRefusesToGoOnAfterAFailure() {
        val reader = ArchiveReader.open(ByteArrayInputStream(archive(RecordType.NOTE to bytes(3)).first), password)
        assertEquals(RecordType.NOTE, reader.next()!!.type)
        assertNull(reader.next())
        assertNull(reader.next())

        // Junk inserted where the NOTE record ends at a segment boundary; read on, the next segment would authenticate as index 2.
        val junk = bytes(segment, 9)
        val spliced = fourSegments.copyOf(HEADER_LEN + 2 * segment) + junk + fourSegments.segmentAt(2) + fourSegments.segmentAt(3)
        val broken = ArchiveReader.open(ByteArrayInputStream(spliced), password)
        assertEquals(RecordType.NOTE, broken.next()!!.type)
        assertFailsWith<InvalidBackupException> { broken.next() }
        assertFailsWith<IllegalStateException> { broken.next() }
        assertNull(broken.verifiedTally)
    }

    @Test
    fun theWriterRefusesMisuseAndEveryCallAfterAFailedWrite() {
        assertFailsWith<IllegalArgumentException> { ArchiveWriter.open(ByteArrayOutputStream(), "short".toCharArray()) }
        val writer = ArchiveWriter.open(ByteArrayOutputStream(), password)
        assertFailsWith<IllegalArgumentException> { writer.write(RecordType.END, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { writer.write(RecordType.CAPTURE, ByteArray(MAX_RECORD_BYTES + 1)) }
        writer.finish()
        assertFailsWith<IllegalStateException> { writer.write(RecordType.NOTE, ByteArray(0)) }
        assertFailsWith<IllegalStateException> { writer.finish() }

        var writes = 0
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (++writes > 1) throw IOException("disk full")
            }
        }
        val cut = ArchiveWriter.open(failing, password)
        assertFailsWith<IOException> { cut.write(RecordType.NOTE, bytes(2 * SEGMENT_BYTES)) }
        assertFailsWith<IllegalStateException> { cut.write(RecordType.NOTE, ByteArray(0)) }
        assertFailsWith<IllegalStateException> { cut.finish() }
    }

    @Test
    fun theHeaderAReaderHandsOutIsACopy() {
        val reader = ArchiveReader.open(ByteArrayInputStream(fourSegments), password)
        reader.header.fill(0)
        assertContentEquals(fourSegments.copyOf(HEADER_LEN), reader.header)
        assertEquals(2, generateSequence { reader.next() }.count())
    }

    @Test
    fun aKeyDerivedOnceOpensItsArchiveAgainAndAnotherArchiveReadsAsDamaged() {
        val header = fourSegments.copyOf(HEADER_LEN)
        val key = ArchiveReader.deriveKey(header, password)
        repeat(2) { assertEquals(2, generateSequence(ArchiveReader.open(ByteArrayInputStream(fourSegments), key)::next).count()) }

        val other = archive(RecordType.NOTE to bytes(3)).first
        val e = assertFailsWith<InvalidBackupException> { ArchiveReader.open(ByteArrayInputStream(other), key) }
        assertEquals(BackupProblem.DAMAGED, e.problem)
        val wrong = ArchiveReader.deriveKey(header, "correct horse batterz".toCharArray())
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, assertFailsWith<InvalidBackupException> { ArchiveReader.open(ByteArrayInputStream(fourSegments), wrong).next() }.problem)
        val fourteen = "\uD83D\uDE00".repeat(14).toCharArray()
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, assertFailsWith<InvalidBackupException> { ArchiveReader.deriveKey(header, fourteen) }.problem)
        ArchiveReader.deriveKey(header, "\uD83D\uDE00".repeat(15).toCharArray())
    }

    private val recoveryKey = ("abcdefgh" + "01234567" + "ijklmnop" + "qrstuvwx").repeat(2)

    private fun automatic(vararg records: Pair<RecordType, ByteArray>, key: String = recoveryKey): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ArchiveWriter.openWithRecoveryKey(out, key.toCharArray())
        for ((type, body) in records) writer.write(type, body)
        writer.finish()
        return out.toByteArray()
    }

    @Test
    fun anArchiveSealedWithARecoveryKeyOpensWithTheKeyAsShownOrStoredAndNothingElse() {
        val data = automatic(RecordType.NOTE to bytes(SEGMENT_BYTES + 3), RecordType.REMINDERS to bytes(9))
        assertEquals(ArchiveFormat.KDF_RECOVERY_KEY, ArchiveReader.kdfOf(data.copyOf(HEADER_LEN)))
        assertEquals(2, readAll(data, recoveryKey.toCharArray()).first.size)
        assertEquals(2, readAll(data, RecoveryKey.display(recoveryKey.toCharArray()).toCharArray()).first.size)
        val other = recoveryKey.dropLast(1) + "y"
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(data, other.toCharArray()))
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(data, recoveryKey.dropLast(1).toCharArray()), "not a key")
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(data, password), "a password")
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem(data.copyOf().also { it[6] = ArchiveFormat.KDF_PBKDF2_SHA256.toByte() }, recoveryKey.toCharArray()))
        assertFailsWith<IllegalArgumentException> { ArchiveWriter.openWithRecoveryKey(ByteArrayOutputStream(), "short".toCharArray()) }
    }

    @Test
    fun theRecoveryKeyLayoutDecryptsWithAKeyFromTheRfcConstruction() {
        val data = automatic(RecordType.NOTE to bytes(10))
        val header = data.copyOf(HEADER_LEN)
        val salt = header.copyOfRange(7, 23)
        val prefix = header.copyOfRange(23, 30)
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(recoveryKey.encodeToByteArray()) }
        val okm = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            doFinal("GNBK recovery key".encodeToByteArray() + byteArrayOf(1))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(okm, "AES"), GCMParameterSpec(128, prefix + byteArrayOf(0, 0, 0, 0, 1)))
        cipher.updateAAD(header)
        assertContentEquals(frame(RecordType.NOTE.wire, bytes(10)), cipher.doFinal(data.segmentAt(0)).copyOf(15))
    }

    @Test
    fun aFixedArchiveMatchesTheVectorTheDeviceTestChecks() {
        val (data, _) = archive(RecordType.NOTE to ArchiveVector.BODY.encodeToByteArray(), pw = ArchiveVector.PASSWORD.toCharArray(), random = ArchiveVector.FixedRandom())
        assertEquals(ArchiveVector.SHA256, sha256(data))
    }
}

/** A fixed archive; `ArchiveProviderParityTest` in the device suite carries the same values. */
internal object ArchiveVector {
    const val PASSWORD = "pässwörd ñandú ünïcode"
    const val BODY = "Grüße, café"
    const val SHA256 = "d1fb63dc1b64d5db07a833a786c8310f284254e92b7aea77be80418ad30bfc38"

    class FixedRandom : SecureRandom() {
        private var n = 0

        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (n++ * 7 + 3).toByte()
        }
    }
}
