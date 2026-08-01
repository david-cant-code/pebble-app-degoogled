package coredevices.coreapp.model

import android.content.Context
import co.touchlab.kermit.Logger
import coredevices.util.CommonBuildKonfig
import coredevices.util.transcription.CactusModelPathProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork-owned Cactus model provider: resolves, downloads (Hugging Face), and
 * version-manages the on-device STT/LM model weights.
 *
 * Upstream ships this class only inside the unplugged :experimental module
 * (coredevices.ring.model.CactusModelProvider), even though on-device Cactus
 * transcription is a core watch feature, and the only alternative STT paths
 * are cloud services or the GMS-backed platform recognizer, both dead on a
 * de-Googled ROM. This copy keeps dictation working. Fork deviations from
 * the upstream class: Context and the app's shared Ktor HttpClient are
 * constructor-injected (upstream service-locates the Context and builds a
 * one-off OkHttpClient per download), the download/extract pipeline lives
 * in the testable [ModelZipInstaller] core, the ring-only setCloudApiKey is
 * dropped, and initTelemetry is a no-op (the native cactus lib's telemetry
 * environment is never configured; a strings sweep of libcactus_engine.so
 * found no endpoints, so this is belt on top of suspenders).
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
class CactusModelProvider(
    private val context: Context,
    private val httpClient: HttpClient,
) : CactusModelPathProvider {
    companion object {
        private val logger = Logger.withTag("CactusModelProvider")

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

    // Lazy so resolving the provider from the DI graph stays free of
    // filesystem access; the dirs are only touched once an install runs.
    private val installer by lazy { ModelZipInstaller(httpClient, context.cacheDir, modelsDir) }

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
        context.assets.list("models")?.contains(ModelZipInstaller.zipNameFor(modelName)) == true

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
            downloadAndExtract(modelName, version)
            versionFile.writeText(version)
        }

        logger.d { "Model '$modelName' at: ${modelDir.absolutePath}" }
        return modelDir.absolutePath
    }

    private suspend fun downloadAndExtract(modelName: String, version: String) = withContext(Dispatchers.IO) {
        val zipName = ModelZipInstaller.zipNameFor(modelName)
        val bundled = context.assets.list("models")?.contains(zipName) == true
        installer.install(
            modelName = modelName,
            version = version,
            copyBundledZip = if (bundled) {
                { dest: File ->
                    logger.i { "Found included model zip in assets: $zipName, extracting..." }
                    context.assets.open("models/$zipName").use { input ->
                        FileOutputStream(dest).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } else {
                null
            },
        )
    }
}
