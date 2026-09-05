package coredevices.util.transcription

import co.touchlab.kermit.Logger
import coredevices.analytics.CoreAnalytics
import coredevices.resampler.Resampler
import coredevices.util.CoreConfigFlow
import coredevices.util.isDebugBuild
import coredevices.util.models.CactusSTTMode
import coredevices.util.models.WhisperModelCatalog
import coredevices.whisper.EnginePlacement
import coredevices.whisper.TranscribeStats
import coredevices.whisper.isWhisperSupported
import coredevices.whisper.pcm16ToShorts
import coredevices.whisper.shortsToFloats
import coredevices.whisper.whisperCancel
import coredevices.whisper.whisperFree
import coredevices.whisper.whisperInit
import coredevices.whisper.whisperTranscribe
import coredevices.whisper.whisperVadFree
import coredevices.whisper.whisperVadInit
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
import kotlinx.coroutines.delay
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
 * preference normalized through [normalizeSpokenLanguage], with null meaning
 * in-engine detection. Static so this mapping stays under host tests.
 */
internal fun whisperLanguageFor(modelMultilingual: Boolean?, spokenLanguage: String?): String? =
    when (modelMultilingual) {
        false -> "en"
        else -> normalizeSpokenLanguage(spokenLanguage)
    }

/**
 * Maps the legacy ISO 639-1 codes java.util.Locale still reports (the
 * pre-1989 codes for Hebrew, Indonesian and Yiddish, kept by Java for
 * serialization compatibility) to the modern codes whisper's language map
 * uses. Without this, a Hebrew-locale user picking their own language gets
 * "iw" all the way to the engine, which whisper does not know; explicitly
 * requested unknown languages corrupt the decoder prompt instead of
 * failing (the native shim's fallback is the second layer against that).
 * Codes outside the legacy trio pass through untouched.
 */
internal fun normalizeSpokenLanguage(code: String?): String? = when (code) {
    "iw" -> "he"
    "in" -> "id"
    "ji" -> "yi"
    else -> code
}

/**
 * Minimum run length [collapseRepeatedSentences] treats as decoder
 * pathology. Two consecutive identical sentences are plausibly real speech
 * (a measured watch capture genuinely contains its phrase twice, and every
 * engine configuration agrees on the double); three or more identical
 * sentences inside a dictation clip bounded by the firmware's 15 s window
 * have only been observed from decoder repetition loops.
 */
internal const val REPEAT_COLLAPSE_THRESHOLD = 3

private val sentenceUnitRegex = "[^.?!]+[.?!]*\\s*".toRegex()

/**
 * Collapses decoder repetition loops in engine output to a single instance.
 *
 * The engine runs without whisper's temperature-fallback ladder (bounded
 * dictation latency; see the native transcribe shim), so a repetition loop
 * that the ladder used to retry away now surfaces as the same sentence
 * emitted until the per-segment token cap: real captures produced thirteen
 * consecutive copies. Runs of at least [REPEAT_COLLAPSE_THRESHOLD]
 * identical sentences (case- and whitespace-insensitive) are collapsed to
 * their first instance; shorter runs pass through untouched. Unpunctuated
 * output repeating one multi-word phrase back-to-back is collapsed by the
 * same rule with words as the unit; single-word repetition is left alone
 * because it is ordinary emphatic speech. Static and pure so the behavior
 * stays under host tests.
 */
internal fun collapseRepeatedSentences(text: String): String {
    if (text.isBlank()) return text
    val units = sentenceUnitRegex.findAll(text).map { it.value }.toList()
    if (units.isEmpty()) return text

    // Sentence-level pass: collapse runs of >= threshold equal sentences.
    val kept = ArrayList<String>(units.size)
    var i = 0
    while (i < units.size) {
        var j = i
        val key = units[i].trim().lowercase()
        while (j < units.size && units[j].trim().lowercase() == key) j++
        kept.add(units[i])
        if (j - i in 2 until REPEAT_COLLAPSE_THRESHOLD) {
            // Keep short runs verbatim (they may be real speech).
            for (k in i + 1 until j) kept.add(units[k])
        } else if (j - i >= REPEAT_COLLAPSE_THRESHOLD && j < units.size) {
            // The per-segment token cap can cut the loop mid-sentence,
            // leaving a truncated copy right after the run; a strict prefix
            // of the collapsed sentence there is loop residue, not speech.
            val tail = units[j].trim().lowercase()
            if (tail.isNotEmpty() && tail.length < key.length && key.startsWith(tail)) j++
        }
        i = j
    }
    val sentenceCollapsed = kept.joinToString("").trim()

    // Word-level pass for unpunctuated loops: if the whole remaining text is
    // one phrase repeated back-to-back at least threshold times (a trailing
    // partial copy counts as part of the loop), keep one instance. Only
    // phrases with at least two distinct words qualify: a single word
    // repeated is ordinary speech ("no no no" in a notification reply), and
    // rewriting it would silently drop words the user actually said,
    // whereas the observed decoder loops repeat phrases. The distinct-word
    // check (not just period >= 2) matters because a single-word run also
    // matches every longer period ("no" x6 is "no no" x3). A single-word
    // loop surfaces as visibly garbled output instead of a silent rewrite.
    val words = sentenceCollapsed.split(Regex("\\s+"))
    for (period in 2..words.size / REPEAT_COLLAPSE_THRESHOLD) {
        val phrase = words.subList(0, period).map { it.lowercase() }
        if (phrase.distinct().size < 2) continue
        val repeats = words.size / period
        if (repeats >= REPEAT_COLLAPSE_THRESHOLD &&
            (0 until words.size).all { words[it].lowercase() == phrase[it % period] }
        ) {
            return words.subList(0, period).joinToString(" ")
        }
    }
    return sentenceCollapsed
}

/**
 * Awaits [worker], a blocking native engine call running on another thread,
 * such that cancelling the waiting coroutine genuinely interrupts the
 * engine. A thread blocked inside a native call cannot observe coroutine
 * cancellation, so the waiter and the blocked thread must be different
 * threads: this waiter is the cancellable side, and on cancellation it
 * requests the engine's per-call abort via [cancel] (checked by the engine
 * between its encoder and decoder passes), then holds the already-cancelled
 * caller until the worker has actually unwound. The hold preserves the
 * one-native-call-at-a-time invariant the caller's mutex provides: resuming
 * while the engine is still inside the context would let the next
 * transcription run against it concurrently. The hold is bounded by
 * [unwindBound] so an engine that never honours the abort cannot hold the
 * mutex forever (the exact stuck state cancellation exists to prevent); a
 * blown bound runs [onWedged] before rethrowing so the owner can quarantine
 * the still-occupied native context. Because [cancel] targets one call by
 * id, a later call clearing or arming its own abort cannot revoke this
 * abandoned call's pending abort.
 *
 * [worker] must not be a child of the waiter (cancelling the waiter must
 * leave the work free to finish unwinding) and must wrap the engine call in
 * [runCatching] (an engine failure must not tear down the worker's scope).
 * Static so the cancel/unwind/wedge contract stays under host tests.
 */
internal suspend fun <T> awaitEngineWork(
    worker: Deferred<Result<T>>,
    cancel: () -> Unit,
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
        cancel()
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
 * The engine entry points the service drives, injectable so the
 * init/free/transcribe handle lifecycle stays under host tests with a
 * scripted fake (the real functions are hard-wired to the native library
 * and cannot run on a host JVM). Production wiring is [RealWhisperEngine];
 * only the service's own tests pass anything else.
 */
internal interface WhisperEngine {
    fun supported(): Boolean
    fun init(modelPath: String): Long
    fun transcribe(
        handle: Long,
        pcm: FloatArray,
        threads: Int,
        language: String?,
        callId: Long,
        placement: EnginePlacement,
        vadHandle: Long,
        stats: TranscribeStats?,
    ): String
    fun cancel(callId: Long)
    fun free(handle: Long)
    fun vadInit(modelPath: String): Long
    fun vadFree(handle: Long)
}

/** The :whisper top-level binding functions, bound 1:1. */
internal object RealWhisperEngine : WhisperEngine {
    override fun supported(): Boolean = isWhisperSupported()
    override fun init(modelPath: String): Long = whisperInit(modelPath)
    override fun transcribe(
        handle: Long,
        pcm: FloatArray,
        threads: Int,
        language: String?,
        callId: Long,
        placement: EnginePlacement,
        vadHandle: Long,
        stats: TranscribeStats?,
    ): String = whisperTranscribe(handle, pcm, threads, language, callId, placement, vadHandle, stats)
    override fun cancel(callId: Long) = whisperCancel(callId)
    override fun free(handle: Long) = whisperFree(handle)
    override fun vadInit(modelPath: String): Long = whisperVadInit(modelPath)
    override fun vadFree(handle: Long) = whisperVadFree(handle)
}

/**
 * Local speech-to-text over the whisper.cpp engine (:whisper bindings).
 * Successor to the Cactus-era service with the same shape: the mode/model
 * config flow drives (re)initialization, a rendezvous channel reports init
 * settling, and two mutexes serialize the native handle. The two-mutex
 * split ([transcriptionMutex] taken with tryLock as the busy gate,
 * [modelMutex] guarding every native call, including init and free) is
 * load-bearing: the warm-up path yields to an in-flight transcription
 * instead of queueing behind it, which is what keeps a warm-up racing a
 * real dictation from wedging either.
 *
 * Engine input is 16 kHz mono float PCM handed over in memory; audio at
 * any other rate is resampled first. Cancellation is cooperative through
 * the engine's abort callback, requested per call id so an abandoned
 * wedged call keeps its own abort while a fresh call runs unaffected.
 */
class WhisperTranscriptionService internal constructor(
    private val coreConfigFlow: CoreConfigFlow,
    private val modelProvider: CactusModelPathProvider,
    private val analytics: CoreAnalytics,
    private val inferenceBoost: InferenceBoost,
    private val engine: WhisperEngine,
    // Fed after every successful dictation; null in tests that do not care.
    private val speedTracker: DictationSpeedTracker? = null,
    // The build check behind the debug hooks and the capture clear, injected so host tests can drive both.
    private val debugBuild: () -> Boolean = ::isDebugBuild,
    private val clearCaptures: () -> Unit = { DictationCaptureDump.clear() },
) {
    /** Production entry point: the real native engine. */
    constructor(
        coreConfigFlow: CoreConfigFlow,
        modelProvider: CactusModelPathProvider,
        analytics: CoreAnalytics,
        inferenceBoost: InferenceBoost = NoOpInferenceBoost(),
        speedTracker: DictationSpeedTracker? = null,
    ) : this(coreConfigFlow, modelProvider, analytics, inferenceBoost, RealWhisperEngine, speedTracker)

    companion object {
        private val logger = Logger.withTag("WhisperTranscriptionService")

        /** The engine's fixed input rate; other rates are resampled to it. */
        internal const val ENGINE_SAMPLE_RATE = 16_000

        /**
         * How long a cancelled transcription may take to unwind out of the
         * native call after its abort is requested. The engine checks the
         * request only between encoder and decoder passes, and one encoder
         * pass is seconds-scale for the bigger catalog models on
         * phone-class CPUs, so the bound must comfortably exceed a single
         * pass. Blowing it means the engine is wedged, and the native
         * context gets abandoned rather than risking a concurrent next
         * call against it.
         */
        private val ENGINE_UNWIND_BOUND = 10.seconds
    }

    private val transcriptionMutex = Mutex()

    // Volatile: these are written by init jobs on the IO dispatcher and read
    // by the config observer, earlyInit/ensureInit gates, and the public
    // ready-state accessors on other threads. The gates are advisory (the
    // authoritative re-check happens under modelMutex in initIfNeeded), but
    // without a happens-before edge they could act on arbitrarily stale
    // values.
    @kotlin.concurrent.Volatile
    private var modelHandle: Long = 0L

    @kotlin.concurrent.Volatile
    private var initJob: Job? = null

    @kotlin.concurrent.Volatile
    private var lastInitedModel: String? = null

    // The voice activity detector's native handle. Independent of the
    // speech model (one detector serves every model), initialized under
    // modelMutex the first time a model initializes and the detector file
    // is installed, kept for the process lifetime, never freed while an
    // engine call could be inside it. Zero means "decode untrimmed".
    @kotlin.concurrent.Volatile
    private var vadHandle: Long = 0L

    /** True when the voice activity detector is loaded and trims dictation audio. */
    val isVadReady get() = vadHandle != 0L
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

    // Null until the first config emission; see captureDumpShouldClear.
    private var captureDumpWasOn: Boolean? = null

    init {
        sttConfig.onEach {
            logger.i { "STT config changed: $it" }
            if (it.modelName != lastInitedModel) {
                initJob = performInit()
            }
            val captureDumpOn = debugCaptureDumpApplies(it.debugCaptureDump, debugBuild())
            if (captureDumpShouldClear(captureDumpWasOn, captureDumpOn)) {
                withContext(Dispatchers.IO) { clearCaptures() }
            }
            captureDumpWasOn = captureDumpOn
        }.launchIn(scope)
    }

    @kotlin.concurrent.Volatile
    private var lastTranscriptionAt: TimeMark? = null
    private val modelMutex = Mutex()

    // 1 second of 16 kHz silence for the warm-up pass; the engine's first
    // inference after load pays one-time setup costs the warm-up absorbs.
    private val silentPcm = FloatArray(ENGINE_SAMPLE_RATE)

    /**
     * Runs [block], a blocking native engine call, with working
     * cancellation: [block] receives a per-call id, the call is dispatched
     * to a worker on the service [scope], and this coroutine waits at a
     * cancellable suspension point; a cancelled wait requests that specific
     * call's abort, which whisper polls between inference steps (see
     * [awaitEngineWork] for the unwind and wedge contract). The split
     * matters because a coroutine whose own thread is inside the native call
     * has no suspension point left: nothing observing only that coroutine
     * can act until the call it was supposed to interrupt has returned.
     *
     * Cancellation is per call id, not a shared flag, so an abandoned
     * wedged call keeps its own pending abort even after a fresh call
     * starts: there is nothing to clear on entry, and a later call can
     * never revoke this one's abort. A cancel that lands before the worker
     * reaches the engine is still honoured, because the id is armed before
     * the worker is cancelled and the engine reads it at its first
     * abort-callback poll. Call ids come from [nextCallId], generated under
     * [modelMutex] (every call site holds it), so they are unique across
     * any calls that can be in flight at once.
     */
    private suspend fun <T> withWhisperCancelOnCancel(block: (Long) -> T): T {
        val callId = nextCallId()
        val worker = scope.async(Dispatchers.IO) { runCatching { block(callId) } }
        return awaitEngineWork(
            worker = worker,
            cancel = {
                logger.d { "Requesting whisper engine abort for call $callId (caller cancelled)" }
                engine.cancel(callId)
            },
            unwindBound = ENGINE_UNWIND_BOUND,
            onWedged = ::abandonWedgedContext,
        )
    }

    // Monotonic call-id source. Only ever incremented from
    // withWhisperCancelOnCancel, which every caller enters holding
    // [modelMutex], so the increment is already serialized and the ids are
    // unique even against an abandoned wedged call still running.
    private var callIdCounter: Long = 0L
    private fun nextCallId(): Long = ++callIdCounter

    /**
     * Containment for an engine that ignored its abort past
     * [ENGINE_UNWIND_BOUND]: a thread may still be inside the native
     * context, so freeing it would be a use-after-free. The context is
     * leaked deliberately and the handle zeroed (still under [modelMutex])
     * so the next transcription re-initializes a fresh context instead of
     * racing the stuck call inside the old one. The wedged call keeps its
     * own per-id abort armed, so the fresh call does not disturb it.
     */
    private fun abandonWedgedContext() {
        logger.e {
            "Whisper engine ignored cancellation for $ENGINE_UNWIND_BOUND; " +
                "abandoning the native context and forcing re-init"
        }
        modelHandle = 0L
        lastInitedModel = null
    }

    /**
     * Run the engine with the memory guard and cancellation support.
     * [threads] is decided by the caller so the diagnostics line reports
     * the exact count the engine ran with.
     */
    private suspend fun cancellableTranscribe(
        handle: Long,
        pcm: FloatArray,
        threads: Int,
        stats: TranscribeStats? = null,
    ): String {
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
        return withWhisperCancelOnCancel { callId ->
            engine.transcribe(handle, pcm, threads, language, callId, EnginePlacement.DEFAULT, vadHandle, stats).trim()
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
            try {
                withTimeout(2.seconds) {
                    withWhisperCancelOnCancel { callId ->
                        // Fixed "en": silence has no language to detect,
                        // and detection would only add an extra pass.
                        // Warm-up bypasses the detector: silence would be
                        // trimmed to nothing and the engine's one-time
                        // setup would stay unpaid.
                        engine.transcribe(
                            handle, silentPcm, dictationThreadCount(sttConfig.value), "en", callId,
                            EnginePlacement.DEFAULT, vadHandle = 0L, stats = null,
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.w { "Whisper STT warmup timed out" }
            }
        } finally {
            modelMutex.unlock()
        }
    }

    private suspend fun initIfNeeded() {
        val config = sttConfig.value
        if (config.mode == CactusSTTMode.RemoteOnly) return
        if (!engine.supported()) return
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
        // The free/init pair holds [modelMutex] for the same reason every
        // other native call does: a model switch must not free the context
        // while a transcription or warm-up is inside it on another thread
        // (native use-after-free), and it is what the :whisper threading
        // contract promises the shim. Holding the lock across the whole
        // block also serializes concurrent init jobs (the config observer
        // launches one per relevant emission with no coordination): the
        // second job re-reads the handle state below and becomes a no-op
        // instead of double-freeing or leaking a second context.
        modelMutex.withLock {
            if (modelName != lastInitedModel && modelHandle != 0L) {
                engine.free(modelHandle)
                modelHandle = 0L
            }
            if (modelHandle == 0L) {
                // getModelPath re-hashes the installed file once per process
                // before first use. allowReinstall=false keeps init out of
                // the download flow: a corrupt model is quarantined and this
                // throws (surfacing as not-installed to the visible download
                // UI) rather than pulling a silent multi-hundred-MB metered
                // re-download from an engine-init path.
                val modelPath = modelProvider.getModelPath(modelName, allowReinstall = false)
                modelHandle = engine.init(modelPath)
                lastInitedModel = modelName
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
            loadDetectorIfMissing()
        }
    }

    /**
     * Loads the detector when none is held and the provider has the file.
     * Called under [modelMutex]. An absent detector (an install predating
     * it, a fetch still running, or a failed one) means untrimmed decoding,
     * never a failed init: the provider answers null without downloading,
     * and a load failure is logged and left for the next attempt. The
     * file check comes first because the provider's resolve takes the
     * detector's own mutex, which its download job holds for the whole
     * transfer, and this runs under [modelMutex] on every dictation.
     */
    private suspend fun loadDetectorIfMissing() {
        if (vadHandle != 0L) return
        if (!modelProvider.isVadModelInstalled()) return
        val vadPath = try {
            modelProvider.getVadModelPath()
        } catch (e: Exception) {
            logger.w(e) { "Voice activity detector unavailable; decoding untrimmed" }
            null
        }
        if (vadPath != null) {
            try {
                vadHandle = engine.vadInit(vadPath)
                logger.d { "Voice activity detector initialized" }
            } catch (e: Exception) {
                logger.w(e) { "Voice activity detector failed to load; decoding untrimmed" }
            }
        }
    }

    /** Seconds of audio the engine decoded, null when the call never reported. */
    private fun TranscribeStats.speechSeconds(): Double? =
        decodedSamples.takeIf { it >= 0 }?.let { it / ENGINE_SAMPLE_RATE.toDouble() }

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
    fun isLocalAvailable(): Boolean = engine.supported() && (modelHandle != 0L || modelExists())

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
        // The detector's install can finish after the model came up (both
        // start on a fresh install), so a dictation re-checks for it rather
        // than waiting for the next model change to run the init path.
        if (modelHandle != 0L && vadHandle == 0L) {
            modelMutex.withLock { loadDetectorIfMissing() }
        }
    }

    private suspend fun <T> withMaybeTimeout(timeout: Duration?, block: suspend () -> T): T {
        return if (timeout != null) {
            withTimeout(timeout) { block() }
        } else {
            block()
        }
    }

    private suspend fun runLocalTranscribe(pcm: FloatArray, timeout: Duration? = null): String {
        // Every engine call leaves one diagnostics line (see
        // DictationDiagnostics.kt): the scheduling facts are read before the
        // call because a background process can be promoted or demoted while
        // the decode runs, and the line is written from the finally so every
        // exit path, including cancellation by the caller's deadline, reports
        // how long the engine was actually given.
        val threads = dictationThreadCount(sttConfig.value)
        val snapshot = engineRuntimeSnapshot()
        val started = TimeSource.Monotonic.markNow()
        var outcome = "error"
        // What the engine was actually given (after the detector's cut),
        // for the speed record and the diagnostics line.
        val stats = TranscribeStats()
        // The handle and the model it holds are read together: a switch made
        // during this decode changes the config at once but re-initializes
        // only after the mutex is released, so the record and the line must
        // name the model that ran.
        val handle = modelHandle
        val model = lastInitedModel
        try {
            if (handle == 0L) {
                if (!engine.supported()) {
                    throw TranscriptionException.TranscriptionServiceUnavailable(modelUsed = sttConfig.value.modelName)
                }
                throw TranscriptionException.TranscriptionRequiresDownload("Model not initialized")
            }
            inferenceBoost.acquire()
            val text = try {
                withMaybeTimeout(timeout) {
                    val decoded = cancellableTranscribe(handle, pcm, threads, stats)
                    // Debug-only hold after the decode: cancellable, so a
                    // caller's deadline still fires, and the result still
                    // completes afterwards, as a real overrun's would.
                    val hold = debugDecodeDelay(sttConfig.value.debugSlowDecode, debugBuild())
                    if (hold > Duration.ZERO) {
                        logger.w { "Debug slow-decode hook holding the result for $hold" }
                        delay(hold)
                    }
                    decoded
                }
            } finally {
                inferenceBoost.release()
            }
            outcome = if (text.isBlank()) "no_speech" else "ok"
            analytics.logTranscriptionSuccess("whisper")
            // The speed record behind the model nudge: time to a result per
            // second of engine input, on successful dictations only. The
            // debug hold counts, so the slow-decode hook exercises the nudge
            // the way a slow phone would.
            val speechSeconds = stats.speechSeconds()
            if (text.isNotBlank() && speechSeconds != null) {
                val resultMs = started.elapsedNow().inWholeMilliseconds
                model?.let { speedTracker?.recordDecode(it, speechSeconds, resultMs) }
            }
            return collapseRepeatedSentences(text)
        } catch (e: TimeoutCancellationException) {
            outcome = "deadline"
            analytics.logTranscriptionFailure("whisper", transcriptionFailureReason(e), e.message)
            throw e
        } catch (e: CancellationException) {
            outcome = "cancelled"
            throw e
        } catch (e: Exception) {
            outcome = "error:${e::class.simpleName}"
            analytics.logTranscriptionFailure("whisper", transcriptionFailureReason(e), e.message)
            throw e
        } finally {
            logger.i {
                formatEngineDiagnostics(
                    model = model,
                    threads = threads,
                    snapshot = snapshot,
                    audioSeconds = pcm.size / ENGINE_SAMPLE_RATE.toDouble(),
                    speechSeconds = stats.speechSeconds(),
                    decodeMillis = started.elapsedNow().inWholeMilliseconds,
                    outcome = outcome,
                    vad = vadHandle != 0L,
                )
            }
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
            // Debug builds can archive the exact bytes the engine is about
            // to see; the write is fenced inside the dumper and cannot fail
            // the dictation.
            if (debugCaptureDumpApplies(sttConfig.value.debugCaptureDump, debugBuild())) {
                withContext(Dispatchers.IO) { DictationCaptureDump.write(audio, sampleRate) }
            }
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
