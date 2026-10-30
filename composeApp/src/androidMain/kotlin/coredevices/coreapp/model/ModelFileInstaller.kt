package coredevices.coreapp.model

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.models.ModelCatalog
import com.anopticlabs.gravel.models.ModelStorageException
import com.anopticlabs.gravel.models.PinnedModel
import com.anopticlabs.gravel.network.readBody
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Verified download core for the pinned model catalog: streams one model
 * file from its commit-pinned URL, verifies it against the catalog pin,
 * and installs it at [modelsDir]/<id>/<fileName>.
 *
 * The file feeds a native parser, so nothing unverified may become
 * loadable. The layers, each defensible alone:
 *  - the download URL resolves the immutable catalog commit, so a
 *    retargeted branch or re-uploaded file cannot swap the bytes;
 *  - the received bytes are hashed while streaming and must match the
 *    pinned SHA-256 and exact size before the file is promoted out of
 *    staging, with a mid-stream abort as soon as the pinned size is
 *    exceeded;
 *  - [verifyOnLoad] re-hashes the installed file before the engine sees
 *    it and quarantines a mismatch, so on-disk tampering or corruption
 *    after install is also caught.
 *
 * Fail-closed boundary for the partial file: a download that completed
 * with wrong bytes (digest mismatch, oversize) deletes the partial, since
 * resuming from wrong bytes can never produce a right file; a download
 * that merely stopped early (dropped connection, stall, cancellation)
 * keeps it, and the next attempt continues with an HTTP Range request.
 * A resume is appended only when the answer is a 206 whose Content-Range
 * starts at the resume offset; a 200, or a 206 starting elsewhere,
 * restarts from zero. The final digest gate runs on the complete file
 * either way, so a lying resume can waste a transfer but never install
 * wrong bytes.
 *
 * Context-free on purpose (only [File] roots and an [HttpClient]), which
 * keeps the whole pipeline under JVM unit tests with a mock engine and
 * temp directories.
 */
class ModelFileInstaller(
    private val httpClient: HttpClient,
    private val modelsDir: File,
    // Injected so tests can disable it: under runTest's virtual clock the
    // MockEngine body writer races this timeout (Ktor delivers the body on
    // a real dispatcher), and multi-chunk transfers lose spuriously.
    // Production always uses the default.
    private val readStallTimeout: Duration = READ_STALL_TIMEOUT,
    private val retryBackoff: Duration = RETRY_BACKOFF,
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val openPartial: (File, Boolean) -> OutputStream = { file, append -> FileOutputStream(file, append) },
) {
    companion object {
        private val logger = Logger.withTag("ModelFileInstaller")
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        private val READ_STALL_TIMEOUT = 30.seconds
        private val RETRY_BACKOFF = 1.seconds
        internal const val MAX_STALLS = 5
        internal const val MAX_RESTARTS = 3
        internal const val MAX_ATTEMPTS = 1000
        internal const val MIN_PROGRESS_BYTES = 1024L * 1024L

        // Staging lives under modelsDir so promotion is a same-filesystem
        // atomic rename; the dot name keeps it out of directory listings
        // that treat modelsDir children as installed models.
        internal const val STAGING_DIR = ".staging"
        internal const val PARTIAL_SUFFIX = ".partial"
        internal const val QUARANTINE_SUFFIX = ".quarantined"

        internal fun isRetriedStatus(status: Int): Boolean =
            status == 408 || status == 425 || status == 429 || status in 500..599

        internal fun retryDelay(stalls: Int, base: Duration): Duration =
            if (stalls == 0) base / 4 else base * (1 shl (stalls - 1))

        /** A certificate the server failed on will fail the same way again, so only transport trouble retries. */
        internal fun isTransientFailure(failure: Throwable): Boolean {
            if (failure is HttpStatusException) return isRetriedStatus(failure.status)
            val certificateFailure = generateSequence(failure) { it.cause }.take(16).any {
                it is SSLPeerUnverifiedException || it is CertificateException || it is CertPathValidatorException
            }
            return failure is IOException && !certificateFailure
        }
    }

    private val stagingDir: File get() = modelsDir.resolve(STAGING_DIR)

    fun installedFile(model: PinnedModel): File =
        modelsDir.resolve(model.id).resolve(model.fileName)

    /**
     * Cheap installed check: exact pinned size at the expected path. The
     * deep content check is [verifyOnLoad]; this one exists for UI state
     * and sweep decisions where hashing hundreds of MB is not acceptable.
     */
    fun isInstalled(model: PinnedModel): Boolean =
        installedFile(model).let { it.isFile && it.length() == model.sizeBytes }

    /** The name carries the pin, so a re-pinned model never resumes the previous pin's bytes. */
    internal fun partialFile(model: PinnedModel): File =
        stagingDir.resolve("${model.id}.${model.sha256.take(16)}$PARTIAL_SUFFIX")

    /** Gravel: the bytes kept for [model]'s resume; 0 without any. */
    fun partialBytes(model: PinnedModel): Long = partialFile(model).length()

    /** Gravel: deletes the bytes kept for [model]'s resume. Callers serialize it with installs of the model. */
    fun discardPartial(model: PinnedModel) {
        partialFile(model).delete()
    }

    private fun quarantineFile(model: PinnedModel): File =
        stagingDir.resolve("${model.id}$QUARANTINE_SUFFIX")

    private enum class Outcome { Done, RestartClean }

    private class HttpStatusException(val status: Int, url: String) : Exception("HTTP $status for $url")

    private class StallException(url: String) : IOException("Download stalled for $url")

    private class ShortBodyException(message: String) : IOException(message)

    /** Bytes both in the digest and in the partial; every attempt first checks the partial still has exactly these. */
    private class Progress(var offset: Long)

    /**
     * Ensures the pinned file for [model] is installed, downloading (or
     * resuming) as needed. A dropped or stalled transfer, and an HTTP 408,
     * 425, 429 or 5xx, is retried with resume until [MAX_STALLS] attempts
     * in a row each add less than [MIN_PROGRESS_BYTES]. Throws
     * [SecurityException] when received bytes fail the pin,
     * [ModelStorageException] when the disk cannot hold or take the file,
     * and another exception for any other failure, which leaves a
     * resumable partial. Callers serialize per model id.
     */
    suspend fun install(model: PinnedModel) {
        stagingDir.mkdirs()
        val partial = partialFile(model)

        // A fully-downloaded-but-unpromoted partial (process death between
        // the digest gate and the rename) is hashed in place rather than
        // re-pulled; wrong bytes fall through to a clean download.
        if (partial.isFile && partial.length() >= model.sizeBytes) {
            if (partial.length() == model.sizeBytes && hashFile(partial) == model.sha256) {
                promote(partial, model)
                return
            }
            partial.delete()
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val progress = Progress(partial.lengthOrZero())
        if (progress.offset > 0) seedDigest(digest, partial)
        val needed = model.sizeBytes - progress.offset
        if (usableSpace(stagingDir) < needed) {
            throw ModelStorageException("Not enough free storage for ${model.id}: $needed bytes needed", outOfSpace = true)
        }

        var stalls = 0
        var restarts = 0
        var lastFailure: Exception? = null
        repeat(MAX_ATTEMPTS) {
            if (partial.lengthOrZero() != progress.offset) {
                logger.w { "The partial for ${model.id} changed outside the download; restarting from zero" }
                digest.reset()
                progress.offset = 0
                partial.delete()
            }
            // A transfer that failed after its last byte needs no request.
            if (progress.offset == model.sizeBytes) {
                verifyAndPromote(partial, digest, model)
                return
            }
            val attemptStart = progress.offset
            val outcome = try {
                downloadOnto(partial, progress, digest, model)
            } catch (e: CancellationException) {
                logger.i { "Model download canceled for ${model.id}" }
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!isTransientFailure(e)) {
                    logger.e(e) { "Model download failed for ${model.id}" }
                    throw e
                }
                lastFailure = e
                null
            }
            when (outcome) {
                Outcome.Done -> {
                    verifyAndPromote(partial, digest, model)
                    return
                }
                Outcome.RestartClean -> {
                    if (++restarts > MAX_RESTARTS) {
                        throw Exception("Download for ${model.id} failed: the server keeps ignoring resume requests")
                    }
                    logger.w { "Resume for ${model.id} not honored by the server; restarting from zero" }
                    digest.reset()
                    progress.offset = 0
                    partial.delete()
                    return@repeat
                }
                null -> {}
            }
            stalls = if (progress.offset - attemptStart >= MIN_PROGRESS_BYTES) 0 else stalls + 1
            logger.w(lastFailure) {
                "Download attempt for ${model.id} failed at ${progress.offset} of ${model.sizeBytes} bytes " +
                    "($stalls without progress)"
            }
            if (stalls >= MAX_STALLS) {
                throw Exception(
                    "Download for ${model.id} failed after $MAX_STALLS attempts without progress; " +
                        "partial kept for resume",
                    lastFailure,
                )
            }
            delay(retryDelay(stalls, retryBackoff))
        }
        throw Exception("Download for ${model.id} gave up after $MAX_ATTEMPTS attempts; partial kept for resume", lastFailure)
    }

    /**
     * Re-hashes the installed file against its pin. On mismatch the file
     * is quarantined (moved aside under staging, deleted if even that
     * fails) and treated as not installed, so the engine never parses it;
     * the evidence is kept for one generation per model id.
     */
    suspend fun verifyOnLoad(model: PinnedModel): Boolean {
        val file = installedFile(model)
        if (!file.isFile) return false
        if (file.length() == model.sizeBytes && hashFile(file) == model.sha256) return true

        logger.e { "Installed model ${model.id} failed load-time verification; quarantining it" }
        stagingDir.mkdirs()
        val quarantine = quarantineFile(model)
        quarantine.delete()
        if (!file.renameTo(quarantine)) {
            file.delete()
        }
        // The now-empty model dir must not read as installed to the cheap
        // checks or linger for the sweep.
        file.parentFile?.deleteRecursively()
        return false
    }

    /**
     * Deletes everything in staging except the partial of each catalog
     * model's current pin and its quarantined file, returning the names
     * removed. An install in flight for a catalog model keeps its partial.
     */
    fun pruneOrphanedStaging(): List<String> {
        val kept = ModelCatalog.MODELS.flatMap { listOf(partialFile(it).name, quarantineFile(it).name) }.toSet()
        val orphans = stagingDir.listFiles()?.filter { !it.isFile || it.name !in kept } ?: return emptyList()
        orphans.forEach { it.deleteRecursively() }
        if (orphans.isNotEmpty()) logger.i { "Pruned ${orphans.size} orphaned staging entries" }
        return orphans.map { it.name }
    }

    private suspend fun downloadOnto(
        partial: File,
        progress: Progress,
        digest: MessageDigest,
        model: PinnedModel,
    ): Outcome {
        val url = ModelCatalog.urlFor(model)
        val offset = progress.offset
        logger.i { "Downloading model: $url" + if (offset > 0) " (resuming at $offset)" else "" }
        return httpClient.prepareGet(url) {
            if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
        }.execute { response ->
            val resumed = when {
                offset > 0 && response.status == HttpStatusCode.PartialContent -> {
                    // Only an exact continuation is appendable; any
                    // other start would silently corrupt the file.
                    val start = contentRangeStart(response.headers[HttpHeaders.ContentRange])
                    if (start != offset) return@execute Outcome.RestartClean
                    true
                }
                // A server (or the CDN behind the redirect) that
                // ignores Range answers 200 with the full body; the
                // partial is useless against it.
                offset > 0 && response.status == HttpStatusCode.OK -> return@execute Outcome.RestartClean
                response.status == HttpStatusCode.OK -> false
                else -> throw HttpStatusException(response.status.value, url)
            }

            response.readBody { channel ->
                var lastLoggedPct = -1
                val output = try {
                    openPartial(partial, resumed)
                } catch (e: IOException) {
                    throw ModelStorageException("Cannot write the partial for ${model.id}", e)
                }
                val copied = runCatching {
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        // An infinite timeout must bypass withTimeoutOrNull
                        // entirely, not just never expire: INFINITE still
                        // schedules a Long.MAX_VALUE timer that a virtual
                        // clock fast-forwards to whenever the test
                        // dispatcher idles mid-transfer.
                        val read = if (readStallTimeout.isInfinite()) {
                            channel.readAvailable(buffer, 0, buffer.size)
                        } else {
                            withTimeoutOrNull(readStallTimeout) {
                                channel.readAvailable(buffer, 0, buffer.size)
                            } ?: throw StallException(url)
                        }
                        if (read == -1) break
                        if (read == 0) continue
                        if (progress.offset + read > model.sizeBytes) {
                            // Complete-but-wrong, not stopped-early: no
                            // resume can fix an oversize stream.
                            partial.delete()
                            throw SecurityException(
                                "Download for ${model.id} exceeded the pinned ${model.sizeBytes} bytes; aborting",
                            )
                        }
                        try {
                            output.write(buffer, 0, read)
                        } catch (e: IOException) {
                            throw ModelStorageException("Cannot write the partial for ${model.id}", e)
                        }
                        digest.update(buffer, 0, read)
                        progress.offset += read
                        val pct = (progress.offset * 100 / model.sizeBytes).toInt()
                        if (pct / 10 > lastLoggedPct / 10) {
                            lastLoggedPct = pct
                            logger.d { "Download progress: $pct% (${progress.offset} / ${model.sizeBytes})" }
                        }
                    }
                }
                // A close that fails is a disk failure like a failed write,
                // unless the copy had already failed for its own reason.
                val closeFailure = runCatching { output.close() }.exceptionOrNull()
                copied.getOrThrow()
                if (closeFailure != null) throw ModelStorageException("Cannot write the partial for ${model.id}", closeFailure)
                if (progress.offset < model.sizeBytes) {
                    throw ShortBodyException(
                        "Download for ${model.id} ended at ${progress.offset} of ${model.sizeBytes} bytes",
                    )
                }
                Outcome.Done
            }
        }
    }

    /**
     * Feeds the kept partial's bytes through the digest so appended bytes
     * continue the same hash stream. Disk corruption of these bytes is
     * caught by [verifyOnLoad], which hashes what is actually on disk
     * before the engine sees the file.
     */
    private suspend fun seedDigest(digest: MessageDigest, partial: File) {
        partial.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
    }

    /** The download-time gate: the partial on disk must be the pinned size and the streamed bytes the pinned digest. */
    private fun verifyAndPromote(partial: File, digest: MessageDigest, model: PinnedModel) {
        val sha256 = digest.toHexString()
        val onDisk = partial.lengthOrZero()
        if (onDisk != model.sizeBytes || sha256 != model.sha256) {
            partial.delete()
            throw SecurityException(
                "Model file for ${model.id} failed verification: got $onDisk bytes / sha256 $sha256, " +
                    "pinned ${model.sizeBytes} bytes / sha256 ${model.sha256}",
            )
        }
        promote(partial, model)
    }

    private fun promote(partial: File, model: PinnedModel) {
        val target = installedFile(model)
        try {
            target.parentFile?.mkdirs()
            // Same-filesystem atomic rename: no reader can ever observe a
            // half-written model file, and REPLACE_EXISTING covers a stale
            // file left by a failed quarantine rename.
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            throw ModelStorageException("Cannot move ${model.id} into place", e)
        }
        logger.i { "Installed verified model '${model.id}' at ${target.absolutePath}" }
    }

    private fun File.lengthOrZero(): Long = if (isFile) length() else 0L

    private suspend fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.toHexString()
    }

    private fun contentRangeStart(header: String?): Long? {
        // Shape: "bytes <start>-<end>/<total>"; anything unparsable is a
        // refusal to resume, not a guess.
        val match = Regex("""bytes (\d+)-""").find(header ?: return null) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun MessageDigest.toHexString(): String =
        digest().joinToString("") { "%02x".format(it) }
}
