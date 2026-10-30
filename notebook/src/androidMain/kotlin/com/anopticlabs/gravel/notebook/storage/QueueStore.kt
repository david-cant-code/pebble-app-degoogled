package com.anopticlabs.gravel.notebook.storage

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.needsReview
import com.anopticlabs.gravel.notebook.capture.wavDurationMillis
import com.anopticlabs.gravel.notebook.capture.capturedAtFromName
import com.anopticlabs.gravel.notebook.capture.isWatchRecordingName
import com.anopticlabs.gravel.notebook.capture.removeQueuedCapture
import com.anopticlabs.gravel.notebook.capture.retryFailedCaptures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** A queued capture's audio as read back from its blob. */
sealed interface BlobRead {
    class Present(val bytes: ByteArray) : BlobRead

    data object Absent : BlobRead

    /** The blob failed to open and was moved to quarantine; the entry is left for the caller to flag. */
    data object Corrupt : BlobRead
}

/** What [QueueStore.salvagePending] did: the ids it queued, and how many files it kept for a later attempt. */
data class SalvageResult(val queued: List<String>, val kept: Int)

/**
 * The capture queue: `queue_meta`, the list of [QueuedCapture] entries, one encrypted blob per
 * entry under `queue/`, and the plaintext captures the store could not seal, held under
 * `pending_captures/` until a later capture run queues them. The `queue_meta` lock from
 * [NotebookLocks] covers the entries and the blobs: every mutator and [readBlob] run on
 * [Dispatchers.IO] under it and read strictly before they write; the non-suspend [readQueue]
 * blocks on file and Keystore I/O. `pending_captures/` is not locked: [salvagePending] callers
 * serialize its runs, as `NotebookHousekeeping` does.
 * `queue_meta` is never quarantined by a read.
 */
class QueueStore internal constructor(
    root: File,
    crypto: Cryptographer,
    private val maxMetaBytes: Long = MAX_QUEUE_META_BYTES,
    private val clock: () -> Long,
) {

    constructor(root: File, crypto: Cryptographer) : this(root, crypto, MAX_QUEUE_META_BYTES, System::currentTimeMillis)

    private val files = EncryptedFiles(root, crypto, clock)
    private val queueDir = File(files.root, QUEUE_DIR)
    private val metaFile = File(files.root, QUEUE_META_FILE)
    private val pendingDir = File(files.root, PENDING_DIR)
    private val lock = NotebookLocks.lockFor(QUEUE_META_FILE)

    /** The queued entries; an entry with an invalid id or a duplicated id is a format failure. */
    fun readQueue(strict: Boolean = false): List<QueuedCapture> {
        val json = files.readText(metaFile, strict, quarantineCorrupt = false, maxBytes = maxMetaBytes) ?: return emptyList()
        return parsePersisted(strict, QUEUE_META_FILE, { emptyList() }) {
            NotebookJson.decodeFromString<List<QueuedCapture>>(json).also { requireWellFormed(it) }
        }
    }

    /**
     * Writes [bytes] as the blob of [id], then appends its entry, both under the lock, and deletes
     * the blob again if the entry write fails. An id already queued keeps its entry, and its blob
     * is written only if it is missing; the entry takes the length of a WAV file. Returns whether
     * [bytes] are now the blob of [id].
     */
    suspend fun enqueue(id: String, bytes: ByteArray, originalFileName: String, queuedAtMillis: Long = clock()): Boolean =
        enqueue(QueuedCapture(id, originalFileName, queuedAtMillis, durationMillis = wavDurationMillis(bytes)), bytes)

    /** [enqueue] of an entry with its attempt count and flag, as a restore carries them. */
    suspend fun enqueue(entry: QueuedCapture, bytes: ByteArray): Boolean = io {
        val blob = blobFile(entry.id)
        require(bytes.size <= MAX_BLOB_BYTES) { "capture ${entry.id} is ${bytes.size} bytes, over the blob cap" }
        lock.withLock {
            val current = readQueue(strict = true)
            if (current.any { it.id == entry.id }) {
                if (blob.exists()) return@withLock false
                files.write(blob, bytes)
                return@withLock true
            }
            files.write(blob, bytes)
            try {
                writeQueue(current + entry)
            } catch (e: Exception) {
                blob.delete()
                throw e
            }
            true
        }
    }

    /** What [enqueueAll] did per entry: queued with its blob, already queued, or left out for want of room. */
    data class EnqueueAllResult(val added: Int, val kept: Int, val skipped: Int)

    /**
     * [enqueue] for a batch under one lock section and one queue write: an id already queued is
     * kept, an entry the queue file has no room for is skipped, the rest get their blob from
     * [bytesOf] and their entry. A failed queue write deletes the blobs it wrote.
     */
    suspend fun enqueueAll(entries: List<QueuedCapture>, bytesOf: (QueuedCapture) -> ByteArray): EnqueueAllResult = io {
        entries.forEach { requireValidId(it.id) }
        lock.withLock {
            val current = readQueue(strict = true)
            val known = current.map { it.id }.toMutableSet()
            val queue = current.toMutableList()
            var queueBytes = NotebookJson.encodeToString(current).length
            val written = mutableListOf<File>()
            var added = 0
            var kept = 0
            var skipped = 0
            try {
                for (entry in entries) {
                    val blob = blobFile(entry.id)
                    if (entry.id in known) {
                        if (!blob.exists()) files.write(blob, bytesOf(entry))
                        kept++
                        continue
                    }
                    val entryBytes = NotebookJson.encodeToString(entry).length + 1
                    if (queueBytes + entryBytes + NotebookEnvelope.MIN_LEN > maxMetaBytes) {
                        skipped++
                        continue
                    }
                    val bytes = bytesOf(entry)
                    require(bytes.size <= MAX_BLOB_BYTES) { "capture ${entry.id} is ${bytes.size} bytes, over the blob cap" }
                    files.write(blob, bytes)
                    written += blob
                    queue += entry
                    known += entry.id
                    queueBytes += entryBytes
                    added++
                }
                if (added > 0) writeQueue(queue)
            } catch (e: Exception) {
                written.forEach { it.delete() }
                throw e
            }
            EnqueueAllResult(added, kept, skipped)
        }
    }

    /** Replaces the queue with [transform] of the current one; the lambda must not call back into the store. */
    suspend fun update(transform: (List<QueuedCapture>) -> List<QueuedCapture>): List<QueuedCapture> = io {
        lock.withLock {
            val current = readQueue(strict = true)
            val updated = transform(current)
            if (updated != current) writeQueue(updated)
            updated
        }
    }

    /** Removes the entry, then its blob, so an interruption leaves at most a blob without an entry. */
    suspend fun remove(id: String): List<QueuedCapture> = io {
        val blob = blobFile(id)
        lock.withLock {
            val current = readQueue(strict = true)
            val updated = removeQueuedCapture(current, id)
            if (updated != current) writeQueue(updated)
            blob.delete()
            updated
        }
    }

    /** What [removeFailed] did. */
    enum class FailedRemoval { REMOVED, GONE, NOT_FLAGGED }

    /**
     * Removes the flagged entry [id]: any quarantined copies of its blob first, then the entry, then
     * the blob, best effort as in [remove]. An entry [busy] names counts as not flagged; it is asked
     * under the queue lock, which the drain's attempt write also takes.
     */
    suspend fun removeFailed(id: String, busy: (String) -> Boolean = { false }): FailedRemoval = io {
        val blob = blobFile(id)
        lock.withLock {
            val current = readQueue(strict = true)
            val entry = current.firstOrNull { it.id == id } ?: return@withLock FailedRemoval.GONE
            if (!entry.needsReview || busy(id)) return@withLock FailedRemoval.NOT_FLAGGED
            if (!files.deleteQuarantined(blob)) throw IOException("a quarantined copy of capture $id could not be deleted")
            writeQueue(removeQueuedCapture(current, id))
            blob.delete()
            FailedRemoval.REMOVED
        }
    }

    /** True when an entry changed; one whose blob is gone, or that [busy] names, is left flagged, see [retryFailedCaptures]. */
    suspend fun retryFailed(ids: Set<String>, busy: (String) -> Boolean = { false }): Boolean = io {
        lock.withLock {
            val current = readQueue(strict = true)
            val updated = retryFailedCaptures(current, ids.filterTo(HashSet()) { hasBlob(it) && !busy(it) })
            if (updated != current) writeQueue(updated)
            updated != current
        }
    }

    fun hasBlob(id: String): Boolean = blobFile(id).exists()

    /**
     * A transient or key failure propagates, so the caller retries later instead of taking the
     * entry for stale; a corrupt blob is moved to quarantine unless [quarantineCorrupt] is false.
     */
    suspend fun readBlob(id: String, quarantineCorrupt: Boolean = true): BlobRead = io {
        val blob = blobFile(id)
        lock.withLock {
            try {
                files.read(blob, strict = true, quarantineCorrupt = false, maxBytes = MAX_BLOB_FILE_BYTES)?.let { BlobRead.Present(it) }
                    ?: BlobRead.Absent
            } catch (e: CorruptFileException) {
                if (quarantineCorrupt) files.quarantine(blob, e)
                BlobRead.Corrupt
            }
        }
    }

    /**
     * Replaces a damaged `queue_meta`, copied to quarantine first, in one move with an entry for each blob that
     * opens ([RECOVERED_NAME], queued at the blob's time, or now when that is unusable); a blob that fails to open is quarantined. Null when it reads.
     * A blob directory that exists but cannot be listed is a transient failure, with nothing replaced.
     */
    suspend fun rebuildIfDamaged(): Int? = io {
        lock.withLock {
            val damage = try {
                readQueue(strict = true)
                return@withLock null
            } catch (e: CorruptFileException) {
                e
            } catch (e: PersistedFormatException) {
                e
            }
            val now = clock()
            val listed = when {
                !queueDir.exists() || queueDir.isFile -> emptyArray()
                else -> queueDir.listFiles() ?: throw TransientCryptoException("could not list the recordings", IOException("listFiles returned null"))
            }
            val entries = listed
                .filter { it.isFile && it.name.endsWith(BLOB_EXTENSION) && isValidId(it.name.removeSuffix(BLOB_EXTENSION)) }
                .sortedWith(compareBy({ it.lastModified() }, { it.name }))
                .mapNotNull { blob ->
                    val queuedAt = blob.lastModified().takeIf { it in 1..now } ?: now
                    files.read(blob, strict = true, maxBytes = MAX_BLOB_FILE_BYTES)?.let {
                        val length = wavDurationMillis(it)
                        it.fill(0)
                        QueuedCapture(blob.name.removeSuffix(BLOB_EXTENSION), RECOVERED_NAME, queuedAt, durationMillis = length)
                    }
                }
            files.quarantine(metaFile, damage, "is damaged", keep = true) ?: throw TransientCryptoException("$QUEUE_META_FILE could not be set aside", damage)
            writeQueue(entries)
            entries.size
        }
    }

    /** The plaintext length of [id]'s blob from its file size, or null when there is no blob; no key is used. */
    suspend fun blobLength(id: String): Long? = io {
        val blob = blobFile(id)
        lock.withLock { if (blob.exists()) blob.length() - NotebookEnvelope.MIN_LEN else null }
    }

    /**
     * Deletes blobs with no entry that are at least [minAgeMillis] old; a file that is not a blob
     * is left alone. Runs under the lock, so a blob being enqueued is never seen without its entry;
     * the age gate spares a blob written by anything else until it is old. An absent or unreadable
     * queue file skips the sweep. Returns the number deleted.
     */
    suspend fun sweepOrphanBlobs(minAgeMillis: Long = ORPHAN_SWEEP_MIN_AGE_MS, now: Long = clock()): Int = io {
        lock.withLock {
            if (!metaFile.exists()) return@withLock 0
            val known = try {
                readQueue(strict = true).map { it.id }.toSet()
            } catch (e: Exception) {
                if (!isStorageFailure(e)) throw e
                logger.w(e) { "queue could not be read; orphan blob sweep skipped" }
                return@withLock 0
            }
            var deleted = 0
            queueDir.listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(BLOB_EXTENSION)) return@forEach
                if (now - f.lastModified() < minAgeMillis) return@forEach
                if (f.name.removeSuffix(BLOB_EXTENSION) !in known && f.delete()) deleted++
            }
            deleted
        }
    }

    /**
     * Moves a plaintext capture the store could not seal to `pending_captures/<id>~<name>` for
     * [salvagePending] on a later capture run; null, with the file left where it is, when a file is
     * already pending under [id], the name or size is not one the store accepts, or the move fails.
     */
    suspend fun retainPending(file: File, id: String): File? = io {
        requireValidId(id)
        val name = file.name
        if (!ORIGINAL_NAME.matches(name)) {
            logger.w { "capture $name could not be kept for a later attempt: not a plain file name" }
            return@io null
        }
        if (file.length() > MAX_BLOB_BYTES) {
            logger.w { "capture $name could not be kept for a later attempt: over the blob cap" }
            return@io null
        }
        try {
            pendingDir.mkdirs()
            if (pendingDir.listFiles().orEmpty().any { it.name.startsWith("$id$SEPARATOR") }) {
                logger.w { "capture $name could not be kept for a later attempt: $id is already pending" }
                return@io null
            }
            val target = File(pendingDir, "$id$SEPARATOR$name")
            Files.move(file.toPath(), target.toPath())
            logger.w { "capture $name could not be sealed; kept as ${target.name} for a later attempt" }
            target
        } catch (e: IOException) {
            logger.w(e) { "capture $name could not be kept for a later attempt" }
            null
        }
    }

    /**
     * [retainPending] for a recording held in memory: [bytes] go to a partial file that is synced,
     * then renamed to `<id>~<name>`, so [salvagePending] never reads a half-written recording.
     */
    suspend fun retainPending(bytes: ByteArray, id: String, name: String): File? = io {
        requireValidId(id)
        if (!ORIGINAL_NAME.matches(name) || bytes.size > MAX_BLOB_BYTES) {
            logger.w { "capture $id could not be kept for a later attempt: name or size not accepted" }
            return@io null
        }
        val part = File(pendingDir, "$PART_PREFIX$id$SEPARATOR$name")
        try {
            pendingDir.mkdirs()
            if (pendingDir.listFiles().orEmpty().any { it.name.startsWith("$id$SEPARATOR") }) {
                logger.w { "capture $id could not be kept for a later attempt: $id is already pending" }
                return@io null
            }
            FileOutputStream(part).use {
                it.write(bytes)
                it.fd.sync()
            }
            val target = File(pendingDir, "$id$SEPARATOR$name")
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(pendingDir)
            logger.w { "capture $id could not be sealed; kept as ${target.name} for a later attempt" }
            target
        } catch (e: IOException) {
            part.delete()
            logger.w(e) { "capture $id could not be kept for a later attempt" }
            null
        }
    }

    /**
     * Queues every regular file under `pending_captures/` named `<id>~<name>`, deleting the
     * plaintext once [id]'s blob holds its bytes; a file whose enqueue fails or that is over the
     * cap is kept for a later attempt, an empty one is deleted, a partial file at least
     * [ORPHAN_SWEEP_MIN_AGE_MS] old is deleted, and any other file is left alone. A file kept or
     * left alone is logged once per process, since every capture run calls this.
     */
    suspend fun salvagePending(): SalvageResult = io {
        val pending = pendingDir.listFiles()?.sortedBy { it.name } ?: return@io SalvageResult(emptyList(), 0)
        val queued = mutableListOf<String>()
        var kept = 0
        for (f in pending) {
            if (f.name.startsWith(PART_PREFIX)) {
                // Younger ones may still be written by retainPending.
                if (clock() - f.lastModified() >= ORPHAN_SWEEP_MIN_AGE_MS && f.delete()) {
                    logger.w { "${f.name} under pending captures was never completed; deleted" }
                }
                continue
            }
            if (!isRetained(f)) {
                warnOnce(f) { "${f.name} under pending captures is not a retained capture; left alone" }
                continue
            }
            val id = f.name.substringBefore(SEPARATOR)
            val name = f.name.substringAfter(SEPARATOR, "")
            if (f.length() == 0L) {
                logger.w { "${f.name} under pending captures is empty; deleted" }
                f.delete()
                continue
            }
            if (f.length() > MAX_BLOB_BYTES) {
                warnOnce(f) { "${f.name} under pending captures is over the blob cap; kept unread" }
                kept++
                continue
            }
            try {
                val now = clock()
                val bytes = f.readBytes()
                // Dated at the start the capture path wrote into the name, else when the file was written.
                val queuedAt = capturedAtFromName(name)?.takeIf { it in 1..now } ?: f.lastModified().takeIf { it in 1..now } ?: now
                val entry = QueuedCapture(id, name, queuedAt, durationMillis = wavDurationMillis(bytes), fromWatch = isWatchRecordingName(name))
                val stored = enqueue(entry, bytes)
                if (!stored && !holdsBlob(id, bytes)) {
                    warnOnce(f) { "${f.name} under pending captures differs from the queued blob of $id; kept" }
                    kept++
                    continue
                }
                if (!f.delete()) logger.w { "${f.name} under pending captures was queued but could not be deleted" }
                queued += id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (reportedPending.add(f.name)) logger.w(e) { "pending capture ${f.name} could not be queued this time; kept" }
                kept++
            }
        }
        SalvageResult(queued, kept)
    }

    /** How many recordings wait under `pending_captures/`; an empty file, which [salvagePending] deletes, is not one. */
    suspend fun retainedCount(): Int = io { pendingDir.listFiles().orEmpty().count { isRetained(it) && it.length() > 0 } }

    /** How many of those were made on the watch. */
    suspend fun retainedWatchCount(): Int = io {
        pendingDir.listFiles().orEmpty().count { isRetained(it) && it.length() > 0 && isWatchRecordingName(it.name.substringAfter(SEPARATOR)) }
    }

    private val reportedPending = ConcurrentHashMap.newKeySet<String>()

    private fun warnOnce(f: File, message: () -> String) {
        if (reportedPending.add(f.name)) logger.w { message() }
    }

    private fun isRetained(f: File): Boolean =
        f.isFile && !Files.isSymbolicLink(f.toPath()) &&
            isValidId(f.name.substringBefore(SEPARATOR)) && ORIGINAL_NAME.matches(f.name.substringAfter(SEPARATOR, ""))

    private suspend fun holdsBlob(id: String, bytes: ByteArray): Boolean =
        (readBlob(id) as? BlobRead.Present)?.bytes?.contentEquals(bytes) == true

    // A rename reaches the disk with its directory; unsynced, a power loss can bring the partial name back.
    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            logger.w { "pending captures directory not synced: ${e::class.simpleName}" }
        }
    }

    private fun writeQueue(queue: List<QueuedCapture>) {
        requireWellFormed(queue)
        val plaintext = NotebookJson.encodeToString(queue).encodeToByteArray()
        require(plaintext.size + NotebookEnvelope.MIN_LEN <= maxMetaBytes) { "$QUEUE_META_FILE would be over its $maxMetaBytes byte cap" }
        files.write(metaFile, plaintext)
    }

    private fun requireWellFormed(queue: List<QueuedCapture>) {
        require(queue.all { isValidId(it.id) }) { "queue entry with an invalid id" }
        require(queue.distinctBy { it.id }.size == queue.size) { "queue entries share an id" }
    }

    private fun blobFile(id: String): File {
        requireValidId(id)
        return File(queueDir, "$id$BLOB_EXTENSION")
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val QUEUE_DIR = "queue"
        const val QUEUE_META_FILE = "queue_meta"
        const val PENDING_DIR = "pending_captures"
        const val BLOB_EXTENSION = ".bin"

        /** The original name of a recording whose entry was rebuilt from its blob. */
        const val RECOVERED_NAME = "recovered"

        /** A blob is sealed and opened in memory as a whole. */
        const val MAX_BLOB_BYTES = 32 * 1024 * 1024
        const val MAX_QUEUE_META_BYTES = 4L * 1024 * 1024
        const val ORPHAN_SWEEP_MIN_AGE_MS = 120_000L
        private const val MAX_BLOB_FILE_BYTES = MAX_BLOB_BYTES.toLong() + NotebookEnvelope.MIN_LEN

        /** Not an id character, so a pending file name splits into its id and the recording's name. */
        private const val SEPARATOR = "~"
        private const val PART_PREFIX = ".part-"
        private val ORIGINAL_NAME = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}$")

        fun isValidId(id: String): Boolean = isValidStoreId(id)

        /** A recording's own file name, as the capture path names files. */
        fun isValidOriginalName(name: String): Boolean = ORIGINAL_NAME.matches(name)

        private fun requireValidId(id: String) = require(isValidId(id)) { "not a capture id: $id" }

        private val logger = Logger.withTag("NotebookStorage")
    }
}
