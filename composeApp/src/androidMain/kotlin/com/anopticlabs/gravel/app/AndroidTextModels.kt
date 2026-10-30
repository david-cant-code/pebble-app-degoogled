package com.anopticlabs.gravel.app

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.models.LlmModel
import com.anopticlabs.gravel.models.LlmModelCatalog
import com.anopticlabs.gravel.models.ModelRole
import com.anopticlabs.gravel.models.TextDownloadFollower
import com.anopticlabs.gravel.models.TextGenerator
import com.anopticlabs.gravel.models.isTextEngineSupported
import com.anopticlabs.gravel.notebook.alarms.NotebookPreferences
import com.anopticlabs.gravel.notebook.isNotebookSupported
import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.ui.TextModelFacts
import com.anopticlabs.gravel.ui.TextModels
import coredevices.util.models.ModelDownloadManager
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.ModelManager
import coredevices.util.transcription.CactusModelPathProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import org.koin.dsl.binds
import org.koin.dsl.module

/** What [AndroidTextModels] reads and changes; [AppTextModelHost] binds it to the app's stores and engines. */
interface TextModelHost {
    fun engineSupported(): Boolean
    fun installed(): List<String>
    fun inUse(): String?
    fun lastLoadFailed(): Boolean
    fun partialBytes(modelId: String): Long
    var chosen: String?
    val download: StateFlow<ModelDownloadStatus>

    /** False when the system refused to schedule the download. */
    fun startDownload(model: LlmModel): Boolean

    /** Sets the text lane's status where the download job reports nothing. */
    fun setDownload(modelId: String, status: ModelDownloadStatus)
    fun cancelDownload()

    /** Cancels a scheduled or running download of [modelId], and no other. */
    fun cancelDownloadOf(modelId: String)
    fun deleteInstalled(modelId: String)
    suspend fun discardPartial(modelId: String)
    suspend fun forgetDeleted(modelId: String)
    fun requestDrain()
}

/**
 * Gravel: Settings > AI > LLM on Android. A text download that finishes with its model installed, and that the
 * user did not cancel, becomes the Notebook's choice; that, a choice and a delete of an installed model each ask
 * the capture drain to run, since waiting recordings may now process. The download job reports a finished download
 * ([finished]) in whichever process it ran. The text lane is followed from the page's first call on, so this class
 * builds no download manager at app start. Every read and action of the page runs on [scope], off the main thread.
 */
class AndroidTextModels(
    override val supported: Boolean,
    hostOf: () -> TextModelHost,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logger.w(e) { "text model action failed" } },
    ),
) : TextModels, TextDownloadFollower {
    private val host by lazy(hostOf)

    private val _facts = MutableStateFlow(TextModelFacts())
    override val facts: StateFlow<TextModelFacts> = _facts.asStateFlow()

    // One read at a time, each from the current state, so the last one written is the newest.
    private val reads = Mutex()

    // One download decision at a time, so two quick taps cannot both start one.
    private val starts = Mutex()

    private val watching = AtomicBoolean(false)

    // The download the user cancelled: its file can already be in place when the cancel lands.
    @Volatile
    private var cancelled: String? = null

    private fun watch() {
        if (!supported || !watching.compareAndSet(false, true)) return
        // A failed read is logged, and the lane is still followed after it.
        scope.launch { host.download.collect { logged { read() } } }
    }

    override suspend fun finished(modelId: String) {
        if (!supported || LlmModelCatalog.byId(modelId) == null) return
        logged { settled(modelId) }
        logged { read() }
    }

    private suspend fun logged(step: suspend () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "a text model read or follow-up failed" }
        }
    }

    private fun settled(modelId: String) {
        if (cancelled == modelId) {
            cancelled = null
        } else if (modelId in host.installed()) {
            host.chosen = modelId
            host.requestDrain()
        }
    }

    private fun action(block: suspend () -> Unit) {
        watch()
        scope.launch {
            block()
            read()
        }
    }

    override fun refresh() = action {}

    override fun download(modelId: String) {
        val model = LlmModelCatalog.byId(modelId) ?: return
        action {
            starts.withLock {
                if (host.engineSupported() && host.download.value !is ModelDownloadStatus.Downloading) {
                    cancelled = null
                    // Shown before scheduling: the job reports itself only once it runs, which can wait for a network.
                    host.setDownload(model.id, ModelDownloadStatus.Downloading(model.id))
                    if (!host.startDownload(model)) {
                        host.setDownload(model.id, ModelDownloadStatus.Failed(model.id, "Download could not be started"))
                    }
                }
            }
        }
    }

    override fun cancelDownload() = action {
        starts.withLock {
            val running = (host.download.value as? ModelDownloadStatus.Downloading)?.modelSlug
            cancelled = running
            host.cancelDownload()
            // JobScheduler stops only a job that already runs (android16-release, JobScheduler.cancel), so one
            // cancelled before it ran reports nothing.
            if (running != null) host.setDownload(running, ModelDownloadStatus.Idle)
        }
    }

    override fun choose(modelId: String) = action {
        if (modelId in host.installed()) {
            host.chosen = modelId
            host.requestDrain()
        }
    }

    override fun delete(modelId: String) {
        if (LlmModelCatalog.byId(modelId) == null) return
        action {
            if (modelId in host.installed()) {
                host.deleteInstalled(modelId)
                if (host.chosen == modelId) host.chosen = null
                read()
                try {
                    // Waits for a generation using the model to end.
                    host.forgetDeleted(modelId)
                } finally {
                    host.requestDrain()
                }
            } else {
                // A download the system paused stays scheduled and would bring the part back.
                host.cancelDownloadOf(modelId)
                host.discardPartial(modelId)
            }
        }
    }

    private suspend fun read() = reads.withLock {
        _facts.value = TextModelFacts(
            read = true,
            engineSupported = host.engineSupported(),
            installed = host.installed().toSet(),
            partialBytes = LlmModelCatalog.MODELS.associate { it.id to host.partialBytes(it.id) }.filterValues { it > 0L },
            chosen = host.chosen,
            inUse = host.inUse(),
            inUseLoadFailed = host.lastLoadFailed(),
            download = host.download.value,
        )
    }

    private companion object {
        val logger = Logger.withTag("AndroidTextModels")
    }
}

class AppTextModelHost(
    private val generator: TextGenerator,
    private val provider: CactusModelPathProvider,
    private val prefs: NotebookPreferences,
    private val downloads: ModelDownloadManager,
    private val modelManager: ModelManager,
    private val coordinator: () -> CaptureCoordinator,
) : TextModelHost {
    override fun engineSupported() = isTextEngineSupported()
    override fun installed() = generator.installedModels().map { it.id }
    override fun inUse() = generator.installedModel()?.id
    override fun lastLoadFailed() = generator.lastLoadFailed()
    override fun partialBytes(modelId: String) = provider.partialDownloadBytes(modelId)
    override var chosen: String?
        get() = prefs.textModelId
        set(value) = prefs.setTextModelId(value)
    override val download: StateFlow<ModelDownloadStatus> = downloads.downloadStatus(ModelRole.Text)
    override fun startDownload(model: LlmModel) = modelManager.downloadTextModel(model)
    override fun setDownload(modelId: String, status: ModelDownloadStatus) = downloads.updateDownloadStatus(modelId, status)
    override fun cancelDownload() = downloads.cancelDownload(ModelRole.Text)
    override fun cancelDownloadOf(modelId: String) = downloads.cancelDownloadOf(modelId)
    override fun deleteInstalled(modelId: String) = modelManager.deleteModel(modelId)
    override suspend fun discardPartial(modelId: String) = provider.discardPartialDownload(modelId)
    override suspend fun forgetDeleted(modelId: String) = generator.onModelDeleted(modelId)
    override fun requestDrain() = coordinator().requestAgain()
}

val textModelsModule = module {
    single {
        AndroidTextModels(
            supported = isNotebookSupported,
            hostOf = { AppTextModelHost(get(), get(), get(), get(), get(), coordinator = { get() }) },
        )
    } binds arrayOf(TextModels::class, TextDownloadFollower::class)
}
