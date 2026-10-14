package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.HEADER_LEN
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.MAX_END_BYTES
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.MAX_RECORD_BYTES
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.NONCE_LEN
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.NONCE_PREFIX_LEN
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.RECORD_HEADER_LEN
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.SALT_LEN
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.SEGMENT_BYTES
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.TAG_LEN
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The records of each type an archive holds before END, and the SHA-256 (hex) of their framed bytes. */
data class ArchiveTally(val records: Map<RecordType, Int>, val sha256: String) {
    fun count(type: RecordType): Int = records[type] ?: 0
}

class ArchiveRecord(val type: RecordType, val body: ByteArray)

/**
 * A key derived from a password for one archive's salt; [ArchiveReader.open] reports an archive with another salt as
 * damaged. [wipe] zeroes this object's copy of the key; a reader opened with it keeps the cipher's own.
 */
class ArchiveKey internal constructor(internal val salt: ByteArray, private val bytes: ByteArray) {
    internal fun spec() = SecretKeySpec(bytes, "AES")

    fun wipe() = bytes.fill(0)
}

/**
 * Writes the archive: a header of magic, version (u16), kdf id, salt and nonce prefix, then the
 * record stream sealed in segments. The key comes from the archive's own salt and either a
 * password, NFC-normalized (PBKDF2-HMAC-SHA256), or a [RecoveryKey] (HKDF-SHA256). Segments follow the STREAM construction (Hoang, Reyhanitabar, Rogaway and
 * Vizár, CRYPTO 2015), the nonce laid out as in Tink (tink-java main,
 * `subtle/AesGcmHkdfStreaming.paramsForSegment`): prefix, segment index (u32 BE), last flag; each
 * is AES-256-GCM with the header as AAD, and every segment but the last holds [SEGMENT_BYTES].
 * A record is its type byte, its length (u32 BE) and its body; END closes the stream with the
 * count per type and the SHA-256 of every framed record before it. The caller closes [out]. A
 * writer that has thrown refuses every later call.
 */
class ArchiveWriter private constructor(private val sealer: SegmentSealer) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val counts = LinkedHashMap<RecordType, Int>()
    private var finished = false
    private var failed = false

    @Throws(IOException::class)
    fun write(type: RecordType, body: ByteArray) {
        check(!finished && !failed) { "archive already finished or failed" }
        require(type != RecordType.END) { "END is written by finish" }
        require(body.size <= MAX_RECORD_BYTES) { "record of ${body.size} bytes over the cap" }
        val frame = recordHeader(type, body.size)
        sealing {
            sealer.write(frame)
            sealer.write(body)
        }
        digest.update(frame)
        digest.update(body)
        counts[type] = (counts[type] ?: 0) + 1
    }

    /** Writes END and the last segment; returns what the archive holds. */
    @Throws(IOException::class)
    fun finish(): ArchiveTally {
        check(!finished && !failed) { "archive already finished or failed" }
        finished = true
        val tally = ArchiveTally(counts.toMap(), digest.digest().toHex())
        val end = BackupJson.encodeToString(ArchiveEnd.serializer(), ArchiveEnd(counts.mapKeys { it.key.wire }, tally.sha256)).encodeToByteArray()
        sealing {
            sealer.write(recordHeader(RecordType.END, end.size))
            sealer.write(end)
            sealer.finish()
        }
        return tally
    }

    private inline fun sealing(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            failed = true
            throw e
        }
    }

    companion object {
        /** Derives the key from [password] and writes the header; the slow step is the key derivation. */
        @Throws(IOException::class)
        fun open(out: OutputStream, password: CharArray, random: SecureRandom = SecureRandom()): ArchiveWriter {
            val chars = requireNotNull(ArchiveCrypto.normalizedPassword(password)) { "password is not valid text" }
            try {
                require(ArchiveCrypto.isLongEnough(chars)) { "password shorter than ${ArchiveFormat.MIN_PASSWORD_LENGTH} characters" }
                return begin(out, ArchiveFormat.KDF_PBKDF2_SHA256, random) { salt -> ArchiveCrypto.deriveKey(chars, salt) }
            } finally {
                chars.fill('\u0000')
            }
        }

        /** Derives the key from [recoveryKey], in any form [RecoveryKey.parse] reads, and writes the header. */
        @Throws(IOException::class)
        fun openWithRecoveryKey(out: OutputStream, recoveryKey: CharArray, random: SecureRandom = SecureRandom()): ArchiveWriter {
            val key = requireNotNull(RecoveryKey.parse(CharBuffer.wrap(recoveryKey))) { "not a recovery key" }
            try {
                return begin(out, ArchiveFormat.KDF_RECOVERY_KEY, random) { salt -> ArchiveCrypto.recoveryKeySpec(key, salt) }
            } finally {
                key.fill('\u0000')
            }
        }

        private inline fun begin(out: OutputStream, kdf: Int, random: SecureRandom, derive: (salt: ByteArray) -> SecretKeySpec): ArchiveWriter {
            val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
            val prefix = ByteArray(NONCE_PREFIX_LEN).also { random.nextBytes(it) }
            val header = ArchiveCrypto.header(salt, prefix, kdf)
            val key = derive(salt)
            out.write(header)
            return ArchiveWriter(SegmentSealer(out, key, header, prefix))
        }
    }
}

/**
 * Reads what [ArchiveWriter] wrote, one record at a time: a record is returned only from
 * segments that authenticated, and the stream counts as whole only once END has matched every
 * record before it and nothing follows. A failed tag on the first segment is a wrong password
 * or damage; on a later one, with the key proven, it is damage. A reader that has thrown refuses
 * every later call.
 */
class ArchiveReader private constructor(private val headerBytes: ByteArray, private val opener: SegmentOpener) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val counts = LinkedHashMap<RecordType, Int>()
    private var tally: ArchiveTally? = null
    private var failed = false

    val header: ByteArray get() = headerBytes.copyOf()

    /** Set once [next] has returned null. */
    val verifiedTally: ArchiveTally? get() = tally

    /** The next record before END, or null once END has checked out and the stream is spent. */
    @Throws(IOException::class)
    fun next(): ArchiveRecord? {
        check(!failed) { "archive reader already failed" }
        try {
            return readNext()
        } catch (e: Throwable) {
            failed = true
            throw e
        }
    }

    private fun readNext(): ArchiveRecord? {
        if (tally != null) return null
        if (opener.atEnd()) throw damaged("archive ends without its closing record")
        val frame = ByteArray(RECORD_HEADER_LEN)
        opener.readFully(frame)
        val type = RecordType.of(frame[0].toInt() and 0xff)
            ?: throw InvalidBackupException(BackupProblem.NEWER_FORMAT, "archive holds a record this app does not know")
        val length = ((frame[1].toLong() and 0xff) shl 24) or ((frame[2].toLong() and 0xff) shl 16) or
            ((frame[3].toLong() and 0xff) shl 8) or (frame[4].toLong() and 0xff)
        if (type == RecordType.END && length > MAX_END_BYTES) throw damaged("closing record over its size cap")
        if (length > MAX_RECORD_BYTES) throw damaged("record over the size cap")
        val body = ByteArray(length.toInt())
        opener.readFully(body)
        if (type != RecordType.END) {
            digest.update(frame)
            digest.update(body)
            counts[type] = (counts[type] ?: 0) + 1
            return ArchiveRecord(type, body)
        }
        val end = try {
            BackupJson.decodeFromString(ArchiveEnd.serializer(), body.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw damaged("closing record does not parse")
        }
        val read = ArchiveTally(counts.toMap(), digest.digest().toHex())
        if (end.records != read.records.mapKeys { it.key.wire }) throw damaged("record counts do not match the closing record")
        if (end.sha256 != read.sha256) throw damaged("record hash does not match the closing record")
        if (!opener.atEnd()) throw damaged("data follows the closing record")
        tally = read
        return null
    }

    companion object {
        /** Reads and checks the header and derives the key from [secret] as [deriveKey] does; no segment is read yet. */
        @Throws(IOException::class)
        fun open(input: InputStream, secret: CharArray): ArchiveReader {
            val header = readHeader(input)
            val key = deriveKey(header, secret)
            try {
                return open(input, header, key)
            } finally {
                key.wipe()
            }
        }

        /** Reads and checks the header; one without [key]'s salt is not the archive the key was derived for, and damaged. */
        @Throws(IOException::class)
        fun open(input: InputStream, key: ArchiveKey): ArchiveReader = open(input, readHeader(input), key)

        /**
         * The key of the archive whose header [readHeader] returned as [header], from [secret]: its
         * password, or its recovery key for an archive with [ArchiveFormat.KDF_RECOVERY_KEY]. A
         * password derivation is the slow step.
         */
        fun deriveKey(header: ByteArray, secret: CharArray): ArchiveKey {
            val salt = saltOf(header)
            if (kdfOf(header) == ArchiveFormat.KDF_RECOVERY_KEY) {
                val key = RecoveryKey.parse(CharBuffer.wrap(secret)) ?: throw wrongSecret()
                try {
                    return ArchiveKey(salt, ArchiveCrypto.recoveryKeyBytes(key, salt))
                } finally {
                    key.fill('\u0000')
                }
            }
            val chars = ArchiveCrypto.normalizedPassword(secret) ?: throw wrongSecret()
            try {
                if (!ArchiveCrypto.isLongEnough(chars)) throw wrongSecret()
                return ArchiveKey(salt, ArchiveCrypto.deriveKeyBytes(chars, salt))
            } finally {
                chars.fill('\u0000')
            }
        }

        /** The kdf id of a header [readHeader] returned. */
        fun kdfOf(header: ByteArray): Int = header[6].toInt() and 0xff

        private fun wrongSecret() = InvalidBackupException(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, "wrong password or damaged backup")

        private fun open(input: InputStream, header: ByteArray, key: ArchiveKey): ArchiveReader {
            if (!saltOf(header).contentEquals(key.salt)) throw damaged("its salt is not the one its key was derived for")
            return ArchiveReader(header, SegmentOpener(input, key.spec(), header, header.copyOfRange(7 + SALT_LEN, HEADER_LEN)))
        }

        private fun saltOf(header: ByteArray) = header.copyOfRange(7, 7 + SALT_LEN)

        /** Reads the header from [input] and checks everything in it that needs no key; returns it. */
        @Throws(IOException::class)
        fun readHeader(input: InputStream): ByteArray {
            val header = ByteArray(HEADER_LEN)
            val got = input.readUpTo(header, 0, HEADER_LEN)
            if (got < 6 || !header.copyOf(4).contentEquals(ArchiveFormat.MAGIC)) {
                throw InvalidBackupException(BackupProblem.NOT_A_BACKUP, "not a backup file")
            }
            val version = ((header[4].toInt() and 0xff) shl 8) or (header[5].toInt() and 0xff)
            if (version > ArchiveFormat.VERSION) throw InvalidBackupException(BackupProblem.NEWER_FORMAT, "backup from a newer app version (v$version)")
            if (version != ArchiveFormat.VERSION) throw InvalidBackupException(BackupProblem.NOT_A_BACKUP, "not a backup file")
            if (got < HEADER_LEN) throw damaged("header cut short")
            if (kdfOf(header) != ArchiveFormat.KDF_PBKDF2_SHA256 && kdfOf(header) != ArchiveFormat.KDF_RECOVERY_KEY) {
                throw InvalidBackupException(BackupProblem.NEWER_FORMAT, "backup key derivation this app does not know")
            }
            return header
        }
    }
}

internal object ArchiveCrypto {
    const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun header(salt: ByteArray, prefix: ByteArray, kdf: Int = ArchiveFormat.KDF_PBKDF2_SHA256): ByteArray {
        require(salt.size == SALT_LEN && prefix.size == NONCE_PREFIX_LEN) { "salt or nonce prefix of the wrong length" }
        val v = ArchiveFormat.VERSION
        return ArchiveFormat.MAGIC + byteArrayOf((v shr 8).toByte(), v.toByte(), kdf.toByte()) + salt + prefix
    }

    fun nonce(prefix: ByteArray, index: Long, last: Boolean): ByteArray {
        require(index in 0..MAX_SEGMENT_INDEX) { "segment index out of range" }
        val n = ByteArray(NONCE_LEN)
        prefix.copyInto(n)
        n[7] = (index ushr 24).toByte()
        n[8] = (index ushr 16).toByte()
        n[9] = (index ushr 8).toByte()
        n[10] = index.toByte()
        n[11] = if (last) 1 else 0
        return n
    }

    /** NFC (Unicode Standard Annex #15), so a password typed composed or decomposed derives one key; null when the chars are not well-formed UTF-16. */
    fun normalizedPassword(password: CharArray): CharArray? {
        var i = 0
        while (i < password.size) {
            val c = password[i]
            when {
                c.isHighSurrogate() -> if (i + 1 < password.size && password[i + 1].isLowSurrogate()) i += 2 else return null
                c.isLowSurrogate() -> return null
                else -> i++
            }
        }
        return Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC).toCharArray()
    }

    /** [password] as [normalizedPassword] returns it, measured in code points. */
    fun isLongEnough(password: CharArray): Boolean =
        Character.codePointCount(password, 0, password.size) >= ArchiveFormat.MIN_PASSWORD_LENGTH

    /** [password] as [normalizedPassword] returns it. */
    fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val bytes = deriveKeyBytes(password, salt)
        try {
            return SecretKeySpec(bytes, "AES")
        } finally {
            bytes.fill(0)
        }
    }

    /** [key] as [RecoveryKey.parse] returns it: its ASCII bytes are the HKDF input keying material. */
    fun recoveryKeyBytes(key: CharArray, salt: ByteArray): ByteArray {
        val ikm = ByteArray(key.size) { key[it].code.toByte() }
        try {
            return Hkdf.sha256(ikm, salt, ArchiveFormat.RECOVERY_KEY_INFO.encodeToByteArray(), ArchiveFormat.KEY_BITS / 8)
        } finally {
            ikm.fill(0)
        }
    }

    fun recoveryKeySpec(key: CharArray, salt: ByteArray): SecretKeySpec {
        val bytes = recoveryKeyBytes(key, salt)
        try {
            return SecretKeySpec(bytes, "AES")
        } finally {
            bytes.fill(0)
        }
    }

    fun deriveKeyBytes(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ArchiveFormat.PBKDF2_ITERATIONS, ArchiveFormat.KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    const val MAX_SEGMENT_INDEX = 0xFFFF_FFFFL
}

/** Seals a full buffer as a non-last segment only once more plaintext arrives; [finish] seals what is buffered as the last. */
internal class SegmentSealer(
    private val out: OutputStream,
    private val key: SecretKeySpec,
    private val header: ByteArray,
    private val prefix: ByteArray,
) {
    private val cipher = Cipher.getInstance(ArchiveCrypto.TRANSFORMATION)
    private val buffer = ByteArray(SEGMENT_BYTES)
    private var filled = 0
    private var index = 0L

    fun write(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        var at = off
        val end = off + len
        while (at < end) {
            if (filled == SEGMENT_BYTES) seal(last = false)
            val n = minOf(SEGMENT_BYTES - filled, end - at)
            bytes.copyInto(buffer, filled, at, at + n)
            filled += n
            at += n
        }
    }

    fun finish() = seal(last = true)

    private fun seal(last: Boolean) {
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN * 8, ArchiveCrypto.nonce(prefix, index, last)))
        cipher.updateAAD(header)
        out.write(cipher.doFinal(buffer, 0, filled))
        filled = 0
        index++
    }
}

/** Opens segments on demand; a segment is the last one when the input ends inside it or right after it. */
internal class SegmentOpener(
    private val input: InputStream,
    private val key: SecretKeySpec,
    private val header: ByteArray,
    private val prefix: ByteArray,
) {
    private val cipher = Cipher.getInstance(ArchiveCrypto.TRANSFORMATION)
    private val sealed = ByteArray(SEGMENT_BYTES + TAG_LEN)
    private var lookahead = -1
    private var plain = ByteArray(0)
    private var pos = 0
    private var index = 0L
    private var lastOpened = false

    fun atEnd(): Boolean {
        while (pos == plain.size) {
            if (lastOpened) return true
            openNext()
        }
        return false
    }

    /** Up to [len] bytes into [dst] at [off]; -1 once the last segment is spent. */
    fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (atEnd()) return -1
        val n = minOf(plain.size - pos, len)
        plain.copyInto(dst, off, pos, pos + n)
        pos += n
        return n
    }

    fun readFully(dst: ByteArray) {
        var at = 0
        while (at < dst.size) {
            if (atEnd()) throw damaged("archive ends inside a record")
            val n = minOf(plain.size - pos, dst.size - at)
            plain.copyInto(dst, at, pos, pos + n)
            pos += n
            at += n
        }
    }

    private fun openNext() {
        var n = 0
        if (lookahead >= 0) {
            sealed[0] = lookahead.toByte()
            lookahead = -1
            n = 1
        }
        n += input.readUpTo(sealed, n, sealed.size - n)
        val last = if (n < sealed.size) true else input.read().also { lookahead = it } < 0
        if (n < TAG_LEN) throw damaged("segment cut short")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN * 8, ArchiveCrypto.nonce(prefix, index, last)))
        cipher.updateAAD(header)
        plain = try {
            cipher.doFinal(sealed, 0, n)
        } catch (e: AEADBadTagException) {
            if (index == 0L) throw InvalidBackupException(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, "wrong password or damaged backup")
            throw damaged("segment $index fails authentication")
        } catch (e: GeneralSecurityException) {
            throw damaged("segment $index does not open")
        }
        pos = 0
        index++
        lastOpened = last
    }
}

private fun recordHeader(type: RecordType, length: Int): ByteArray =
    byteArrayOf(type.wire.toByte(), (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())

private fun damaged(detail: String) = InvalidBackupException(BackupProblem.DAMAGED, "backup file is damaged: $detail")

/** Reads until [len] bytes or the end of the stream; returns how many were read. */
private fun InputStream.readUpTo(dst: ByteArray, off: Int, len: Int): Int {
    var total = 0
    while (total < len) {
        val n = read(dst, off + total, len - total)
        if (n < 0) break
        total += n
    }
    return total
}
