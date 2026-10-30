package com.anopticlabs.gravel.notebook.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * Local files for archives on their way to a picked document or in from one, in a directory under
 * the Notebook root, which the app's backup rules exclude with the rest of it. Every file this
 * instance creates carries its random session prefix, which [sweep] spares; the app holds one
 * instance per process.
 */
class BackupStaging(
    root: File,
    private val minFreeBytes: Long = MIN_FREE_BYTES,
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    private val dir = File(root, DIR)
    private val random = SecureRandom()
    private val session = "archive-" + ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) } + "-"

    /** A new empty file under a fresh key; the caller deletes it when done. */
    @Throws(StagingException::class)
    fun newFile(): StagedFile {
        if (!dir.isDirectory && !dir.mkdirs()) throw StagingException("cannot create the backup staging directory")
        val key = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")
        val prefix = ByteArray(ArchiveFormat.NONCE_PREFIX_LEN).also(random::nextBytes)
        return StagedFile(staging { File.createTempFile(session, ".tmp", dir) }, key, prefix, minFreeBytes, usableSpace)
    }

    /** Deletes the staging files of other instances, which an ended process left behind. Returns how many it deleted. */
    fun sweep(): Int = dir.listFiles().orEmpty().count { it.isFile && !it.name.startsWith(session) && it.delete() }

    companion object {
        const val DIR = "backup_staging"

        /** A staging write fails once it finds less free space than this in the app's storage. */
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
    }
}

/** A staging file could not be created, written or read back. */
open class StagingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A staging write refused because it found less than the staging minimum free. */
class StagingSpaceException : StagingException("not enough free space to stage the backup")

private inline fun <T> staging(block: () -> T): T = try {
    block()
} catch (e: StagingException) {
    throw e
} catch (e: IOException) {
    throw StagingException("backup staging failed: ${e.message}", e)
}

/**
 * A staging file whose content is sealed in [ArchiveFormat.SEGMENT_BYTES] segments (the
 * archive's segment layer) under a random key held only here, so the file opens only through this
 * object: a copy of it, or a file an ended process left, is unreadable even with the backup
 * password.
 */
class StagedFile internal constructor(
    val file: File,
    private val key: SecretKeySpec,
    private val prefix: ByteArray,
    private val minFreeBytes: Long,
    private val usableSpace: (File) -> Long,
) {
    /** The bytes the last closed [openWrite] stream took; -1 before one closed. */
    @Volatile
    var size: Long = -1L
        private set

    @Throws(StagingException::class)
    fun openWrite(): OutputStream {
        requireSpace()
        return Sealing(staging { FileOutputStream(file) })
    }

    /** Every failure, a segment that will not open included, reads as a [StagingException]. */
    @Throws(StagingException::class)
    fun openRead(): InputStream = Opening(staging { FileInputStream(file) })

    fun delete() {
        file.delete()
    }

    private fun requireSpace() {
        if (usableSpace(file.parentFile!!) < minFreeBytes) throw StagingSpaceException()
    }

    private inner class Sealing(private val out: FileOutputStream) : OutputStream() {
        private val sealer = SegmentSealer(out, key, AAD, prefix)
        private var count = 0L
        private var sinceCheck = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            staging { sealer.write(b, off, len) }
            count += len
            sinceCheck += len
            if (sinceCheck >= SPACE_CHECK_BYTES) {
                sinceCheck = 0
                requireSpace()
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                staging { sealer.finish() }
            } finally {
                out.close()
            }
            size = count
        }
    }

    private inner class Opening(private val input: InputStream) : InputStream() {
        private val opener = SegmentOpener(input, key, AAD, prefix)

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            staging { opener.read(b, off, len) }
        } catch (e: InvalidBackupException) {
            throw StagingException("the staged file does not open: ${e.message}", e)
        }

        override fun close() = input.close()
    }

    private companion object {
        val AAD = "gn:staging".encodeToByteArray()
        const val SPACE_CHECK_BYTES = 1L shl 20
    }
}
