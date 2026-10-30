package com.anopticlabs.gravel.notebook.storage

import co.touchlab.kermit.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.crypto.BadPaddingException

/**
 * The Notebook's encrypted files under one [root]: each bound to its path relative to the root,
 * written through a synced staging file and a move, and quarantined on corruption.
 */
class EncryptedFiles internal constructor(
    root: File,
    private val crypto: Cryptographer,
    private val clock: () -> Long,
) {
    constructor(root: File, crypto: Cryptographer) : this(root, crypto, System::currentTimeMillis)

    val root: File = root.absoluteFile.normalize()
    val stagingDir: File get() = File(root, STAGING_DIR)
    val quarantineDir: File get() = File(root, QUARANTINE_DIR)

    /** [file]'s path relative to [root] with `/` separators; [file] must be under [root]. */
    fun relativePath(file: File): String {
        val relative = root.toPath().relativize(file.absoluteFile.normalize().toPath())
        val first = relative.getName(0).toString()
        require(first.isNotEmpty() && first != "..") { "$file is not under $root" }
        return relative.joinToString("/")
    }

    /** The location an envelope is bound to: [file]'s path relative to [root]. */
    fun locationFor(file: File): ByteArray = relativePath(file).encodeToByteArray()

    /**
     * The plaintext, or null when the file is absent or could not be read this time; [strict]
     * rethrows a transient or key failure so a read-modify-write aborts. A corrupt file is moved to
     * [quarantineDir] and reads as null, unless [quarantineCorrupt] is false: then it stays in
     * place, reads as null, and a [strict] read throws [CorruptFileException]. A file longer than
     * [maxBytes] is corrupt without being read, since the file is held in memory whole.
     */
    fun read(file: File, strict: Boolean = false, quarantineCorrupt: Boolean = true, maxBytes: Long = Long.MAX_VALUE): ByteArray? {
        if (!file.exists()) return null
        return try {
            val length = file.length()
            if (length > maxBytes) throw InvalidFormatException("${label(file)} is $length bytes, over the $maxBytes byte cap")
            val raw = try {
                file.readBytes()
            } catch (e: IOException) {
                throw TransientCryptoException("read failed for ${label(file)}", e)
            }
            crypto.open(raw, locationFor(file))
        } catch (e: TransientCryptoException) {
            if (strict) throw e
            warn(file, e, "could not be read this time; left in place")
            null
        } catch (e: KeyUnavailableException) {
            if (strict) throw e
            warn(file, e, "cannot be opened without its key; left in place")
            null
        } catch (e: BadPaddingException) {
            corrupt(file, e, strict, quarantineCorrupt)
        } catch (e: InvalidFormatException) {
            corrupt(file, e, strict, quarantineCorrupt)
        }
    }

    fun readText(file: File, strict: Boolean = false, quarantineCorrupt: Boolean = true, maxBytes: Long = Long.MAX_VALUE): String? =
        read(file, strict, quarantineCorrupt, maxBytes)?.decodeToString()

    private fun corrupt(file: File, cause: Exception, strict: Boolean, quarantineCorrupt: Boolean): ByteArray? {
        if (quarantineCorrupt) {
            quarantine(file, cause)
            return null
        }
        if (strict) throw CorruptFileException("${label(file)} failed to open and was left in place", cause)
        warn(file, cause, "failed to open; left in place")
        return null
    }

    /** Replaces [file] in one move, atomic where the file system allows; an I/O failure is transient. */
    fun write(file: File, plaintext: ByteArray) {
        val sealed = crypto.seal(plaintext, locationFor(file))
        try {
            stagingDir.mkdirs()
            file.parentFile?.mkdirs()
            val staging = Files.createTempFile(stagingDir.toPath(), "${flatName(file)}.", ".tmp").toFile()
            try {
                FileOutputStream(staging).use {
                    it.write(sealed)
                    it.fd.sync()
                }
                try {
                    Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    warn(file, e, "has no atomic move; moving non-atomically")
                    Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                staging.delete()
            }
        } catch (e: IOException) {
            throw TransientCryptoException("write failed for ${label(file)}", e)
        }
    }

    fun writeText(file: File, text: String) = write(file, text.encodeToByteArray())

    /**
     * Moves [file] aside as `.quarantine/<path, / as __>.<millis>[.n]`, or copies it there when
     * [keep], for a caller that replaces it in one move afterwards; null when that failed. [why]
     * is for the log.
     */
    fun quarantine(file: File, cause: Throwable, why: String = "failed to open", keep: Boolean = false): File? {
        val base = "${flatName(file)}.${clock()}"
        try {
            if (keep) keptCopyOf(file)?.let { return restamped(it, base).also(::syncCopy) }
            quarantineDir.mkdirs()
            for (n in 0 until MAX_QUARANTINE_COLLISIONS) {
                val dst = File(quarantineDir, if (n == 0) base else "$base.$n")
                try {
                    if (keep) Files.copy(file.toPath(), dst.toPath()).also { syncCopy(dst) } else Files.move(file.toPath(), dst.toPath())
                } catch (e: FileAlreadyExistsException) {
                    continue
                }
                warn(file, cause, "$why; quarantined")
                return dst
            }
            warn(file, cause, "$why and every quarantine name was taken")
        } catch (e: IOException) {
            warn(file, e, "$why and could not be quarantined")
        }
        return null
    }

    // The caller replaces the original next, so the copy reaches the disk first; its name, as QueueStore's renames, best effort.
    private fun syncCopy(copy: File) {
        FileChannel.open(copy.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        try {
            FileChannel.open(quarantineDir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            logger.w { "quarantine directory not synced: ${e::class.simpleName}" }
        }
    }

    // A copy used again takes the current time's name, so its days run from the replace it now backs.
    private fun restamped(copy: File, name: String): File = try {
        Files.move(copy.toPath(), File(quarantineDir, name).toPath()).toFile()
    } catch (e: IOException) {
        copy
    }

    /** A copy of [file] already in quarantine, so a replace that keeps failing does not pile copies up. */
    private fun keptCopyOf(file: File): File? {
        val prefix = "${flatName(file)}."
        val bytes = file.readBytes()
        return quarantineDir.listFiles().orEmpty().firstOrNull { it.name.startsWith(prefix) && it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }
    }

    /** Deletes the quarantined copies of [file]; false when one could not be deleted, a copy already gone counting as deleted. */
    fun deleteQuarantined(file: File): Boolean {
        val prefix = "${flatName(file)}."
        return quarantineDir.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.map { it.delete() || !it.exists() }.all { it }
    }

    /** Deletes staging files at least [minAgeMillis] old; a write still in flight past the gate would lose its file. */
    fun sweepStaging(minAgeMillis: Long = STAGING_SWEEP_MIN_AGE_MS, now: Long = clock()) {
        stagingDir.listFiles()?.forEach { if (now - it.lastModified() >= minAgeMillis) it.delete() }
    }

    /**
     * Deletes the files directly under [quarantineDir] set aside [maxAgeMillis] or more before
     * [now]. The time is the one [quarantine] put in the name, read off the clock of that moment; a
     * name without one goes by the file's modification time. A time ahead of [now] keeps the file,
     * and directories are left alone.
     */
    fun sweepQuarantine(maxAgeMillis: Long = QUARANTINE_MAX_AGE_MS, now: Long = clock()) {
        quarantineDir.listFiles()?.forEach { copy ->
            if (!copy.isDirectory && now - (setAsideAt(copy.name) ?: copy.lastModified()) >= maxAgeMillis) copy.delete()
        }
    }

    private fun setAsideAt(name: String): Long? = SET_ASIDE_TIME.find(name)?.groupValues?.get(1)?.toLongOrNull()

    private fun flatName(file: File): String = relativePath(file).replace("/", "__")

    private fun warn(file: File, cause: Throwable, what: String) = logger.w(cause) { "${label(file)} $what" }

    internal fun label(file: File): String = try {
        relativePath(file)
    } catch (e: IllegalArgumentException) {
        "a file outside the Notebook"
    }

    companion object {
        const val STAGING_DIR = ".staging"
        const val QUARANTINE_DIR = ".quarantine"
        const val STAGING_SWEEP_MIN_AGE_MS = 120_000L
        const val QUARANTINE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_QUARANTINE_COLLISIONS = 100

        // The end of a quarantine name, `.<millis>` or `.<millis>.<n>`; the flat name before it can hold dots and digits.
        private val SET_ASIDE_TIME = Regex("""\.(\d+)(?:\.\d{1,2})?\z""")

        private val logger = Logger.withTag("NotebookStorage")
    }
}
