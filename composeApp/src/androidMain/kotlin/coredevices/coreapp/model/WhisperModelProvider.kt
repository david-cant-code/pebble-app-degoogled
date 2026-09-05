package coredevices.coreapp.model

import android.content.Context
import co.touchlab.kermit.Logger
import coredevices.util.Platform
import coredevices.util.models.WhisperModel
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.models.prefersEnglishModels
import coredevices.util.models.totalRamBytes
import coredevices.util.transcription.CactusModelPathProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
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
    // Same RAM/locale source the ModelManager recommendation uses, so the
    // two recommendation paths cannot diverge near the tier boundary.
    private val platform: Platform,
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
            modelDirNamesIn(modelsDir)
                .filter { name ->
                    val model = WhisperModelCatalog.byId(name)
                    model == null || !isInstalledShapeIn(modelsDir, model)
                }

        /**
         * Directory names under modelsDir that can be models: everything
         * except the installer's staging workspace and the voice activity
         * detector's directory, which is an engine auxiliary rather than a
         * speech model and must never show up as a stale entry, a usable
         * model, or a sweep candidate (a torn detector install is handled
         * by its own verified resolve).
         */
        internal fun modelDirNamesIn(modelsDir: File): List<String> =
            modelsDir.listFiles()
                ?.filter {
                    it.isDirectory &&
                        it.name != ModelFileInstaller.STAGING_DIR &&
                        !WhisperModelCatalog.isVadModelId(it.name)
                }
                ?.map { it.name }
                ?: emptyList()

        /**
         * [resolveVerifiedModelPath] for an optional file: absent, or
         * quarantined after failing its load-time re-hash, reads as null
         * instead of an error, since the caller can run without it. Never
         * downloads.
         */
        internal suspend fun resolveOptionalModelPath(
            installer: ModelFileInstaller,
            loadVerified: MutableMap<String, Boolean>,
            model: WhisperModel,
        ): String? = try {
            resolveVerifiedModelPath(installer, loadVerified, model, allowReinstall = false)
        } catch (e: IllegalStateException) {
            null
        } catch (e: SecurityException) {
            logger.w(e) { "Optional model '${model.id}' failed verification; running without it" }
            null
        }

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
         *
         * With [allowReinstall] false the function never downloads: a
         * missing model throws, and a load-verification failure quarantines
         * the file (removing the corrupt bytes) and then throws. This is
         * the engine init path, kept out of the download flow so a
         * corrupted model cannot trigger a silent metered re-download; the
         * caller surfaces it as not-installed to the visible download UI.
         */
        internal suspend fun resolveVerifiedModelPath(
            installer: ModelFileInstaller,
            loadVerified: MutableMap<String, Boolean>,
            model: WhisperModel,
            allowReinstall: Boolean = true,
        ): String {
            if (!installer.isInstalled(model)) {
                if (!allowReinstall) {
                    throw IllegalStateException(
                        "Model '${model.id}' is not installed; the init path does not download",
                    )
                }
                installer.install(model)
                loadVerified.remove(model.id)
            }
            if (loadVerified[model.id] != true) {
                if (!installer.verifyOnLoad(model)) {
                    // verifyOnLoad has quarantined the corrupt file. On the
                    // download path, one fresh verified reinstall then the
                    // same gate again; two failures mean something is
                    // persistently wrong and the engine must not load it.
                    if (!allowReinstall) {
                        throw SecurityException(
                            "Model '${model.id}' failed load-time verification; " +
                                "quarantined, awaiting re-download through the download flow",
                        )
                    }
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
        /**
         * The catalog entry a download or resolve may target: a speech
         * model, or the detector by its own id so the background download
         * job fetches it through the same verified path; null for anything
         * else, since there is no pin to verify such a download against.
         */
        internal fun catalogModelForDownload(modelId: String): WhisperModel? =
            WhisperModelCatalog.byId(modelId) ?: WhisperModelCatalog.VAD_MODEL.takeIf { it.id == modelId }

        /**
         * Resolves [model] through [resolveVerifiedModelPath] under its own
         * mutex and, on the download path, brings [detector] along under
         * the detector's mutex, so a fresh install never dictates without
         * trimming; a detector failure never fails the model the user asked
         * for. Static so the orchestration is under host tests with a mock
         * installer.
         */
        internal suspend fun resolveModelWithDetector(
            installer: ModelFileInstaller,
            loadVerified: MutableMap<String, Boolean>,
            model: WhisperModel,
            allowReinstall: Boolean,
            detector: WhisperModel = WhisperModelCatalog.VAD_MODEL,
        ): String {
            val path = mutexFor(model.id).withLock {
                resolveVerifiedModelPath(installer, loadVerified, model, allowReinstall)
            }
            if (allowReinstall && model.id != detector.id) {
                try {
                    mutexFor(detector.id).withLock {
                        resolveVerifiedModelPath(installer, loadVerified, detector, allowReinstall = true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(e) { "Voice activity detector install failed; dictation runs without it" }
                }
            }
            return path
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
    override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String = withContext(Dispatchers.IO) {
        val model = catalogModelForDownload(modelId)
            ?: throw IllegalStateException("No catalog entry for model '$modelId'; refusing to download")
        resolveModelWithDetector(installer, loadVerified, model, allowReinstall)
    }

    override suspend fun getVadModelPath(): String? = withContext(Dispatchers.IO) {
        mutexFor(WhisperModelCatalog.VAD_MODEL.id).withLock {
            resolveOptionalModelPath(installer, loadVerified, WhisperModelCatalog.VAD_MODEL)
        }
    }

    override fun isVadModelInstalled(): Boolean =
        isInstalledShapeIn(modelsDir, WhisperModelCatalog.VAD_MODEL)

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

    // Raw directory view (minus staging and the detector): includes stale
    // engine models on purpose so they stay visible to the sweep and
    // deletable in the UI.
    override fun getDownloadedModels(): List<String> = modelDirNamesIn(modelsDir)

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
     * Device-appropriate default when nothing is configured: the same
     * single [WhisperModelCatalog.recommended] decision the ModelManager
     * uses, over the same [Platform] RAM/locale source, so a bare
     * getSTTModelPath and the recommendation prompt can never pick
     * different models for one device.
     */
    private fun recommendedDefault(): WhisperModel {
        val model = WhisperModelCatalog.recommended(
            totalRamBytes = platform.totalRamBytes(),
            preferEnglishOnly = platform.prefersEnglishModels(),
        ).model
        logger.d { "Recommended model for this device: ${model.id}" }
        return model
    }
}
