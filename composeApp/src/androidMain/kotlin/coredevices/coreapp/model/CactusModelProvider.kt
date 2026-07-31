package coredevices.coreapp.model

import android.content.Context
import co.touchlab.kermit.Logger
import coredevices.util.CommonBuildKonfig
import coredevices.util.models.promoteSingleRootDir
import coredevices.util.transcription.CactusModelPathProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Fork-owned Cactus model provider: resolves, downloads (Hugging Face), and
 * version-manages the on-device STT/LM model weights.
 *
 * Upstream ships this class only inside the unplugged :experimental module
 * (coredevices.ring.model.CactusModelProvider), even though on-device Cactus
 * transcription is a core watch feature, and the only alternative STT paths
 * are cloud services or the GMS-backed platform recognizer, both dead on a
 * de-Googled ROM. This copy keeps dictation working. Fork deviations from
 * the upstream class: Context is constructor-injected instead of
 * service-located, the ring-only setCloudApiKey is dropped, and
 * initTelemetry is a no-op (the native cactus lib's telemetry environment is
 * never configured; a strings sweep of libcactus_engine.so found no
 * endpoints, so this is belt on top of suspenders).
 *
 * Provenance: copied from the tree as of commit ecdfa123, flattening
 * experimental/src/commonMain/kotlin/coredevices/ring/model/CactusModelProvider.kt
 * and its androidMain actual into one class. Those originals stay in-tree (the unplugged
 * module keeps its sources for cheap merges) and keep receiving upstream
 * changes that will merge conflict-free WITHOUT touching this copy. After
 * every upstream merge, re-diff this file against them and port whatever
 * matters; drift here breaks dictation.
 *
 * Models are stored at: <filesDir>/models/<modelName>/ with config.txt,
 * vocab.txt, and .weights files, plus a .cactus_version marker.
 */
class CactusModelProvider(private val context: Context) : CactusModelPathProvider {
    companion object {
        private val logger = Logger.withTag("CactusModelProvider")
        private const val HF_BASE = "https://huggingface.co/Cactus-Compute"
        private const val QUANTIZATION = "cq4"
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024

        // One mutex per model so an in-progress STT download doesn't head-of-line
        // block an unrelated LM resolve (or vice versa).
        private val modelMutexes = ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(modelName: String): Mutex =
            modelMutexes.getOrPut(modelName) { Mutex() }

        internal fun modelNeedsReplacement(
            name: String,
            compatibleNames: Set<String>,
            versionMatches: Boolean,
            bundledInApp: Boolean,
        ): Boolean = name !in compatibleNames || (!versionMatches && !bundledInApp)
    }

    private val modelsDir: File get() = context.filesDir.resolve("models").also { it.mkdirs() }

    override suspend fun getSTTModelPath(): String = withContext(Dispatchers.IO) {
        val modelName = CommonBuildKonfig.CACTUS_STT_MODEL
        return@withContext resolveModelPath(modelName, CommonBuildKonfig.CACTUS_WEIGHTS_VERSION)
    }

    override suspend fun getLMModelPath(): String = withContext(Dispatchers.IO) {
        val modelName = CommonBuildKonfig.CACTUS_LM_MODEL_NAME
        return@withContext resolveModelPath(modelName, CommonBuildKonfig.CACTUS_WEIGHTS_VERSION)
    }

    override fun isModelDownloaded(modelName: String): Boolean {
        val modelDir = modelsDir.resolve(modelName)
        return modelDir.exists() && modelDir.resolve("config.txt").exists()
    }

    override fun getDownloadedModels(): List<String> {
        return modelsDir.listFiles()
            ?.filter { it.isDirectory && it.resolve("config.txt").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    override fun getIncompatibleModels(): List<String> {
        val compatible = setOf(CommonBuildKonfig.CACTUS_STT_MODEL, CommonBuildKonfig.CACTUS_LM_MODEL_NAME)
        return getDownloadedModels().filter { name ->
            modelNeedsReplacement(name, compatible, versionMatches(name), isBundled(name))
        }
    }

    private fun versionMatches(modelName: String): Boolean {
        val versionFile = modelsDir.resolve(modelName).resolve(".cactus_version")
        return versionFile.exists() &&
            versionFile.readText().trim() == CommonBuildKonfig.CACTUS_WEIGHTS_VERSION
    }

    private fun isBundled(modelName: String): Boolean =
        context.assets.list("models")?.contains("${modelName.lowercase()}-$QUANTIZATION.zip") == true

    override fun deleteModel(modelName: String) {
        modelsDir.resolve(modelName).deleteRecursively()
    }

    override fun getModelSizeBytes(modelName: String): Long {
        val dir = modelsDir.resolve(modelName)
        return if (dir.exists()) dir.walkTopDown().sumOf { it.length() } else 0L
    }

    override fun initTelemetry() {
        // Fork: never configure the native cactus telemetry environment.
    }

    private suspend fun resolveModelPath(modelName: String, version: String): String = mutexFor(modelName).withLock {
        val modelDir = modelsDir.resolve(modelName)
        val versionFile = modelDir.resolve(".cactus_version")

        val needsDownload = !modelDir.exists()
            || !modelDir.resolve("config.txt").exists()
            || !versionFile.exists()
            || versionFile.readText().trim() != version

        if (needsDownload) {
            downloadAndExtract(modelName, modelDir, version)
            versionFile.writeText(version)
        }

        logger.d { "Model '$modelName' at: ${modelDir.absolutePath}" }
        return modelDir.absolutePath
    }

    private suspend fun downloadAndExtract(modelName: String, targetDir: File, version: String) = withContext(Dispatchers.IO) {
        val zipName = "${modelName.lowercase()}-$QUANTIZATION.zip"

        val tempZip = if (context.assets.list("models")?.contains(zipName) == true) {
            logger.i { "Found included model zip in assets: $zipName, extracting..." }
            val tempZip = File(context.cacheDir, "cactus_asset_$modelName.zip")
            withContext(Dispatchers.IO) {
                context.assets.open("models/$zipName").use { input ->
                    FileOutputStream(tempZip).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            tempZip
        } else {
            val url = "$HF_BASE/$modelName/resolve/$version/$zipName"
            logger.i { "Downloading model: $url" }

            val tempZip = File(context.cacheDir, "cactus_download_$modelName.zip")
            // Cancel the in-flight HTTP call if the coroutine is cancelled so a blocked
            // socket read unblocks promptly instead of hanging until readTimeout.
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val call = client.newCall(Request.Builder().url(url).build())
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()?.take(500) ?: "no body"
                        throw Exception("Download failed: HTTP ${response.code} for $url: $errorBody")
                    }

                    val body = response.body
                        ?: throw Exception("Download failed: empty response body for $url")
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    var lastLoggedPct = -1

                    body.byteStream().use { input ->
                        FileOutputStream(tempZip).use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
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
                }
                logger.i { "Download complete: ${tempZip.length()} bytes" }
                tempZip
            } catch (e: CancellationException) {
                logger.i { "Model download cancelled for $modelName" }
                throw e
            } catch (e: Exception) {
                // A cancelled coroutine cancels the OkHttp call, surfacing as IOException;
                // re-check liveness so cancellation propagates as CancellationException.
                currentCoroutineContext().ensureActive()
                logger.e(e) { "Model download failed for $modelName" }
                throw e
            } finally {
                cancelHandle?.dispose()
            }
        }

        try {
            // Clear old model if present
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            // Extract
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
            logger.i { "Model download cancelled for $modelName" }
            targetDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            // A cancelled coroutine cancels the OkHttp call, surfacing as IOException;
            // re-check liveness so cancellation propagates as CancellationException.
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Model download/extract failed for $modelName" }
            targetDir.deleteRecursively()
            throw e
        } finally {
            tempZip.delete()
        }
    }
}
