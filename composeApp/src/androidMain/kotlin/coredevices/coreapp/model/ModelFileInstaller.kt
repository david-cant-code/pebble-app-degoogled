package coredevices.coreapp.model

import co.touchlab.kermit.Logger
import coredevices.util.models.WhisperModel
import coredevices.util.models.WhisperModelCatalog
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Verified download core for the whisper model catalog: streams one ggml
 * file from its commit-pinned URL, verifies it against the catalog pin,
 * and installs it at [modelsDir]/<id>/<fileName>.
 *
 * The file feeds the native whisper parser, so nothing unverified may
 * become loadable. The layers, each defensible alone:
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
 * keeps it, and the next install seeds its hash state from the kept bytes
 * and continues with an HTTP Range request. A resumed response is
 * accepted only as exactly-206 with a Content-Range matching the resume
 * offset; anything else restarts from zero. The final digest gate runs on
 * the complete file either way, so a lying resume can waste a transfer
 * but never install wrong bytes.
 *
 * Context-free on purpose (only [File] roots and an [HttpClient]), which
 * keeps the whole pipeline under JVM unit tests with a mock engine and
 * temp directories.
 *
 * Instead of a whole-request read timeout, each individual read gets
 * [READ_STALL_TIMEOUT]: a healthy multi-hundred-MB transfer legitimately
 * takes minutes, while a stalled socket should fail promptly. Coroutine
 * cancellation aborts the in-flight request through Ktor itself.
 */
class ModelFileInstaller(
    private val httpClient: HttpClient,
    private val modelsDir: File,
    // Injected so tests can disable it: under runTest's virtual clock the
    // MockEngine body writer races this timeout (Ktor delivers the body on
    // a real dispatcher), and multi-chunk transfers lose spuriously.
    // Production always uses the default.
    private val readStallTimeout: Duration = READ_STALL_TIMEOUT,
) {
    companion object {
        private val logger = Logger.withTag("ModelFileInstaller")
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        private val READ_STALL_TIMEOUT = 30.seconds

        // Staging lives under modelsDir so promotion is a same-filesystem
        // atomic rename; the dot name keeps it out of directory listings
        // that treat modelsDir children as installed models.
        internal const val STAGING_DIR = ".staging"
        internal const val PARTIAL_SUFFIX = ".partial"
        internal const val QUARANTINE_SUFFIX = ".quarantined"
    }

    private val stagingDir: File get() = modelsDir.resolve(STAGING_DIR)

    fun installedFile(model: WhisperModel): File =
        modelsDir.resolve(model.id).resolve(model.fileName)

    /**
     * Cheap installed check: exact pinned size at the expected path. The
     * deep content check is [verifyOnLoad]; this one exists for UI state
     * and sweep decisions where hashing hundreds of MB is not acceptable.
     */
    fun isInstalled(model: WhisperModel): Boolean =
        installedFile(model).let { it.isFile && it.length() == model.sizeBytes }

    private fun partialFile(model: WhisperModel): File =
        stagingDir.resolve("${model.id}$PARTIAL_SUFFIX")

    private fun quarantineFile(model: WhisperModel): File =
        stagingDir.resolve("${model.id}$QUARANTINE_SUFFIX")

    /** How downloadOnto's response handling ended; drives the attempt loop. */
    private enum class Outcome { Done, RestartClean }

    /**
     * Ensures the pinned file for [model] is installed, downloading (or
     * resuming) as needed. Throws [SecurityException] when received bytes
     * fail the pin, and a plain [Exception] for transport failures that
     * leave a resumable partial behind. Callers serialize per model id.
     */
    suspend fun install(model: WhisperModel) {
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

        // Two attempts at most: a resume that the server answers unusably
        // (plain 200, wrong Content-Range) restarts once from zero. A
        // second unusable answer is a server misbehaving on a plain GET,
        // which is a real error, not a retry case.
        repeat(2) { attempt ->
            val offset = if (partial.isFile) partial.length() else 0L
            val digest = MessageDigest.getInstance("SHA-256")
            if (offset > 0) seedDigest(digest, partial)

            when (downloadOnto(partial, offset, digest, model)) {
                Outcome.Done -> {
                    val received = partial.length()
                    if (received < model.sizeBytes) {
                        // Stopped early: the bytes so far may be good, so
                        // the partial stays for the next resume.
                        throw Exception(
                            "Download for ${model.id} ended at $received of ${model.sizeBytes} bytes; partial kept for resume",
                        )
                    }
                    val sha256 = digest.toHexString()
                    if (received != model.sizeBytes || sha256 != model.sha256) {
                        partial.delete()
                        throw SecurityException(
                            "Model file for ${model.id} failed verification: " +
                                "got $received bytes / sha256 $sha256, " +
                                "pinned ${model.sizeBytes} bytes / sha256 ${model.sha256}",
                        )
                    }
                    promote(partial, model)
                    return
                }
                Outcome.RestartClean -> {
                    logger.w { "Resume for ${model.id} not honored by the server; restarting from zero" }
                    partial.delete()
                }
            }
        }
        throw Exception("Download for ${model.id} failed: the server would not serve a usable response")
    }

    /**
     * Re-hashes the installed file against its pin. On mismatch the file
     * is quarantined (moved aside under staging, deleted if even that
     * fails) and treated as not installed, so the engine never parses it;
     * the evidence is kept for one generation per model id.
     */
    suspend fun verifyOnLoad(model: WhisperModel): Boolean {
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

    private suspend fun downloadOnto(
        partial: File,
        offset: Long,
        digest: MessageDigest,
        model: WhisperModel,
    ): Outcome {
        val url = WhisperModelCatalog.urlFor(model)
        logger.i { "Downloading model: $url" + if (offset > 0) " (resuming at $offset)" else "" }
        try {
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
                    else -> throw Exception("Download failed: HTTP ${response.status.value} for $url")
                }

                val channel = response.bodyAsChannel()
                var received = offset
                var lastLoggedPct = -1
                FileOutputStream(partial, resumed).use { output ->
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
                            } ?: throw Exception("Download stalled for $url")
                        }
                        if (read == -1) break
                        if (read == 0) continue
                        received += read
                        if (received > model.sizeBytes) {
                            // Complete-but-wrong, not stopped-early: no
                            // resume can fix an oversize stream.
                            partial.delete()
                            throw SecurityException(
                                "Download for ${model.id} exceeded the pinned ${model.sizeBytes} bytes; aborting",
                            )
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        val pct = (received * 100 / model.sizeBytes).toInt()
                        if (pct / 10 > lastLoggedPct / 10) {
                            lastLoggedPct = pct
                            logger.d { "Download progress: $pct% ($received / ${model.sizeBytes})" }
                        }
                    }
                }
                Outcome.Done
            }
        } catch (e: CancellationException) {
            // The partial survives cancellation by design: the next
            // install resumes from it.
            logger.i { "Model download cancelled for ${model.id}" }
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Model download failed for ${model.id}" }
            throw e
        }
    }

    /**
     * Feeds the kept partial's bytes through the digest so appended bytes
     * continue the same hash stream. Disk corruption of these bytes is
     * caught by the final whole-file gate, which hashes what was actually
     * on disk, not what the network delivered.
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

    private fun promote(partial: File, model: WhisperModel) {
        val target = installedFile(model)
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
        logger.i { "Installed verified model '${model.id}' at ${target.absolutePath}" }
    }

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
