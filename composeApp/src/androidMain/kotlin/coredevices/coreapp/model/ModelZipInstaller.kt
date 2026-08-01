package coredevices.coreapp.model

import co.touchlab.kermit.Logger
import coredevices.util.models.promoteSingleRootDir
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.seconds

/**
 * Verified download/extract core behind [CactusModelProvider]: obtains a
 * model zip (bundled asset or Hugging Face download), verifies it against
 * its [ModelPin], and installs it under [modelsDir]/<model>.
 *
 * The archive feeds the native libcactus_engine.so parser, so nothing
 * unverified may reach extraction. The layers, each defensible alone:
 *  - the download URL resolves an immutable commit ([ModelPin.commitSha]),
 *    so a retargeted release tag cannot swap the archive;
 *  - the received bytes are hashed while streaming and checked against the
 *    pinned SHA-256 and exact size before extraction, with a mid-stream
 *    abort as soon as the pinned size is exceeded; bundled assets are
 *    hashed through the same gate, so an APK repack cannot sneak weights
 *    past it either;
 *  - extraction is bounded (entry count, total uncompressed bytes) and
 *    confined (separator-boundary Zip-Slip check), limiting even an
 *    archive that matched a wrongly updated pin;
 *  - everything lands in a staging directory that only replaces the live
 *    model after full verification, so no failure mode destroys a working
 *    install.
 *
 * The class is deliberately Context-free: everything that needs an Android
 * Context (asset access, directory roots) stays in the provider and is
 * handed in from outside, which keeps the whole pipeline under JVM unit
 * tests with a mock HTTP engine and temp directories.
 *
 * Provenance: the download loop, buffer size, zip naming convention,
 * extraction loop, and temp-file handling were extracted from the fork's
 * [CactusModelProvider] copy and so ultimately derive from the unplugged
 * upstream class (experimental/src/commonMain/kotlin/coredevices/ring/
 * model/CactusModelProvider.kt). The provider's re-diff-after-upstream-merge
 * instruction names this file as a porting target; upstream fixes to their
 * download/extract logic belong here.
 *
 * Instead of a whole-request read timeout, each individual read gets
 * [READ_STALL_TIMEOUT]: a healthy multi-hundred-MB transfer legitimately
 * takes minutes, while a stalled socket should fail promptly. Coroutine
 * cancellation aborts the in-flight request through Ktor itself.
 *
 * Callers hold the provider's per-model mutex and run [install] on an
 * IO-capable dispatcher; this class stays dispatcher-agnostic so tests can
 * drive it on a virtual-time dispatcher.
 */
class ModelZipInstaller(
    private val httpClient: HttpClient,
    private val cacheDir: File,
    private val modelsDir: File,
) {
    companion object {
        private val logger = Logger.withTag("ModelZipInstaller")
        private const val HF_BASE = "https://huggingface.co/Cactus-Compute"
        private const val QUANTIZATION = "cq4"
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        private val READ_STALL_TIMEOUT = 30.seconds

        // Staging lives under modelsDir so the final swap is a same-filesystem
        // rename; the dot name keeps it out of getDownloadedModels, which only
        // looks at direct children carrying a config.txt.
        internal const val STAGING_DIR = ".staging"
        internal const val VERSION_MARKER = ".cactus_version"
        internal const val CONFIG_FILE = "config.txt"

        // Where the live model is parked during the swap; under STAGING_DIR
        // so it shares the dot-dir invisibility and the same filesystem.
        internal const val OLD_ASIDE_SUFFIX = ".old"

        // Bounds for a hostile archive that somehow passed the digest gate
        // (a wrongly updated pin): real model zips hold a handful of files
        // and the cq4 weights barely compress, so both caps sit far above
        // anything legitimate while still stopping zip bombs.
        internal const val MAX_ENTRIES = 10_000
        internal const val UNCOMPRESSED_CAP_FACTOR = 4L

        /** Zip naming convention shared by the HF repos and the bundled assets. */
        fun zipNameFor(modelName: String): String = "${modelName.lowercase()}-$QUANTIZATION.zip"
    }

    /**
     * Obtains the zip for [modelName], verifies it against [pin], and swaps
     * it into modelsDir/<model>. [copyBundledZip] is non-null when the zip
     * ships inside the APK; it writes the asset to the given file and skips
     * the network entirely. The existing model directory survives every
     * failure mode: it is parked aside (never deleted) until the staged
     * replacement that passed all checks has been renamed into place, and a
     * failed swap renames it straight back. The temp zip and the staging
     * directory are removed on every exit path.
     */
    suspend fun install(
        modelName: String,
        pin: ModelPin,
        copyBundledZip: (suspend (dest: File) -> Unit)?,
    ) {
        val targetDir = modelsDir.resolve(modelName)
        val stagingDir = modelsDir.resolve(STAGING_DIR).resolve(modelName)
        val oldAsideDir = modelsDir.resolve(STAGING_DIR).resolve("$modelName$OLD_ASIDE_SUFFIX")
        val tempZip = cacheDir.resolve(
            if (copyBundledZip != null) "cactus_asset_$modelName.zip" else "cactus_download_$modelName.zip",
        )
        try {
            // A process death between the two swap renames below leaves the
            // working install parked aside and no live model dir; restore it
            // first so even that crash never costs the install.
            if (!targetDir.exists() && oldAsideDir.exists()) {
                oldAsideDir.renameTo(targetDir)
            }
            // A crashed or cancelled earlier install may have left staging
            // or a swapped-out old model behind; neither is trusted as a
            // whole, so start clean. The parked copy is only swept while a
            // live install exists: if the restore above failed it is the
            // sole copy, and deleting it would turn a rename hiccup into a
            // lost install.
            stagingDir.deleteRecursively()
            if (targetDir.exists()) {
                oldAsideDir.deleteRecursively()
            }

            val (obtainedBytes, obtainedSha256) = if (copyBundledZip != null) {
                copyBundledZip(tempZip)
                hashFile(tempZip)
            } else {
                download(modelName, pin, tempZip)
            }
            if (obtainedBytes != pin.zipSizeBytes || obtainedSha256 != pin.zipSha256Hex) {
                throw SecurityException(
                    "Model archive for $modelName failed verification: " +
                        "got $obtainedBytes bytes / sha256 $obtainedSha256, " +
                        "pinned ${pin.zipSizeBytes} bytes / sha256 ${pin.zipSha256Hex}",
                )
            }

            extractToStaging(tempZip, stagingDir, pin)
            promoteSingleRootDir(Path(stagingDir.absolutePath))
            // Structural sanity on top of the digest: a verified archive
            // that still does not look like a model means the pin itself is
            // wrong, and must not replace a working install.
            if (!stagingDir.resolve(CONFIG_FILE).exists()) {
                throw SecurityException(
                    "Verified archive for $modelName contains no $CONFIG_FILE; refusing to install it",
                )
            }
            // Written into staging so the swap below is all-or-nothing: a
            // model directory either carries the marker of a fully verified
            // install or gets reinstalled.
            stagingDir.resolve(VERSION_MARKER).writeText(pin.zipSha256Hex)

            // Swap by parking the live install aside instead of deleting it:
            // an I/O failure at any single step leaves either the old or the
            // new model in place (a delete-then-rename would lose both to a
            // partial delete), and the recovery at the top of install covers
            // a process death between the renames.
            if (targetDir.exists() && !targetDir.renameTo(oldAsideDir)) {
                throw Exception("Could not move the old model for $modelName aside; keeping it")
            }
            if (!stagingDir.renameTo(targetDir)) {
                oldAsideDir.renameTo(targetDir)
                throw Exception("Could not move the verified model for $modelName into place")
            }
            if (!oldAsideDir.deleteRecursively()) {
                logger.w { "Old model for $modelName not fully removed; leftovers are swept on the next install" }
            }
            logger.i { "Installed verified model '$modelName' to ${targetDir.absolutePath}" }
        } catch (e: CancellationException) {
            logger.i { "Model install cancelled for $modelName" }
            throw e
        } catch (e: Exception) {
            // Transport failures can surface while the coroutine is being
            // cancelled; re-check liveness so cancellation propagates as
            // CancellationException rather than an install error.
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Model install failed for $modelName" }
            throw e
        } finally {
            // Cleanup lives here, not in the catch arms, so it also runs
            // when ensureActive above rethrows as CancellationException;
            // after a successful swap the staging dir is already gone and
            // this is a no-op.
            stagingDir.deleteRecursively()
            tempZip.delete()
        }
    }

    /**
     * Streams the pinned commit URL to [tempZip] while hashing, aborting as
     * soon as more than the pinned byte count arrives. Returns received
     * byte count and lowercase hex SHA-256 for the caller's gate.
     */
    private suspend fun download(modelName: String, pin: ModelPin, tempZip: File): Pair<Long, String> {
        val url = "$HF_BASE/${pin.hfRepo}/resolve/${pin.commitSha}/${zipNameFor(modelName)}"
        logger.i { "Downloading model: $url" }
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            httpClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    throw Exception("Download failed: HTTP ${response.status.value} for $url")
                }
                val channel = response.bodyAsChannel()
                var lastLoggedPct = -1
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val read = withTimeoutOrNull(READ_STALL_TIMEOUT) {
                            channel.readAvailable(buffer, 0, buffer.size)
                        } ?: throw Exception("Download stalled for $url")
                        if (read == -1) break
                        if (read == 0) continue
                        received += read
                        if (received > pin.zipSizeBytes) {
                            throw SecurityException(
                                "Download for $modelName exceeded the pinned ${pin.zipSizeBytes} bytes; aborting",
                            )
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        val pct = (received * 100 / pin.zipSizeBytes).toInt()
                        if (pct / 10 > lastLoggedPct / 10) {
                            lastLoggedPct = pct
                            logger.d { "Download progress: $pct% ($received / ${pin.zipSizeBytes})" }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            logger.i { "Model download cancelled for $modelName" }
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Model download failed for $modelName" }
            throw e
        }
        logger.i { "Download complete: $received bytes" }
        return received to digest.toHexString()
    }

    /** Size and SHA-256 of an already-local zip; the bundled-asset gate. */
    private fun hashFile(file: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                size += read
                digest.update(buffer, 0, read)
            }
        }
        return size to digest.toHexString()
    }

    private suspend fun extractToStaging(tempZip: File, stagingDir: File, pin: ModelPin) {
        stagingDir.mkdirs()
        // Separator-boundary confinement: comparing against the bare prefix
        // would accept an escape to a sibling whose name merely starts with
        // the staging dir's (entry "../<model>-evil/...").
        val stagingRoot = stagingDir.canonicalPath
        val stagingPrefix = stagingRoot + File.separator
        val uncompressedCap = pin.zipSizeBytes * UNCOMPRESSED_CAP_FACTOR
        var entryCount = 0
        var totalUncompressed = 0L
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                entryCount++
                if (entryCount > MAX_ENTRIES) {
                    throw SecurityException("Model archive has more than $MAX_ENTRIES entries; refusing to extract")
                }
                val outputFile = File(stagingDir, entry.name)
                val canonical = outputFile.canonicalPath
                if (canonical != stagingRoot && !canonical.startsWith(stagingPrefix)) {
                    throw SecurityException("ZIP entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { fos ->
                        // Count what actually inflates rather than trusting
                        // the entry's declared size.
                        while (true) {
                            val read = zis.read(buffer)
                            if (read == -1) break
                            totalUncompressed += read
                            if (totalUncompressed > uncompressedCap) {
                                throw SecurityException(
                                    "Model archive inflates past $uncompressedCap bytes; refusing to extract",
                                )
                            }
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        logger.i { "Extraction complete to ${stagingDir.absolutePath}" }
    }

    private fun MessageDigest.toHexString(): String =
        digest().joinToString("") { "%02x".format(it) }
}
