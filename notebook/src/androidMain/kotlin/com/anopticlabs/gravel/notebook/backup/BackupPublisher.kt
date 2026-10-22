package com.anopticlabs.gravel.notebook.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The operations on a picked document that [BackupPublisher] needs, over a handle type [H]. */
interface BackupDocumentOps<H> {
    fun describe(handle: H): BackupDocumentInfo?

    /** A truncating write; throws when the document cannot be opened, with nothing truncated. */
    @Throws(IOException::class)
    fun openWrite(handle: H): OutputStream

    @Throws(IOException::class)
    fun openRead(handle: H): InputStream

    /** True only when the document is known to be gone; never throws. */
    fun delete(handle: H): Boolean

    /** Keeps access to the document past the end of the process until [release]; false without a grant to keep. Never throws. */
    fun hold(handle: H): Boolean = false

    fun release(handle: H) {}
}

data class BackupDocumentInfo(val displayName: String, val sizeBytes: Long? = null)

/** The archive a publish copies: [size] bytes that [open] reads from the start. */
class BackupSource(val size: Long, val open: () -> InputStream)

/** A verified archive in the picked document, which the provider names [displayName] (null when it reports no name). */
data class BackupPublishOutcome(val displayName: String?, val byteCount: Long)

/** How far a publish got. Up to [NOT_OPENED] nothing was written to the document. */
enum class PublishFailure { NOT_EMPTY, NOT_CHECKED, NOT_OPENED, NOT_WRITTEN, NOT_READ_BACK, NOT_VERIFIED }

/** No usable archive was written to [fileName]; [removed] says whether the document was then deleted, null when nothing tried. */
class BackupPublishException(val failure: PublishFailure, val fileName: String?, val removed: Boolean?, cause: Throwable? = null) :
    Exception("backup not published: $failure, removed=$removed", cause)

/**
 * Copies a finished archive into the document a save picker created, under the name it has, and reads it back
 * through the caller's verify step. A document that holds data, or whose emptiness cannot be read, is refused
 * before any byte is written, so a failed save destroys no earlier file; a document written but not verified is
 * deleted, as is an empty one that would not open for writing.
 */
object BackupPublisher {

    /**
     * [onWriting] runs once the document is known to be empty, before its first byte is written; a throw stops the
     * publish there. [verify] reads the document back and throws when it is not the archive that was written.
     */
    fun <H> publish(
        ops: BackupDocumentOps<H>,
        target: H,
        source: BackupSource,
        onWriting: () -> Unit = {},
        verify: (InputStream) -> Unit,
    ): BackupPublishOutcome {
        require(source.size > 0) { "refusing to publish an empty backup" }
        // Opened before the document is touched, so a source that will not open changes nothing.
        return source.open().use { input -> publishFrom(ops, target, input, source.size, onWriting, verify) }
    }

    private fun <H> publishFrom(
        ops: BackupDocumentOps<H>,
        target: H,
        input: InputStream,
        size: Long,
        onWriting: () -> Unit,
        verify: (InputStream) -> Unit,
    ): BackupPublishOutcome {
        val info = ops.describe(target)
        val name = info?.displayName
        if ((info?.sizeBytes ?: 0L) > 0L) throw BackupPublishException(PublishFailure.NOT_EMPTY, name, null)
        val empty = try {
            ops.openRead(target).use { it.read() < 0 }
        } catch (e: Exception) {
            throw BackupPublishException(PublishFailure.NOT_CHECKED, name, null, e)
        }
        if (!empty) throw BackupPublishException(PublishFailure.NOT_EMPTY, name, null)
        onWriting()

        val out = try {
            ops.openWrite(target)
        } catch (e: Exception) {
            throw BackupPublishException(PublishFailure.NOT_OPENED, name, ops.delete(target), e)
        }
        try {
            val copied = out.use { input.copyTo(it) }
            if (copied != size) throw IOException("copied $copied of $size bytes")
        } catch (e: Throwable) {
            val removed = ops.delete(target)
            if (e is Error) throw e
            throw BackupPublishException(PublishFailure.NOT_WRITTEN, name, removed, e)
        }
        val readBack = try {
            ops.openRead(target)
        } catch (e: Exception) {
            throw BackupPublishException(PublishFailure.NOT_READ_BACK, name, ops.delete(target), e)
        }
        try {
            readBack.use(verify)
        } catch (e: Throwable) {
            val removed = ops.delete(target)
            if (e is Error) throw e
            throw BackupPublishException(if (e is IOException) PublishFailure.NOT_READ_BACK else PublishFailure.NOT_VERIFIED, name, removed, e)
        }
        return BackupPublishOutcome(ops.describe(target)?.displayName ?: name, size)
    }
}
