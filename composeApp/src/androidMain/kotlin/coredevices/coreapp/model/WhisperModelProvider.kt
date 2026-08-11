package coredevices.coreapp.model

import android.app.ActivityManager
import android.content.Context
import co.touchlab.kermit.Logger
import coredevices.util.models.WhisperModel
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.transcription.CactusModelPathProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork-owned model provider for the whisper engine: resolves, downloads,
 * and verifies the on-device speech models from [WhisperModelCatalog].
 * Successor to the Cactus-era provider behind the same
 * [CactusModelPathProvider] interface (the interface keeps its upstream
 * name to bound merge cost; see the interface's own KDoc).
 *
 * Verification is layered: the installer's download-time digest gate
 * (fail closed, see [ModelFileInstaller]) plus a load-time re-hash here,
 * once per process per model, before a path is ever handed to the native
 * parser. A load-time mismatch quarantines the file and triggers one
 * fresh verified reinstall; a second failure surfaces as an error rather
 * than a silent retry loop.
 *
 * Models are stored at <filesDir>/models/<id>/<fileName>. Anything under
 * <filesDir>/models that is not a catalog entry in installed shape is
 * reported by [getIncompatibleModels]; that is what makes the Cactus-era
 * model directories sweepable by the app delegate's migration pass.
 *
 * There is no language model in this fork: the engine consumes STT models
 * only, so [getLMModelPath] fails loudly instead of pretending.
 */
class WhisperModelProvider(
    private val context: Context,
    private val httpClient: HttpClient,
    // Handed in by the DI wiring so the provider can resolve "the
    // configured model" without owning config plumbing; null means nothing
    // configured yet, which falls back to the device-appropriate
    // recommendation.
    private val configuredModelId: () -> String? = { null },
) : CactusModelPathProvider {
    companion object {
        private val logger = Logger.withTag("WhisperModelProvider")

        // One mutex per model so an in-progress download of one model does
        // not head-of-line block resolving another.
        private val modelMutexes = ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(modelId: String): Mutex =
            modelMutexes.getOrPut(modelId) { Mutex() }

        // Static like the Cactus-era decision helpers so the directory
        // shape logic stays under JVM tests with temp dirs; a regression
        // here either forces pointless multi-hundred-MB re-downloads or
        // lets the migration sweep miss stale engine models.
        internal fun isInstalledShapeIn(modelsDir: File, model: WhisperModel): Boolean =
            modelsDir.resolve(model.id).resolve(model.fileName)
                .let { it.isFile && it.length() == model.sizeBytes }

        /**
         * Model directories that cannot serve the current engine: names
         * outside the catalog (every Cactus-era install lands here) and
         * catalog directories whose file is missing or wrong-sized (a
         * quarantine leftover or torn install). Staging is the
         * installer's workspace, never a model.
         */
        internal fun incompatibleIn(modelsDir: File): List<String> =
            modelsDir.listFiles()
                ?.filter { it.isDirectory && it.name != ModelFileInstaller.STAGING_DIR }
                ?.map { it.name }
                ?.filter { name ->
                    val model = WhisperModelCatalog.byId(name)
                    model == null || !isInstalledShapeIn(modelsDir, model)
                }
                ?: emptyList()

        /**
         * Every legitimate model name is a single path segment: a catalog
         * id or a directory name straight out of a modelsDir listing.
         * File.resolve honours separators, ".." and absolute paths, so an
         * arbitrary name reaching the delete/size sinks below could
         * operate outside modelsDir (deleteModel resolves into a recursive
         * delete). No current caller passes such a name; this gate keeps
         * that true for future ones, matching the fail-closed stance
         * getModelPath already takes for unknown ids.
         */
        internal fun isSafeModelDirName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." &&
                !name.contains('/') && !name.contains('\\')

        /**
         * The load-time half of the layered model verification, static so
         * the orchestration stays under JVM tests: install when missing,
         * re-hash once per process before first use ([loadVerified] is the
         * per-process memo), quarantine plus one fresh reinstall on a
         * mismatch, and a hard failure instead of a retry loop when the
         * reinstall does not verify either. A fresh install always drops
         * the memo entry so the new bytes are re-hashed before use.
         */
        internal suspend fun resolveVerifiedModelPath(
            installer: ModelFileInstaller,
            loadVerified: MutableMap<String, Boolean>,
            model: WhisperModel,
        ): String {
            if (!installer.isInstalled(model)) {
                installer.install(model)
                loadVerified.remove(model.id)
            }
            if (loadVerified[model.id] != true) {
                if (!installer.verifyOnLoad(model)) {
                    // Quarantined: one fresh verified reinstall, then the
                    // same gate again; two failures mean something is
                    // persistently wrong and the engine must not load it.
                    installer.install(model)
                    if (!installer.verifyOnLoad(model)) {
                        throw SecurityException(
                            "Model '${model.id}' failed load-time verification after a fresh reinstall",
                        )
                    }
                }
                loadVerified[model.id] = true
            }
            return installer.installedFile(model).absolutePath
        }
    }

    private val modelsDir: File get() = context.filesDir.resolve("models").also { it.mkdirs() }

    // Lazy so resolving the provider from the DI graph stays free of
    // filesystem access; the dirs are only touched once an install runs.
    private val installer by lazy { ModelFileInstaller(httpClient, modelsDir) }

    // Load-time verification runs once per process per model: the full
    // re-hash of hundreds of MB is too costly per transcription, and
    // within a process the file only changes through this provider.
    private val loadVerified = ConcurrentHashMap<String, Boolean>()

    /**
     * Ensures [modelId] is installed and load-verified, returning the
     * absolute path of the model file. The single entry point every
     * download and resolve funnels through; fails closed for ids outside
     * the catalog, since there is no pin to verify such a download
     * against.
     */
    override suspend fun getModelPath(modelId: String): String = withContext(Dispatchers.IO) {
        val model = WhisperModelCatalog.byId(modelId)
            ?: throw IllegalStateException("No catalog entry for model '$modelId'; refusing to download")
        mutexFor(model.id).withLock {
            resolveVerifiedModelPath(installer, loadVerified, model)
        }
    }

    override suspend fun getSTTModelPath(): String =
        getModelPath(configuredModelId() ?: recommendedDefault().id)

    override suspend fun getLMModelPath(): String =
        throw UnsupportedOperationException(
            "This fork bundles no language model; only STT models exist",
        )

    override fun isModelDownloaded(modelName: String): Boolean {
        val model = WhisperModelCatalog.byId(modelName) ?: return false
        return isInstalledShapeIn(modelsDir, model)
    }

    // Raw directory view (minus staging): includes stale engine models on
    // purpose so they stay visible to the sweep and deletable in the UI.
    override fun getDownloadedModels(): List<String> =
        modelsDir.listFiles()
            ?.filter { it.isDirectory && it.name != ModelFileInstaller.STAGING_DIR }
            ?.map { it.name }
            ?: emptyList()

    override fun getIncompatibleModels(): List<String> = incompatibleIn(modelsDir)

    override fun deleteModel(modelName: String) {
        if (!isSafeModelDirName(modelName)) {
            logger.w { "Refusing to delete model with unsafe name '$modelName'" }
            return
        }
        modelsDir.resolve(modelName).deleteRecursively()
        loadVerified.remove(modelName)
    }

    override fun getModelSizeBytes(modelName: String): Long {
        if (!isSafeModelDirName(modelName)) return 0L
        val dir = modelsDir.resolve(modelName)
        return if (dir.exists()) dir.walkTopDown().sumOf { it.length() } else 0L
    }

    override fun initTelemetry() {
        // The whisper engine has no telemetry to configure; the method
        // stays until the interface itself is retired.
    }

    /**
     * Device-appropriate default when nothing is configured: tier by
     * total device RAM, English-only variant when the device language is
     * English. Total RAM (not free heap) is the right signal because the
     * model cost is native allocation over the device's lifetime of the
     * process, not JVM heap.
     */
    private fun recommendedDefault(): WhisperModel {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val english = context.resources.configuration.locales.get(0)?.language == "en"
        val model = WhisperModelCatalog.recommended(memoryInfo.totalMem, english)
        logger.d { "Recommended model for this device: ${model.id}" }
        return model
    }
}
