package coredevices.coreapp.model

import co.touchlab.kermit.Logger
import coredevices.util.models.promoteSingleRootDir
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.seconds

/**
 * Download/extract core behind [CactusModelProvider]: obtains a model zip
 * (bundled asset or Hugging Face download) and installs it under
 * [modelsDir]/<model>.
 *
 * Extracted from the provider so the whole obtain-and-install pipeline runs
 * under JVM unit tests with a mock HTTP engine and temp directories. The
 * class is deliberately Context-free: everything that needs an Android
 * Context (asset access, directory roots) stays in the provider and is
 * handed in from outside.
 *
 * Transport is the app's shared Ktor [HttpClient] (the same one the
 * verified firmware installer uses). Instead of a whole-request read
 * timeout, each individual read gets [READ_STALL_TIMEOUT]: a healthy
 * multi-hundred-MB transfer legitimately takes minutes, while a stalled
 * socket should fail promptly. Coroutine cancellation aborts the in-flight
 * request through Ktor itself.
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

        /** Zip naming convention shared by the HF repos and the bundled assets. */
        fun zipNameFor(modelName: String): String = "${modelName.lowercase()}-$QUANTIZATION.zip"
    }

    /**
     * Obtains the zip for [modelName] and installs it to modelsDir/<model>,
     * replacing whatever was there. [copyBundledZip] is non-null when the
     * zip ships inside the APK; it writes the asset to the given file and
     * skips the network entirely. The temp zip lives in [cacheDir] and is
     * removed on every exit path, success or failure.
     */
    suspend fun install(
        modelName: String,
        version: String,
        copyBundledZip: (suspend (dest: File) -> Unit)?,
    ) {
        val targetDir = modelsDir.resolve(modelName)
        val tempZip = cacheDir.resolve(
            if (copyBundledZip != null) "cactus_asset_$modelName.zip" else "cactus_download_$modelName.zip",
        )
        try {
            if (copyBundledZip != null) {
                copyBundledZip(tempZip)
            } else {
                download(modelName, version, tempZip)
            }
            extract(modelName, tempZip, targetDir)
        } finally {
            tempZip.delete()
        }
    }

    private suspend fun download(modelName: String, version: String, tempZip: File) {
        val url = "$HF_BASE/$modelName/resolve/$version/${zipNameFor(modelName)}"
        logger.i { "Downloading model: $url" }
        try {
            httpClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    throw Exception("Download failed: HTTP ${response.status.value} for $url")
                }
                val totalBytes = response.contentLength() ?: -1L
                val channel = response.bodyAsChannel()
                var downloadedBytes = 0L
                var lastLoggedPct = -1
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val read = withTimeoutOrNull(READ_STALL_TIMEOUT) {
                            channel.readAvailable(buffer, 0, buffer.size)
                        } ?: throw Exception("Download stalled for $url")
                        if (read == -1) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val pct = (downloadedBytes * 100 / totalBytes).toInt()
                            if (pct / 10 > lastLoggedPct / 10) {
                                lastLoggedPct = pct
                                logger.d { "Download progress: $pct% ($downloadedBytes / $totalBytes)" }
                            }
                        }
                    }
                }
            }
            logger.i { "Download complete: ${tempZip.length()} bytes" }
        } catch (e: CancellationException) {
            logger.i { "Model download cancelled for $modelName" }
            throw e
        } catch (e: Exception) {
            // Transport failures can surface while the coroutine is being
            // cancelled; re-check liveness so cancellation propagates as
            // CancellationException rather than a download error.
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Model download failed for $modelName" }
            throw e
        }
    }

    private suspend fun extract(modelName: String, tempZip: File, targetDir: File) {
        try {
            // Clear old model if present
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    val outputFile = File(targetDir, entry.name)
                    // ZIP Slip protection
                    if (!outputFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        throw SecurityException("ZIP entry outside target dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            promoteSingleRootDir(Path(targetDir.absolutePath))
            logger.i { "Extraction complete to ${targetDir.absolutePath}" }
        } catch (e: CancellationException) {
            logger.i { "Model install cancelled for $modelName" }
            targetDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Model extract failed for $modelName" }
            targetDir.deleteRecursively()
            throw e
        }
    }
}
