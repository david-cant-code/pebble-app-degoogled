package coredevices.util.transcription

import co.touchlab.kermit.Logger
import coredevices.analytics.CoreAnalytics
import coredevices.resampler.Resampler
import coredevices.util.CoreConfigFlow
import coredevices.util.models.CactusSTTMode
import coredevices.util.models.WhisperModelCatalog
import coredevices.whisper.isWhisperSupported
import coredevices.whisper.pcm16ToShorts
import coredevices.whisper.shortsToFloats
import coredevices.whisper.whisperFree
import coredevices.whisper.whisperInit
import coredevices.whisper.whisperSetCancel
import coredevices.whisper.whisperTranscribe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

expect suspend fun withHighPriorityThread(block: suspend () -> Unit)
expect suspend fun getFreeMemoryMB(): Long
expect val PLATFORM_MIN_TRANSCRIPTION_MEMORY_MB: Long

/** Engine thread count for one transcription; bounded, whisper scales poorly past a few cores. */
expect fun transcriptionThreadCount(): Int

private val nonSpeechRegex = "\\[[^\\]]*\\]|\\([^)]*\\)".toRegex()

/**
 * Throws [TranscriptionException.NoSpeechDetected] if [text] is blank or contains no usable speech
 * (only noise / non-speech tokens / stutters). Returns normally otherwise.
 *
 * Used both as the final guard on a transcription result and, for [CactusSTTMode.LocalFirst], to
 * treat an empty local result as a failure that triggers the remote fallback (HARD-324).
 */
internal fun validateContainsSpeech(text: String?, modelUsed: String?) {
    when {
        text.isNullOrBlank() ->
            throw TranscriptionException.NoSpeechDetected("empty_result", modelUsed = modelUsed)
        text.length < 2 ->
            throw TranscriptionException.NoSpeechDetected("too_short", modelUsed = modelUsed)
        text.replace(nonSpeechRegex, "").isBlank() ->
            throw TranscriptionException.NoSpeechDetected("non_speech_tokens", modelUsed = modelUsed)
        text.replace("s*", "").lowercase().count { it.isLetterOrDigit() } < 2 ->
            throw TranscriptionException.NoSpeechDetected("stutters_or_noise", modelUsed = modelUsed)
    }
}

/**
 * The language handed to the engine for one transcription. The English-only
 * models decode only English, so the spoken-language preference must never
 * reach them (whisper would warn and misbehave); multilingual models get the
 * preference as-is, with null meaning in-engine detection. Static so this
 * mapping stays under host tests.
 */
internal fun whisperLanguageFor(modelMultilingual: Boolean?, spokenLanguage: String?): String? =
    when (modelMultilingual) {
        false -> "en"
        else -> spokenLanguage
    }

/**
 * Awaits [worker], a blocking native engine call running on another thread,
 * such that cancelling the waiting coroutine genuinely interrupts the
 * engine. A thread blocked inside a native call cannot observe coroutine
 * cancellation, so the waiter and the blocked thread must be different
 * threads: this waiter is the cancellable side, and on cancellation it arms
 * the engine's abort flag via [setCancel] (checked by the engine between
 * its encoder and decoder passes), then holds the already-cancelled caller
 * until the worker has actually unwound. The hold preserves the one-native-call-at-a-
 * time invariant the caller's mutex provides: resuming while the engine is
 * still inside the context would let the next transcription run against it
 * concurrently. The hold is bounded by [unwindBound] so an engine that
 * never honours the abort cannot hold the mutex forever (the exact stuck
 * state cancellation exists to prevent); a blown bound runs [onWedged]
 * before rethrowing so the owner can quarantine the still-occupied native
 * context.
 *
 * [worker] must not be a child of the waiter (cancelling the waiter must
 * leave the work free to finish unwinding) and must wrap the engine call in
 * [runCatching] (an engine failure must not tear down the worker's scope).
 * Static so the cancel/unwind/wedge contract stays under host tests.
 */
internal suspend fun <T> awaitEngineWork(
    worker: Deferred<Result<T>>,
    setCancel: (Boolean) -> Unit,
    unwindBound: Duration,
    onWedged: () -> Unit,
): T {
    try {
        return worker.await().getOrThrow()
    } catch (e: CancellationException) {
        if (currentCoroutineContext().isActive) {
            // The cancellation came from the worker's side (its scope died),
            // not from this caller: surface an engine failure instead of a
            // phantom cancellation of a coroutine nobody cancelled.
            throw IllegalStateException("Engine worker cancelled unexpectedly", e)
        }
        setCancel(true)
        val unwound = withContext(NonCancellable) {
            withTimeoutOrNull(unwindBound) {
                worker.join()
                true
            } ?: false
        }
        if (!unwound) onWedged()
        throw e
    }
}

/**
 * Local speech-to-text over the whisper.cpp engine (:whisper bindings).
 * Successor to the Cactus-era service with the same shape: the mode/model
 * config flow drives (re)initialization, a rendezvous channel reports init
 * settling, and two mutexes serialize the native handle. The two-mutex
 * split ([transcriptionMutex] taken with tryLock as the busy gate,
 * [modelMutex] guarding every native call) is load-bearing: the warm-up
 * path yields to an in-flight transcription instead of queueing behind it,
 * which is what keeps a warm-up racing a real dictation from wedging
 * either.
 *
 * Engine input is 16 kHz mono float PCM handed over in memory; audio at
 * any other rate is resampled first. Cancellation is cooperative through
 * the engine's abort callback: a process-wide flag armed on caller
 * cancellation, safe exactly because these mutexes allow one native
 * transcription at a time.
 */
class WhisperTranscriptionService(
    private val coreConfigFlow: CoreConfigFlow,
    private val modelProvider: CactusModelPathProvider,
    private val analytics: CoreAnalytics,
    private val inferenceBoost: InferenceBoost = NoOpInferenceBoost()
) {
    companion object {
        private val logger = Logger.withTag("WhisperTranscriptionService")

        /** The engine's fixed input rate; other rates are resampled to it. */
        internal const val ENGINE_SAMPLE_RATE = 16_000

        /**
         * How long a cancelled transcription may take to unwind out of the
         * native call after the abort flag is armed. The engine checks the
         * flag only between encoder and decoder passes, and one encoder
         * pass is seconds-scale for the bigger catalog models on
         * phone-class CPUs, so the bound must comfortably exceed a single
         * pass. Blowing it means the engine is wedged, and the native
         * context gets abandoned rather than risking a concurrent next
         * call against it.
         */
        private val ENGINE_UNWIND_BOUND = 10.seconds
    }

    private val transcriptionMutex = Mutex()
    private var modelHandle: Long = 0L
    private var initJob: Job? = null
    private var lastInitedModel: String? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    val lastModelUsed get() = lastInitedModel
    val isModelReady get() = modelHandle != 0L
    val configuredModel get() = sttConfig.value.modelName
    val onInitialized = Channel<Boolean>(Channel.RENDEZVOUS)

    private val sttConfig = coreConfigFlow.flow.map { it.sttConfig }.stateIn(
        scope,
        started = kotlinx.coroutines.flow.SharingStarted.Lazily,
        initialValue = coreConfigFlow.value.sttConfig
    )

    init {
        sttConfig.onEach {
            logger.i { "STT config changed: $it" }
            if (it.modelName != lastInitedModel) {
                initJob = performInit()
            }
        }.launchIn(scope)
    }

    private var lastTranscriptionAt: TimeMark? = null
    private val modelMutex = Mutex()

    // 1 second of 16 kHz silence for the warm-up pass; the engine's first
    // inference after load pays one-time setup costs the warm-up absorbs.
    private val silentPcm = FloatArray(ENGINE_SAMPLE_RATE)

    /**
     * Runs [block], a blocking native engine call, with working
     * cancellation: the call is dispatched to a worker on the service
     * [scope] while this coroutine waits at a cancellable suspension point,
     * and a cancelled wait arms the process-wide abort flag whisper polls
     * between inference steps (see [awaitEngineWork] for the unwind and
     * wedge contract). The split matters because a coroutine whose own
     * thread is inside the native call has no suspension point left: nothing
     * observing only that coroutine can act until the call it was supposed
     * to interrupt has already returned.
     *
     * The flag is process-wide, which is sound only because [modelMutex]
     * allows a single native call at a time; clearing it on entry discards
     * any stale request from a previous cancelled call. A cancel that lands
     * before the worker even reaches the engine is still honoured: the
     * worker is not cancelled with the caller, and the already-armed flag
     * is read at the engine's first abort-callback poll.
     */
    private suspend fun <T> withWhisperCancelOnCancel(block: () -> T): T {
        whisperSetCancel(false)
        val worker = scope.async(Dispatchers.IO) { runCatching { block() } }
        return awaitEngineWork(
            worker = worker,
            setCancel = { requested ->
                logger.d { "Requesting whisper engine abort (caller cancelled)" }
                whisperSetCancel(requested)
            },
            unwindBound = ENGINE_UNWIND_BOUND,
            onWedged = ::abandonWedgedContext,
        )
    }

    /**
     * Containment for an engine that ignored the abort flag past
     * [ENGINE_UNWIND_BOUND]: a thread may still be inside the native
     * context, so freeing it would be a use-after-free. The context is
     * leaked deliberately and the handle zeroed (still under [modelMutex])
     * so the next transcription re-initializes a fresh context instead of
     * racing the stuck call inside the old one.
     */
    private fun abandonWedgedContext() {
        logger.e {
            "Whisper engine ignored cancellation for $ENGINE_UNWIND_BOUND; " +
                "abandoning the native context and forcing re-init"
        }
        modelHandle = 0L
        lastInitedModel = null
    }

    /** Run the engine with the memory guard and cancellation support. */
    private suspend fun cancellableTranscribe(handle: Long, pcm: FloatArray): String {
        val freeMemory = try {
            getFreeMemoryMB()
        } catch (e: Exception) {
            logger.w(e) { "Failed to get free memory" }
            0L
        }
        if (freeMemory < PLATFORM_MIN_TRANSCRIPTION_MEMORY_MB) {
            logger.e { "Low free memory ($freeMemory MB), skipping local transcription" }
            throw TranscriptionException.NotEnoughMemory(modelUsed = sttConfig.value.modelName)
        }
        val language = whisperLanguageFor(
            modelMultilingual = sttConfig.value.modelName
                ?.let { WhisperModelCatalog.byId(it) }?.multilingual,
            spokenLanguage = sttConfig.value.spokenLanguage,
        )
        return withWhisperCancelOnCancel {
            whisperTranscribe(handle, pcm, transcriptionThreadCount(), language).trim()
        }
    }

    /**
     * PCM16 bytes to the engine's float format, resampling when the source
     * rate differs from [ENGINE_SAMPLE_RATE]. Watches report 16 kHz in
     * practice, so the resample path is a compatibility net, not the norm.
     */
    private fun toEngineFloats(audio: ByteArray, sampleRate: Int): FloatArray {
        require(sampleRate > 0) { "Invalid sample rate $sampleRate" }
        var samples = pcm16ToShorts(audio)
        if (sampleRate != ENGINE_SAMPLE_RATE) {
            logger.i { "Resampling ${sampleRate}Hz audio to ${ENGINE_SAMPLE_RATE}Hz for the engine" }
            samples = Resampler(sampleRate, ENGINE_SAMPLE_RATE).process(samples)
        }
        return shortsToFloats(samples)
    }

    private suspend fun warmUpIfIdle() {
        // Warm up only when we haven't recently warmed up / transcribed
        if ((lastTranscriptionAt?.elapsedNow() ?: Duration.INFINITE) < 2.minutes) {
            lastTranscriptionAt = TimeSource.Monotonic.markNow()
            return
        }
        logger.d { "Warming up whisper STT model with silent audio" }
        val freeMemory = try {
            getFreeMemoryMB()
        } catch (e: Exception) {
            logger.w(e) { "Failed to get free memory" }
            0L
        }
        if (freeMemory < PLATFORM_MIN_TRANSCRIPTION_MEMORY_MB) {
            logger.w { "Low free memory ($freeMemory MB), skipping warmup" }
            return
        }
        lastTranscriptionAt = TimeSource.Monotonic.markNow()
        if (!modelMutex.tryLock()) {
            logger.d { "Skipping warmup, transcription in progress" }
            return
        }
        try {
            val handle = modelHandle
            if (handle == 0L) return
            withHighPriorityThread {
                try {
                    withTimeout(2.seconds) {
                        withWhisperCancelOnCancel {
                            // Fixed "en": silence has no language to detect,
                            // and detection would only add an extra pass.
                            whisperTranscribe(handle, silentPcm, transcriptionThreadCount(), "en")
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.w { "Whisper STT warmup timed out" }
                }
            }
        } finally {
            modelMutex.unlock()
        }
    }

    private suspend fun initIfNeeded() {
        val config = sttConfig.value
        if (config.mode == CactusSTTMode.RemoteOnly) return
        if (!isWhisperSupported()) return
        // The configured model is the single source of truth; null means no
        // local model has been chosen yet (fresh install, or the migration
        // sweep cleared it) and there is nothing to initialize.
        val modelName = config.modelName ?: run {
            logger.d { "No STT model configured, skipping init" }
            return
        }
        if (!modelProvider.isModelDownloaded(modelName) ||
            modelName in modelProvider.getIncompatibleModels()
        ) {
            logger.w { "STT model '$modelName' unavailable or needs update, skipping init" }
            return
        }
        val start = Clock.System.now()
        if (config.modelName != lastInitedModel) {
            if (modelHandle != 0L) {
                whisperFree(modelHandle)
                modelHandle = 0L
            }
        }
        if (modelHandle == 0L) {
            // getModelPath re-verifies the installed file before returning
            // it (quarantining a mismatch); the download branch is
            // unreachable here behind the isModelDownloaded gate above.
            val modelPath = modelProvider.getModelPath(modelName)
            modelHandle = whisperInit(modelPath)
            lastInitedModel = config.modelName
            // A fresh handle is cold no matter how recently the previous
            // model transcribed: its first inference pays one-time graph
            // and buffer setup that can multiply latency past the watch
            // dictation window. Clearing the recency mark makes the
            // post-init warm-up unconditional so a real dictation never
            // pays those costs.
            lastTranscriptionAt = null
            val initDuration = Clock.System.now() - start
            logger.d { "Whisper STT model initialized in $initDuration" }
        }
    }

    private fun modelExists(): Boolean =
        sttConfig.value.modelName?.let { modelProvider.isModelDownloaded(it) } ?: false

    private fun performInit(): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                initIfNeeded()
                warmUpIfIdle()
                onInitialized.trySend(modelHandle != 0L || sttConfig.value.mode == CactusSTTMode.RemoteOnly)
            } catch (e: Throwable) {
                logger.e(e) { "Whisper STT model initialization failed: ${e.message}" }
                onInitialized.trySend(false)
            }
        }
    }

    /** True if the local model is loaded, or downloaded and ready to load, on a supported device. */
    fun isLocalAvailable(): Boolean = isWhisperSupported() && (modelHandle != 0L || modelExists())

    fun earlyInit() {
        if (initJob == null || modelHandle == 0L || lastInitedModel != sttConfig.value.modelName) {
            if (initJob?.isActive == true) {
                logger.d { "Whisper STT model initialization already in progress" }
                return
            }
            initJob = performInit()
        } else {
            scope.launch {
                warmUpIfIdle()
                onInitialized.trySend(true)
            }
        }
    }

    /** Kick off init if needed and wait (up to [initTimeout]) for it to settle. */
    private suspend fun ensureInit(initTimeout: Duration) {
        if (initJob == null || modelHandle == 0L || lastInitedModel != sttConfig.value.modelName) {
            if (initJob?.isActive != true) {
                initJob = performInit()
            }
        }
        withTimeout(initTimeout) { initJob?.join() }
    }

    private suspend fun <T> withMaybeTimeout(timeout: Duration?, block: suspend () -> T): T {
        return if (timeout != null) {
            withTimeout(timeout) { block() }
        } else {
            block()
        }
    }

    private suspend fun runLocalTranscribe(pcm: FloatArray, timeout: Duration? = null): String {
        try {
            val handle = modelHandle
            if (handle == 0L) {
                if (!isWhisperSupported()) {
                    throw TranscriptionException.TranscriptionServiceUnavailable(modelUsed = sttConfig.value.modelName)
                }
                throw TranscriptionException.TranscriptionRequiresDownload("Model not initialized")
            }
            inferenceBoost.acquire()
            val text = try {
                withMaybeTimeout(timeout) {
                    cancellableTranscribe(handle, pcm)
                }
            } finally {
                inferenceBoost.release()
            }
            analytics.logTranscriptionSuccess("whisper")
            return text
        } catch (e: TimeoutCancellationException) {
            analytics.logTranscriptionFailure("whisper", transcriptionFailureReason(e), e.message)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            analytics.logTranscriptionFailure("whisper", transcriptionFailureReason(e), e.message)
            throw e
        }
    }

    /**
     * Run the local whisper model on a pre-collected PCM buffer. Converts to the engine's float
     * format in memory (no temp file), transcribes with cancellation support, and returns the
     * recognized text (which may be blank; the caller decides how to treat that). Serialized via
     * [transcriptionMutex] to protect the native model handle.
     *
     * Throws [TranscriptionException.TranscriptionRequiresDownload] if the model isn't initialized,
     * [TranscriptionException.TranscriptionServiceUnavailable] if the engine is unsupported, and
     * [TranscriptionException.NotEnoughMemory] under memory pressure.
     */
    suspend fun transcribeLocal(
        audio: ByteArray,
        sampleRate: Int,
        timeout: Duration? = null,
        initTimeout: Duration = 10.seconds,
    ): String {
        ensureInit(initTimeout)
        if (!transcriptionMutex.tryLock()) {
            throw TranscriptionException.TranscriptionInProgress(modelUsed = sttConfig.value.modelName)
        }
        return try {
            val pcm = toEngineFloats(audio, sampleRate)
            modelMutex.withLock { runLocalTranscribe(pcm, timeout) }
        } finally {
            transcriptionMutex.unlock()
        }
    }

    /**
     * Run the local whisper model directly on a pre-collected PCM buffer, ignoring the configured
     * mode. Intended for callers (e.g. Rebble ASR fallback) that decide mode externally.
     * Returns the recognized text. Throws [TranscriptionException.TranscriptionRequiresDownload]
     * if the local model isn't initialized; throws [TranscriptionException.NoSpeechDetected]
     * if the result is empty.
     */
    suspend fun transcribeLocalForFallback(
        audio: ByteArray,
        sampleRate: Int,
        timeout: Duration = Duration.INFINITE,
    ): String {
        val text = transcribeLocal(
            audio = audio,
            sampleRate = sampleRate,
            timeout = timeout.takeIf { it.isFinite() },
            initTimeout = 20.seconds,
        )
        return text.takeIf { it.isNotBlank() }
            ?: throw TranscriptionException.NoSpeechDetected(
                "empty_result",
                modelUsed = sttConfig.value.modelName,
            )
    }
}
