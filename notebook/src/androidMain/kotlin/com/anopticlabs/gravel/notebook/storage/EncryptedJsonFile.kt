package com.anopticlabs.gravel.notebook.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What a [EncryptedJsonFile.modify] block decided: the value to store and the result to return. */
internal class Modified<T, R>(val value: T, val result: R)

/**
 * One store file holding an encrypted JSON value, never quarantined by a read, read strictly under
 * its process-wide lock before every write; [validate] and the [maxBytes] cap apply on read and
 * on write, so a file this reader would refuse is never written.
 */
internal class EncryptedJsonFile<T>(
    private val files: EncryptedFiles,
    private val file: File,
    private val serializer: KSerializer<T>,
    private val maxBytes: Long,
    private val empty: () -> T,
    private val validate: (T) -> Unit,
) {
    private val lock = NotebookLocks.lockFor(file.name)
    private val writes = revisions.getOrPut(file.absoluteFile.normalize().path) { MutableStateFlow(0L) }

    /** Counts the writes to this file that landed, process-wide per file. */
    val revision: StateFlow<Long> = writes.asStateFlow()

    /** Absent reads as [empty]; [strict] rethrows a transient, key, corrupt or format failure instead of degrading to it. */
    fun read(strict: Boolean = false): T {
        val json = files.readText(file, strict, quarantineCorrupt = false, maxBytes = maxBytes) ?: return empty()
        return parsePersisted(strict, file.name, empty) {
            NotebookJson.decodeFromString(serializer, json).also(validate)
        }
    }

    /** Runs [block] on the strict read under the lock, on the I/O dispatcher, and writes its value when it differs. */
    suspend fun <R> modify(block: (T) -> Modified<T, R>): R = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read(strict = true)
            val modified = block(current)
            if (modified.value != current) write(modified.value)
            modified.result
        }
    }

    /**
     * Moves the file to quarantine when a strict read under the lock still finds it corrupt or
     * unparseable, so it reads as empty; returns whether it moved it. A transient or key failure
     * propagates with the file in place.
     */
    suspend fun setAsideIfDamaged(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val damage = try {
                read(strict = true)
                return@withLock false
            } catch (e: CorruptFileException) {
                e
            } catch (e: PersistedFormatException) {
                e
            }
            moveAside(damage, "is damaged")
            true
        }
    }

    /** Moves the file to quarantine whatever it holds; false when there was none. */
    suspend fun setAside(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.exists()) return@withLock false
            moveAside(IllegalStateException("set aside"), "set aside with the part it belongs to")
            true
        }
    }

    private fun moveAside(cause: Exception, why: String) {
        files.quarantine(file, cause, why) ?: throw TransientCryptoException("${file.name} could not be set aside", cause)
        writes.update { it + 1 }
    }

    private fun write(value: T) {
        validate(value)
        val plaintext = NotebookJson.encodeToString(serializer, value).encodeToByteArray()
        require(plaintext.size + NotebookEnvelope.MIN_LEN <= maxBytes) { "${file.name} would be over its $maxBytes byte cap" }
        files.write(file, plaintext)
        writes.update { it + 1 }
    }

    suspend fun update(block: (T) -> T): T = modify { current ->
        val next = block(current)
        Modified(next, next)
    }

    companion object {
        private val revisions = ConcurrentHashMap<String, MutableStateFlow<Long>>()

        /** A capped list of a few dozen short entries stays far under this. */
        const val SMALL_FILE_BYTES = 4L * 1024 * 1024

        /** The event log grows until retention prunes it. */
        const val LOG_FILE_BYTES = 16L * 1024 * 1024

        fun <T> list(files: EncryptedFiles, file: File, element: KSerializer<T>, maxBytes: Long, validate: (List<T>) -> Unit) =
            EncryptedJsonFile(files, file, ListSerializer(element), maxBytes, ::emptyList, validate)
    }
}
