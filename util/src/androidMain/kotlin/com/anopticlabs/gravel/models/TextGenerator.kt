package com.anopticlabs.gravel.models

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.llm.ChatMessage
import com.anopticlabs.gravel.llm.LlmEngineClient
import com.anopticlabs.gravel.llm.LlmEngineLimits
import com.anopticlabs.gravel.llm.LlmEngineTimeoutException
import com.anopticlabs.gravel.llm.LlmEngineUnavailableException
import com.anopticlabs.gravel.llm.LlmGenerateStats
import com.anopticlabs.gravel.llm.isLlmSupported
import com.anopticlabs.gravel.transcription.DictationPriority
import com.anopticlabs.gravel.transcription.awaitPreemptibleEngineWork
import coredevices.util.transcription.CactusModelPathProvider
import coredevices.whisper.WhisperEngineUnavailableException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * No text model can serve a generation: none is installed, the installed one failed its check or
 * would not load, or this CPU cannot run the engine.
 */
class TextModelUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The prompt does not fit the context, so the same prompt would fail again. */
class TextPromptTooLongException(message: String) : Exception(message)

/** Dictation kept the engines busy through every wait. */
class TextEngineHeldException(message: String) : Exception(message)

/** The engine calls [TextGenerator] makes, apart so host tests can stand in for the engine process. */
internal interface TextEngineCalls {
    fun supported(): Boolean
    fun init(modelPath: String, contextTokens: Int): Long
    fun formatChat(handle: Long, messages: List<ChatMessage>): String
    fun generate(handle: Long, prompt: String, maxTokens: Int, threads: Int, callId: Long, stats: LlmGenerateStats): String
    fun cancel(callId: Long)
    fun free(handle: Long)
    fun processGeneration(): Long
    fun addDeathListener(listener: (generation: Long) -> Unit)
    fun endProcess(reason: String)
}

internal object LlmEngineCalls : TextEngineCalls {
    override fun supported(): Boolean = isLlmSupported()
    override fun init(modelPath: String, contextTokens: Int): Long = LlmEngineClient.init(File(modelPath), contextTokens)
    override fun formatChat(handle: Long, messages: List<ChatMessage>): String = LlmEngineClient.formatChat(handle, messages)
    override fun generate(handle: Long, prompt: String, maxTokens: Int, threads: Int, callId: Long, stats: LlmGenerateStats): String =
        LlmEngineClient.generate(handle, prompt, maxTokens, threads, callId, stats = stats)
    override fun cancel(callId: Long) = LlmEngineClient.cancel(callId)
    override fun free(handle: Long) = LlmEngineClient.free(handle)
    override fun processGeneration(): Long = LlmEngineClient.processGeneration()
    override fun addDeathListener(listener: (generation: Long) -> Unit) = LlmEngineClient.addDeathListener(listener)
    override fun endProcess(reason: String) = LlmEngineClient.endProcess(reason)
}

/**
 * Notebook text generation on the `:llm` engine, one call at a time. The picked model is freed
 * [IDLE_TIMEOUT] after its last use, or sooner by [release] or [onModelDeleted]. Loading and
 * generating give way to dictation: each waits while a hold is up, and a hold raised mid-call stops
 * the call, which starts again; a load already under way finishes. A use and the speed probe never overlap ([InferenceExclusion]).
 */
class TextGenerator internal constructor(
    private val provider: CactusModelPathProvider,
    private val selectedModelId: () -> String?,
    private val recommendedModel: () -> LlmModel?,
    private val dictation: DictationPriority,
    private val exclusion: InferenceExclusion,
    private val engine: TextEngineCalls,
    private val threads: () -> Int,
    private val scope: CoroutineScope,
    private val idleTimeout: Duration = IDLE_TIMEOUT,
    private val holdWait: Duration = HOLD_WAIT,
    private val unwindBound: Duration = UNWIND_BOUND,
) {
    constructor(
        provider: CactusModelPathProvider,
        selectedModelId: () -> String?,
        recommendedModel: () -> LlmModel?,
        dictation: DictationPriority,
        exclusion: InferenceExclusion,
    ) : this(
        provider, selectedModelId, recommendedModel, dictation, exclusion,
        LlmEngineCalls, ::textGenerationThreadCount, CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    companion object {
        /** The context every Notebook generation runs in. */
        const val CONTEXT_TOKENS = 8192

        /** Engine losses, or hold waits that ran out, one use retries before failing. */
        internal const val MAX_RETRIES = 3

        private val IDLE_TIMEOUT = 5.minutes
        private val HOLD_WAIT = 5.minutes
        private val UNWIND_BOUND = 15.seconds
        private val logger = Logger.withTag("TextGenerator")

        // Process-wide, so no two generators share a call id: the engine keeps a cancel for an id not yet running.
        private val callIds = AtomicLong(1L shl 32)
    }

    private class Loaded(val model: LlmModel, val handle: Long, val generation: Long)

    private sealed interface Attempt<out T> {
        class Done<T>(val value: T) : Attempt<T>
        object Stopped : Attempt<Nothing>
        class Lost(val loadingModel: String?) : Attempt<Nothing>
    }

    // Every engine call and every change to [loaded] happens under this.
    private val engineMutex = Mutex()

    @Volatile
    private var loaded: Loaded? = null

    // The model whose last load failed; cleared by a load that succeeds or the delete of its file.
    @Volatile
    private var failedLoad: String? = null

    private val deathWatch = AtomicBoolean(false)
    private val idleLock = Any()
    private var idleJob: Job? = null

    /** The catalog's text models installed in a usable shape; reads the disk. */
    fun installedModels(): List<LlmModel> {
        val usable = provider.getDownloadedModels().toSet() - provider.getIncompatibleModels().toSet()
        return LlmModelCatalog.MODELS.filter { it.id in usable }
    }

    /** The model a use would load now; reads the disk. */
    fun installedModel(): LlmModel? = pickTextModel(installedModels(), selectedModelId(), recommendedModel())

    /** True while the model a use would load is the one whose last load failed in this process; reads the disk. */
    fun lastLoadFailed(): Boolean = failedLoad?.let { it == installedModel()?.id } ?: false

    /** Loads the model now, so a following [generate] starts at once; fails as [generate] does. */
    suspend fun prepare() = use { Attempt.Done(Unit) }

    /** [prepare] in the background; a failure is logged and the load is left to the next [generate]. */
    fun preload() {
        scope.launch {
            try {
                prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w { "text model preload failed: ${e::class.simpleName}" }
            }
        }
    }

    /**
     * One greedy reply to [user] under [system], of at most [maxTokens] tokens (clamped to the
     * engine's bound); both contents are trimmed first, as the pinned models' templates trim them.
     * Fails with [TextModelUnavailableException], [TextPromptTooLongException],
     * [TextEngineHeldException], IllegalArgumentException for content the engine refuses,
     * [LlmEngineTimeoutException] for a call past its deadline (not retried),
     * [LlmEngineUnavailableException] when an engine loss uses up the retries, which hold waits that ran out share
     * ([MAX_RETRIES]), or [TextModelUnavailableException] when every counted event of the use was a loss during a
     * load, and RuntimeException for any other engine failure.
     */
    suspend fun generate(system: String, user: String, maxTokens: Int): String {
        val bounded = maxTokens.coerceIn(1, LlmEngineLimits.MAX_TOKENS)
        return use { current -> runOnce(current, system.trim(), user.trim(), bounded) }
    }

    /** Frees the model now; the next use loads it again. */
    suspend fun release() {
        cancelIdle()
        withContext(Dispatchers.IO) { engineMutex.withLock { freeLoaded() } }
    }

    /**
     * For after [modelId]'s file was deleted: frees the model if it is loaded, once a use holding the
     * engine ends, and forgets a failed load of it, so a new download of it is tried again.
     */
    suspend fun onModelDeleted(modelId: String) {
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                if (loaded?.model?.id == modelId) freeLoaded()
                // Also a failure of a use that held the engine until now.
                if (failedLoad == modelId) failedLoad = null
            }
        }
    }

    private suspend fun <T> use(block: suspend (Loaded) -> Attempt<T>): T {
        cancelIdle()
        try {
            return withContext(Dispatchers.IO) { engineMutex.withLock { useLocked(block) } }
        } finally {
            armIdle()
        }
    }

    private suspend fun <T> useLocked(block: suspend (Loaded) -> Attempt<T>): T {
        var retries = 0
        var loadLosses = 0
        while (true) {
            if (withTimeoutOrNull(holdWait) { dictation.awaitReleased() } == null) {
                if (retries++ >= MAX_RETRIES) throw TextEngineHeldException("dictation held the engines through ${MAX_RETRIES + 1} waits")
                continue
            }
            val attempt = exclusion.withLock {
                val current = try {
                    ensureLoaded()
                } catch (e: LlmEngineUnavailableException) {
                    return@withLock Attempt.Lost(loadingModel = installedModel()?.id)
                }
                block(current)
            }
            when (attempt) {
                is Attempt.Done -> return attempt.value
                Attempt.Stopped -> continue
                is Attempt.Lost -> {
                    val model = attempt.loadingModel
                    if (model != null) loadLosses++
                    if (retries++ >= MAX_RETRIES) {
                        // Marked when each of this use's counted events was an engine loss during a load.
                        if (model != null && loadLosses > MAX_RETRIES) {
                            failedLoad = model
                            throw TextModelUnavailableException("text model $model: the engine was lost during ${MAX_RETRIES + 1} loads")
                        }
                        throw LlmEngineUnavailableException("the text engine was lost and the retries are used up")
                    }
                }
            }
        }
    }

    private suspend fun runOnce(current: Loaded, system: String, user: String, maxTokens: Int): Attempt<String> {
        val prompt = try {
            engine.formatChat(current.handle, listOf(ChatMessage("system", system), ChatMessage("user", user))) + current.model.promptSuffix
        } catch (e: LlmEngineUnavailableException) {
            forget(current)
            return Attempt.Lost(loadingModel = null)
        }
        if (dictation.isHeld) return Attempt.Stopped
        val callId = callIds.incrementAndGet()
        val threadCount = threads()
        val stats = LlmGenerateStats()
        // Watches from before the call starts; the engine keeps a cancel that arrives before the call does.
        val preemption = scope.async { dictation.awaitRaised() }
        try {
            val worker = scope.async {
                runCatching { engine.generate(current.handle, prompt, maxTokens, threadCount, callId, stats) }
            }
            val text = awaitPreemptibleEngineWork(
                worker = worker,
                preemption = preemption,
                cancel = { engine.cancel(callId) },
                unwindBound = unwindBound,
                onWedged = {
                    forget(current)
                    engine.endProcess("a text generation did not stop within $unwindBound")
                },
            )
            logger.i { "generated ${stats.generatedTokens} tokens from ${stats.promptTokens} in ${stats.prefillMillis}+${stats.decodeMillis} ms (${stats.stopReason})" }
            return Attempt.Done(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmEngineTimeoutException) {
            forget(current)
            throw e
        } catch (e: LlmEngineUnavailableException) {
            forget(current)
            return Attempt.Lost(loadingModel = null)
        } catch (e: WhisperEngineUnavailableException) {
            // awaitPreemptibleEngineWork's report of a call that would not stop; onWedged ended the process.
            return Attempt.Lost(loadingModel = null)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            if (preemption.isCompleted && !preemption.isCancelled) return Attempt.Stopped
            if (stats.promptTokens >= CONTEXT_TOKENS) {
                throw TextPromptTooLongException("a prompt of ${stats.promptTokens} tokens does not fit the context of $CONTEXT_TOKENS")
            }
            throw e
        } finally {
            preemption.cancel()
        }
    }

    /**
     * The picked model, loaded; a model loaded in a process that has since died counts as not
     * loaded. A load the engine refuses, or one past its deadline, is a model that cannot be used
     * here; a lost engine is the caller's retry. Either drops the file's verification memo.
     */
    private suspend fun ensureLoaded(): Loaded {
        watchDeaths()
        if (!engine.supported()) throw TextModelUnavailableException("this device cannot run the text engine")
        val model = installedModel() ?: throw TextModelUnavailableException("no text model is installed")
        loaded?.let { current ->
            if (current.model.id == model.id && current.generation == engine.processGeneration()) return current
            freeLoaded()
        }
        val path = try {
            provider.getModelPath(model.id, allowReinstall = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TextModelUnavailableException("text model ${model.id} is not usable: ${e.message}", e)
        }
        val handle = try {
            engine.init(path, CONTEXT_TOKENS)
        } catch (e: LlmEngineTimeoutException) {
            provider.forgetLoadVerification(model.id)
            failedLoad = model.id
            throw TextModelUnavailableException("text model ${model.id} did not load in time", e)
        } catch (e: LlmEngineUnavailableException) {
            provider.forgetLoadVerification(model.id)
            throw e
        } catch (e: RuntimeException) {
            provider.forgetLoadVerification(model.id)
            failedLoad = model.id
            throw TextModelUnavailableException("text model ${model.id} could not be loaded", e)
        }
        failedLoad = null
        return Loaded(model, handle, engine.processGeneration()).also { loaded = it }
    }

    private fun watchDeaths() {
        if (!deathWatch.compareAndSet(false, true)) return
        engine.addDeathListener { generation ->
            val current = loaded ?: return@addDeathListener
            if (current.generation == generation) forget(current)
        }
    }

    // A death drops the load's verification memo too: a corrupt model file is one thing that ends the process.
    private fun forget(current: Loaded) {
        if (loaded === current) loaded = null
        provider.forgetLoadVerification(current.model.id)
    }

    private fun freeLoaded() {
        val current = loaded ?: return
        loaded = null
        engine.free(current.handle)
    }

    private fun cancelIdle() {
        synchronized(idleLock) {
            idleJob?.cancel()
            idleJob = null
        }
    }

    private fun armIdle() {
        synchronized(idleLock) {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(idleTimeout)
                try {
                    engineMutex.withLock { freeLoaded() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w { "idle free of the text model failed: ${e::class.simpleName}" }
                }
            }
        }
    }
}
