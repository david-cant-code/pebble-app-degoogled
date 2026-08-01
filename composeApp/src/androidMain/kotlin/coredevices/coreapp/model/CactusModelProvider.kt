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
 * every upstream merge, re-diff this file AND [ModelZipInstaller] (which
 * carries the extracted download/extract pipeline) against them and port
 * whatever matters; drift here breaks dictation.
 *
 * Fork security deviation: installs are verified. Downloads resolve the
 * immutable commit pinned in [CactusModelPins] (upstream downloads a
 * mutable tag), the archive must match its pinned SHA-256 and exact size
 * before extraction (bundled assets included), and extraction is bounded
 * and staged so a failed or hostile install never destroys a working
 * model. The .cactus_version marker holds the pinned archive digest;
 * installs from before the pinning scheme hold the old release tag, which
 * [CactusModelPins.markerMatches] grandfathers while the pin still names
 * the same archive that tag shipped, so existing users are not forced
 * through a pointless re-download (or silently dropped to RemoteOnly STT
 * by CommonAppDelegate's incompatible-model sweep in the meantime).
 *
 * Models are stored at: <filesDir>/models/<modelName>/ with config.txt,
 * vocab.txt, and .weights files, plus the .cactus_version marker.
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

        // Static like modelNeedsReplacement so the file-reading decision
        // logic stays under JVM tests with temp dirs; a regression here
        // silently forces a 383 MB re-download for every existing user, or
        // downgrades them to RemoteOnly STT through the incompatible-model
        // sweep. The instance methods below just bind the real modelsDir.
        internal fun versionMatchesIn(modelsDir: File, modelName: String): Boolean {
            val versionFile = modelsDir.resolve(modelName).resolve(ModelZipInstaller.VERSION_MARKER)
            val marker = versionFile.takeIf { it.exists() }?.readText()?.trim() ?: return false
            return CactusModelPins.markerMatches(modelName, marker)
        }

        internal fun needsInstallIn(modelsDir: File, modelName: String): Boolean =
            !modelsDir.resolve(modelName).resolve(ModelZipInstaller.CONFIG_FILE).exists() ||
                !versionMatchesIn(modelsDir, modelName)

        // Fail closed on an unpinned model name: without integrity data
        // there is no verifiable download, and the native parser must never
        // see an unverified archive.
        internal fun requirePin(modelName: String): ModelPin =
            CactusModelPins.pinFor(modelName)
                ?: throw IllegalStateException("No integrity pin for model '$modelName'; refusing to download")
    }

    private val modelsDir: File get() = context.filesDir.resolve("models").also { it.mkdirs() }

    // Lazy so resolving the provider from the DI graph stays free of
    // filesystem access; the dirs are only touched once an install runs.
    private val installer by lazy { ModelZipInstaller(httpClient, context.cacheDir, modelsDir) }

    override suspend fun getSTTModelPath(): String = withContext(Dispatchers.IO) {
        return@withContext resolveModelPath(CommonBuildKonfig.CACTUS_STT_MODEL)
    }

    override suspend fun getLMModelPath(): String = withContext(Dispatchers.IO) {
        return@withContext resolveModelPath(CommonBuildKonfig.CACTUS_LM_MODEL_NAME)
    }

    override fun isModelDownloaded(modelName: String): Boolean {
        val modelDir = modelsDir.resolve(modelName)
        return modelDir.exists() && modelDir.resolve(ModelZipInstaller.CONFIG_FILE).exists()
    }

    override fun getDownloadedModels(): List<String> {
        return modelsDir.listFiles()
            ?.filter { it.isDirectory && it.resolve(ModelZipInstaller.CONFIG_FILE).exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    override fun getIncompatibleModels(): List<String> {
        val compatible = setOf(CommonBuildKonfig.CACTUS_STT_MODEL, CommonBuildKonfig.CACTUS_LM_MODEL_NAME)
        return getDownloadedModels().filter { name ->
            modelNeedsReplacement(name, compatible, versionMatches(name), isBundled(name))
        }
    }

    private fun versionMatches(modelName: String): Boolean = versionMatchesIn(modelsDir, modelName)

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

    private suspend fun resolveModelPath(modelName: String): String = mutexFor(modelName).withLock {
        val modelDir = modelsDir.resolve(modelName)

        if (needsInstallIn(modelsDir, modelName)) {
            downloadAndExtract(modelName, requirePin(modelName))
        }

        logger.d { "Model '$modelName' at: ${modelDir.absolutePath}" }
        return modelDir.absolutePath
    }

    private suspend fun downloadAndExtract(modelName: String, pin: ModelPin) = withContext(Dispatchers.IO) {
        val zipName = ModelZipInstaller.zipNameFor(modelName)
        installer.install(
            modelName = modelName,
            pin = pin,
            copyBundledZip = if (isBundled(modelName)) {
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
