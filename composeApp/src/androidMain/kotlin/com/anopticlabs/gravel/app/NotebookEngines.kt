package com.anopticlabs.gravel.app

import android.app.ActivityManager
import android.content.Context
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.llm.LlmEngineTimeoutException
import com.anopticlabs.gravel.models.SpeechModelInstalls
import com.anopticlabs.gravel.models.TextEngineHeldException
import com.anopticlabs.gravel.models.TextGenerator
import com.anopticlabs.gravel.models.TextModelUnavailableException
import com.anopticlabs.gravel.models.TextPromptTooLongException
import com.anopticlabs.gravel.models.isTextEngineSupported
import com.anopticlabs.gravel.notebook.alarms.NotebookPreferences
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.MAX_SERVER_TRIES
import com.anopticlabs.gravel.notebook.capture.SERVER_TRY_SPACING_MILLIS
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureMemory
import com.anopticlabs.gravel.notebook.pipeline.CaptureNotice
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.pipeline.CaptureRemedy
import com.anopticlabs.gravel.notebook.pipeline.CaptureSpeech
import com.anopticlabs.gravel.notebook.pipeline.CaptureText
import com.anopticlabs.gravel.notebook.pipeline.joinTranscript
import com.anopticlabs.gravel.notebook.pipeline.CAPTURE_SAMPLE_RATE
import com.anopticlabs.gravel.notebook.pipeline.SHORT_OF_MEMORY
import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.notebook.pipeline.ServerCheck
import com.anopticlabs.gravel.notebook.pipeline.SpeechCapture
import com.anopticlabs.gravel.notebook.pipeline.SpeechRoute
import com.anopticlabs.gravel.notebook.pipeline.SpeechSettingWatch
import com.anopticlabs.gravel.notebook.pipeline.hasSpeech
import com.anopticlabs.gravel.network.hasUplinkNetwork
import coredevices.util.models.CactusSTTMode
import coredevices.util.transcription.SelfHostedTranscriptionService
import coredevices.util.transcription.ServerUnreachableException
import coredevices.util.transcription.TransportFailure
import com.anopticlabs.gravel.notebook.pipeline.memoryShortFor
import com.anopticlabs.gravel.transcription.LongFormTimeoutException
import com.anopticlabs.gravel.transcription.ServerDictationHold
import coredevices.util.CoreConfigFlow
import coredevices.util.models.ModelManager
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.models.WhisperTier
import coredevices.util.transcription.TranscriptionException
import coredevices.util.transcription.WhisperTranscriptionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

/** Gravel: binds the Notebook's capture pipeline to the app's speech and text engines. */
val notebookEnginesModule = module {
    single {
        TextGenerator(
            provider = get(),
            selectedModelId = { get<NotebookPreferences>().textModelId },
            recommendedModel = { get<ModelManager>().getRecommendedTextModel() },
            dictation = get<WhisperTranscriptionService>().dictationPriority,
            exclusion = get(),
        )
    }
    single {
        val config = get<CoreConfigFlow>()
        NotebookSpeech(
            { config.value.sttConfig.mode }, WhisperPhoneSpeech(get()), SelfHostedServerSpeech(get()) { hasUplinkNetwork(androidContext()) }, get(), get(),
            flaky = { config.value.sttConfig.flakyNetworkMode },
        )
    } bind CaptureSpeech::class
    single { SpeechSettingWatch(speechWatched(get(), get()), request = { get<CaptureCoordinator>().requestAgain() }) }
    single<CaptureText> { NotebookText(get()) }
    single<CaptureMemory> { NotebookMemory(androidContext(), get(), get()) }
}

/**
 * What [SpeechSettingWatch] follows: the speech settings in the app's config, where the server's token and pinned
 * certificate are not kept, and the count of finished speech model downloads.
 */
internal fun speechWatched(config: CoreConfigFlow, installs: SpeechModelInstalls): List<Flow<Any?>> =
    listOf(config.flow.map { it.sttConfig }, installs.count)

/**
 * A deadline the call ran past, or audio the engine refuses, would fail again; a missing model,
 * too little memory or a dictation keeping the engine are not about the capture and hold the drain.
 */
internal fun speechFailureKind(e: Throwable): CaptureFailureKind = when (e) {
    is LongFormTimeoutException, is IllegalArgumentException -> CaptureFailureKind.FLAG
    is TranscriptionException.TranscriptionRequiresDownload,
    is TranscriptionException.NotEnoughMemory,
    is TranscriptionException.TranscriptionInProgress,
    -> CaptureFailureKind.HOLD
    else -> CaptureFailureKind.RETRY
}

/** What a flagged speech failure stores; null for one that is not flagged. */
internal fun speechFailureReason(e: Throwable): CaptureFailureReason? = when (e) {
    is LongFormTimeoutException -> CaptureFailureReason.SPEECH_TIMEOUT
    is IllegalArgumentException -> CaptureFailureReason.SPEECH_REFUSED
    else -> null
}

internal fun textFailureKind(e: Throwable): CaptureFailureKind = when (e) {
    is TextModelUnavailableException, is TextEngineHeldException -> CaptureFailureKind.HOLD
    is TextPromptTooLongException, is IllegalArgumentException, is LlmEngineTimeoutException -> CaptureFailureKind.FLAG
    else -> CaptureFailureKind.RETRY
}

internal fun textFailureReason(e: Throwable): CaptureFailureReason? = when (e) {
    is TextPromptTooLongException -> CaptureFailureReason.TEXT_TOO_LONG
    is LlmEngineTimeoutException -> CaptureFailureReason.TEXT_TIMEOUT
    is IllegalArgumentException -> CaptureFailureReason.TEXT_REFUSED
    else -> null
}

/** A missing, unloadable or unrunnable text model is explained, or fixed, on the text model page; a dictation hold is not. */
internal fun textHoldRemedy(e: Throwable): CaptureRemedy? = CaptureRemedy.TEXT_MODEL.takeIf { e is TextModelUnavailableException }

internal const val REBBLE_ONLY_HELD = "Rebble Only is selected, and the Notebook doesn't send recordings to Rebble"
internal const val PLATFORM_ONLY_HELD = "the system's speech recognition is selected, which the Notebook doesn't use"
internal const val NO_SERVER_HELD = "no transcription server is set up"
internal const val SERVER_CHECK_HELD =
    "a recording failed on your transcription server (an error, no answer within 10 minutes, or a certificate " +
        "Gravel doesn't trust); check the server, then try again"
internal const val WIFI_HELD = "waiting for Wi-Fi to send recordings to your transcription server"
internal const val UNREACHABLE_HELD = "your transcription server can't be reached; recordings wait until it can"
internal const val SERVER_RETRY_HELD = "your transcription server couldn't be reached; after 3 failed tries, they're transcribed on this phone instead"
internal const val NO_NETWORK_HELD = "waiting for a network connection to reach your transcription server"
internal const val SERVER_FALLBACK_NOTICE =
    "Your transcription server failed a recording (an error, no answer within 10 minutes, or a certificate Gravel " +
        "doesn't trust), so recordings go to this phone for now; check the server, then tap Try again."

/** The phone's long-form engine as [NotebookSpeech] uses it; [available] reads the disk. */
interface NotebookPhoneSpeech {
    fun available(): Boolean

    suspend fun transcribe(pcm16: ByteArray): String
}

/** The user's transcription server as [NotebookSpeech] uses it; [transcribe] fails as SelfHostedTranscriptionService.transcribeRecording. */
interface NotebookServerSpeech {
    fun configured(): Boolean

    fun settingsStamp(): Int

    fun allowedOnCurrentNetwork(): Boolean

    /** Whether a request has a network to go out on, whatever the Wi-Fi rule says; see hasUplinkNetwork. */
    fun networkConnected(): Boolean

    suspend fun transcribe(pcm16: ByteArray): String
}

class WhisperPhoneSpeech(private val whisper: WhisperTranscriptionService) : NotebookPhoneSpeech {
    override fun available() = whisper.isLongFormAvailable()

    override suspend fun transcribe(pcm16: ByteArray) = joinTranscript(whisper.transcribeLongForm(pcm16).map { it.text })
}

class SelfHostedServerSpeech(private val server: SelfHostedTranscriptionService, private val connected: () -> Boolean) : NotebookServerSpeech {
    override fun configured() = server.configuredUrl() != null

    override fun settingsStamp() = server.settingsStamp()

    override fun allowedOnCurrentNetwork() = server.allowedOnCurrentNetwork()

    override fun networkConnected() = connected()

    override suspend fun transcribe(pcm16: ByteArray) = server.transcribeRecording(pcm16, CAPTURE_SAMPLE_RATE)
}

/**
 * Gravel: the Notebook's speech step under the app's speech setting ([route]): the phone's long-form engine, the
 * user's transcription server, the phone first and the server for a recording the phone lacks the model or the
 * memory for, runs out of time on, or hears no speech in, or the server first and the phone for what it can't take.
 * A server step that cannot run holds the drain on the server route and makes only its capture wait on the others;
 * [serverCheck] keeps captures off a server that failed one, and [serverDictation] keeps them off while a watch
 * dictation needs the server, stopping a long one there. With [flaky] on, the routes with both send recordings made
 * on the watch to the phone alone. [clock] is the wall clock that spaces a recording's tries to reach the server.
 */
class NotebookSpeech(
    private val mode: () -> CactusSTTMode,
    private val phone: NotebookPhoneSpeech,
    private val server: NotebookServerSpeech,
    private val serverCheck: ServerCheck,
    private val serverDictation: ServerDictationHold,
    private val flaky: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) : CaptureSpeech {
    // Set by an unreachable server, so the rest of a drain pass does not wait on it again; checkReady starts a pass.
    @Volatile
    private var unreachableThisPass = false

    /** Reads the disk for the phone's model. */
    override fun route(): SpeechRoute = when (mode()) {
        CactusSTTMode.RemoteOnly -> SpeechRoute.SERVER
        CactusSTTMode.RemoteFirst -> when {
            !server.configured() -> SpeechRoute.PHONE
            !phone.available() -> SpeechRoute.SERVER
            // A server error sends its recordings to the phone until the hold ends.
            serverCheck.held(server.settingsStamp()) -> SpeechRoute.PHONE
            else -> SpeechRoute.SERVER_THEN_PHONE
        }
        CactusSTTMode.LocalFirst -> when {
            !server.configured() -> SpeechRoute.PHONE
            phone.available() -> SpeechRoute.PHONE_THEN_SERVER
            else -> SpeechRoute.SERVER
        }
        else -> SpeechRoute.PHONE
    }

    /** For the setup guide: whether the setting gives recordings a way to be transcribed, the network aside. */
    suspend fun isSetUp(): Boolean = withContext(Dispatchers.IO) {
        when (mode()) {
            CactusSTTMode.RebbleOnly, CactusSTTMode.PlatformOnly -> false
            CactusSTTMode.RemoteOnly -> server.configured()
            CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst -> server.configured() || phone.available()
            else -> phone.available()
        }
    }

    override suspend fun checkReady() = withContext(Dispatchers.IO) { checkPass() }

    /**
     * What a short recording from the watch, just queued, is known to wait for under the setting: the checks
     * [checkPass] and [transcribe] hold such a recording on when its engines succeed, without their side effects. An
     * unreachable server, or a phone that hears no speech before the server step, is found only by trying, so it is
     * not reported. Reads the disk.
     */
    fun watchRecordingWait(): RecordingWait {
        if (mode() == CactusSTTMode.RebbleOnly || mode() == CactusSTTMode.PlatformOnly) return RecordingWait.SETUP
        return when (route()) {
            SpeechRoute.PHONE -> if (phone.available()) RecordingWait.NONE else RecordingWait.SETUP
            SpeechRoute.SERVER -> when {
                !server.configured() || serverCheck.held(server.settingsStamp()) -> RecordingWait.SETUP
                !server.allowedOnCurrentNetwork() -> RecordingWait.WIFI
                else -> RecordingWait.NONE
            }
            // These routes have the phone's model: phone-then-server tries it first, and server-then-phone sends it a short
            // recording the server step passes on.
            SpeechRoute.PHONE_THEN_SERVER, SpeechRoute.SERVER_THEN_PHONE -> RecordingWait.NONE
        }
    }

    private fun checkPass() {
        unreachableThisPass = false
        checkMode()
        when (route()) {
            SpeechRoute.PHONE -> if (!phone.available()) {
                throw CaptureProcessingException(CaptureFailureKind.HOLD, "on-device speech recognition is not set up")
            }
            SpeechRoute.SERVER -> checkServer(CaptureFailureKind.HOLD)
            SpeechRoute.PHONE_THEN_SERVER, SpeechRoute.SERVER_THEN_PHONE -> Unit
        }
    }

    override fun notice(): CaptureNotice? {
        val fallingBack = mode() == CactusSTTMode.RemoteFirst && server.configured() && phone.available() &&
            serverCheck.held(server.settingsStamp())
        return if (fallingBack) CaptureNotice(SERVER_FALLBACK_NOTICE, CaptureRemedy.SERVER_CHECK) else null
    }

    // The setting can change after the pass's check.
    private fun checkMode() = when (mode()) {
        CactusSTTMode.RebbleOnly -> throw held(CaptureFailureKind.HOLD, REBBLE_ONLY_HELD, remedy = CaptureRemedy.SPEECH_SETTINGS)
        CactusSTTMode.PlatformOnly -> throw held(CaptureFailureKind.HOLD, PLATFORM_ONLY_HELD, remedy = CaptureRemedy.SPEECH_SETTINGS)
        else -> Unit
    }

    override suspend fun transcribe(pcm16: ByteArray, capture: SpeechCapture): String {
        checkMode()
        val route = route()
        if (capture.fromWatch && flaky() && (route == SpeechRoute.PHONE_THEN_SERVER || route == SpeechRoute.SERVER_THEN_PHONE)) {
            return phoneInstead(pcm16, capture)
        }
        return when (route) {
            SpeechRoute.PHONE -> {
                if (!capture.phoneFits()) throw CaptureProcessingException(CaptureFailureKind.HOLD, SHORT_OF_MEMORY)
                onPhone(pcm16)
            }
            SpeechRoute.SERVER -> onServer(pcm16, capture, CaptureFailureKind.HOLD)
            SpeechRoute.PHONE_THEN_SERVER -> phoneThenServer(pcm16, capture)
            SpeechRoute.SERVER_THEN_PHONE -> serverThenPhone(pcm16, capture)
        }
    }

    // A capture the phone ran on is marked, so a later attempt skips it; one turned away for memory is not.
    private suspend fun phoneThenServer(pcm16: ByteArray, capture: SpeechCapture): String {
        if (!capture.phoneTried && capture.phoneFits()) {
            try {
                val transcript = phone.transcribe(pcm16)
                if (hasSpeech(transcript)) return transcript
                capture.markPhoneTried()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranscriptionException.NotEnoughMemory) {
                // The engine's own memory floor: the capture goes to the server unmarked.
            } catch (e: Exception) {
                if (e !is LongFormTimeoutException && e !is TranscriptionException.TranscriptionRequiresDownload) throw phoneFailure(e)
                capture.markPhoneTried()
            }
        }
        // The phone step can run for minutes: a setting that has left the server by now gets no server step.
        if (route() == SpeechRoute.PHONE) throw settingChanged()
        return onServer(pcm16, capture, CaptureFailureKind.WAIT)
    }

    /**
     * A short capture goes to the phone when the server step fails for any reason but the server hearing no speech. A
     * long one waits off the allowed network, without a network and between tries, and goes to the phone, through the
     * processing rules again, after [MAX_SERVER_TRIES] failed tries to reach the server or a server error.
     */
    private suspend fun serverThenPhone(pcm16: ByteArray, capture: SpeechCapture): String {
        if (capture.long && capture.serverTries >= MAX_SERVER_TRIES) return phoneInstead(pcm16, capture)
        val lastTry = capture.serverTriedAtMillis
        val wait = when {
            !server.allowedOnCurrentNetwork() -> WIFI_HELD
            unreachableThisPass -> if (server.networkConnected()) SERVER_RETRY_HELD else NO_NETWORK_HELD
            // A last try in the future (the clock was set back) does not hold the next one.
            capture.long && lastTry != null && clock() - lastTry in 0 until SERVER_TRY_SPACING_MILLIS -> SERVER_RETRY_HELD
            else -> null
        }
        if (wait != null) {
            if (!capture.long) return phoneInstead(pcm16, capture)
            throw held(CaptureFailureKind.WAIT, wait, network = true)
        }
        val stamp = server.settingsStamp()
        serverCheck.take(capture.id)
        val transcript = try {
            send(pcm16, capture.long)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptionException.ServerNotAllowedOnNetwork) {
            if (!capture.long) return phoneInstead(pcm16, capture)
            throw held(CaptureFailureKind.WAIT, WIFI_HELD, e, network = true)
        } catch (e: TranscriptionException.TranscriptionServiceUnavailable) {
            if (!capture.long) return phoneInstead(pcm16, capture)
            throw held(CaptureFailureKind.WAIT, NO_SERVER_HELD, e, remedy = CaptureRemedy.SPEECH_SETTINGS)
        } catch (e: TranscriptionException.TranscriptionNetworkError) {
            if (neverConnected(e)) unreachableThisPass = true
            if (!capture.long) return phoneInstead(pcm16, capture)
            // For a long capture a transport failure is a server try, but only while the phone has a network.
            if (!server.networkConnected()) throw held(CaptureFailureKind.WAIT, NO_NETWORK_HELD, e, network = true)
            if (capture.markServerTry(clock()) && capture.serverTries + 1 >= MAX_SERVER_TRIES) {
                throw CaptureProcessingException(CaptureFailureKind.REROUTE, "your transcription server couldn't be reached", e)
            }
            throw held(CaptureFailureKind.WAIT, SERVER_RETRY_HELD, e, network = true)
        } catch (e: TranscriptionException.NoSpeechDetected) {
            throw CaptureProcessingException(CaptureFailureKind.FLAG, "no speech was heard", e, reason = CaptureFailureReason.NO_SPEECH)
        } catch (e: TranscriptionException.TranscriptionServiceError) {
            serverCheck.fellBack(stamp)
            if (!capture.long) return phoneInstead(pcm16, capture)
            throw CaptureProcessingException(CaptureFailureKind.REROUTE, "your transcription server failed this recording", e)
        } catch (e: Exception) {
            if (!capture.long) return phoneInstead(pcm16, capture)
            throw CaptureProcessingException(CaptureFailureKind.RETRY, "the recording could not be transcribed", e)
        }
        return transcript ?: throw settingChanged()
    }

    // The phone for a capture another step passed on: one memory turns away waits alone, since the rest may go elsewhere.
    private suspend fun phoneInstead(pcm16: ByteArray, capture: SpeechCapture): String {
        if (!capture.phoneFits()) throw CaptureProcessingException(CaptureFailureKind.WAIT, SHORT_OF_MEMORY)
        return try {
            phone.transcribe(pcm16)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptionException.NotEnoughMemory) {
            throw CaptureProcessingException(CaptureFailureKind.WAIT, SHORT_OF_MEMORY, e)
        } catch (e: Exception) {
            throw phoneFailure(e)
        }
    }

    private suspend fun onPhone(pcm16: ByteArray): String = try {
        phone.transcribe(pcm16)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw phoneFailure(e)
    }

    private fun phoneFailure(e: Exception) =
        CaptureProcessingException(speechFailureKind(e), "the recording could not be transcribed", e, reason = speechFailureReason(e))

    private fun checkServer(kind: CaptureFailureKind) {
        if (!server.configured()) throw held(kind, NO_SERVER_HELD, remedy = CaptureRemedy.SPEECH_SETTINGS)
        if (serverCheck.held(server.settingsStamp())) throw held(kind, SERVER_CHECK_HELD, remedy = CaptureRemedy.SERVER_CHECK)
        if (!server.allowedOnCurrentNetwork()) throw held(kind, WIFI_HELD, network = true)
        if (unreachableThisPass) throw held(kind, UNREACHABLE_HELD, network = true)
    }

    private suspend fun onServer(pcm16: ByteArray, capture: SpeechCapture, kind: CaptureFailureKind): String {
        checkServer(kind)
        val stamp = server.settingsStamp()
        val letThrough = serverCheck.take(capture.id)
        val transcript = try {
            send(pcm16, capture.long)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptionException.ServerNotAllowedOnNetwork) {
            throw held(kind, WIFI_HELD, e, network = true)
        } catch (e: TranscriptionException.TranscriptionServiceUnavailable) {
            throw held(kind, NO_SERVER_HELD, e, remedy = CaptureRemedy.SPEECH_SETTINGS)
        } catch (e: TranscriptionException.TranscriptionNetworkError) {
            // NOT_CONNECTED waits for the network; any other transport failure is a counted attempt.
            if (!neverConnected(e)) {
                throw CaptureProcessingException(CaptureFailureKind.RETRY, "the connection to the transcription server was lost", e)
            }
            unreachableThisPass = true
            throw held(kind, UNREACHABLE_HELD, e, network = true)
        } catch (e: TranscriptionException.NoSpeechDetected) {
            throw CaptureProcessingException(CaptureFailureKind.FLAG, "no speech was heard", e, reason = CaptureFailureReason.NO_SPEECH)
        } catch (e: TranscriptionException.TranscriptionServiceError) {
            if (serverCheck.failed(capture.id, stamp, letThrough)) {
                throw CaptureProcessingException(CaptureFailureKind.FLAG, "your transcription server failed this recording again", e, reason = CaptureFailureReason.SERVER_ERROR)
            }
            throw held(kind, SERVER_CHECK_HELD, e, remedy = CaptureRemedy.SERVER_CHECK)
        } catch (e: Exception) {
            throw CaptureProcessingException(CaptureFailureKind.RETRY, "the recording could not be transcribed", e)
        }
        return transcript ?: throw settingChanged()
    }

    // Null when the route is the phone alone by now, or when the route or the server settings changed while the
    // recording waited for a watch dictation.
    private suspend fun send(pcm16: ByteArray, long: Boolean): String? {
        val route = route()
        if (route == SpeechRoute.PHONE) return null
        val stamp = server.settingsStamp()
        return serverDictation.sendRecording(long, { route() == route && server.settingsStamp() == stamp }) { server.transcribe(pcm16) }
    }

    private fun settingChanged() =
        CaptureProcessingException(CaptureFailureKind.REROUTE, "the speech settings changed while this recording was being transcribed")

    // A network failure without a classified cause counts as one that never connected.
    private fun neverConnected(e: TranscriptionException.TranscriptionNetworkError) =
        (e.cause as? ServerUnreachableException)?.failure.let { it == null || it == TransportFailure.NOT_CONNECTED }

    private fun held(
        kind: CaptureFailureKind,
        reason: String,
        cause: Throwable? = null,
        remedy: CaptureRemedy? = null,
        network: Boolean = false,
    ) = CaptureProcessingException(kind, reason, cause, remedy, waitsForNetwork = network)
}

/** What a queued recording waits for before it can be transcribed: nothing known, Wi-Fi, or a setting or model. */
enum class RecordingWait { NONE, WIFI, SETUP }

class NotebookText(private val generator: TextGenerator) : CaptureText {
    override suspend fun checkReady() {
        val problem = withContext(Dispatchers.IO) {
            when {
                !isTextEngineSupported() -> "this device cannot run a text model"
                generator.installedModel() == null -> "no text model is installed"
                generator.lastLoadFailed() -> "the text model could not be loaded"
                else -> null
            }
        }
        if (problem != null) throw CaptureProcessingException(CaptureFailureKind.HOLD, problem, remedy = CaptureRemedy.TEXT_MODEL)
    }

    override suspend fun prepare() = mapped { generator.prepare() }

    override suspend fun generate(system: String, user: String, maxTokens: Int): String =
        mapped { generator.generate(system, user, maxTokens) }

    override suspend fun release() = mapped { generator.release() }

    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CaptureProcessingException(textFailureKind(e), "the text model could not answer", e, textHoldRemedy(e), textFailureReason(e))
    }
}

/**
 * What a transcription on [modelName] adds to the speech engine, with the model's weights when it
 * is not [loaded], since the call loads it first; an unknown model counts as the largest.
 */
internal fun longFormNeedBytes(modelName: String?, loaded: Boolean): Long {
    val model = modelName?.let(WhisperModelCatalog::byId)
    val tier = model?.tier ?: WhisperTier.entries.first()
    val weights = if (loaded) 0L else model?.sizeBytes ?: WhisperModelCatalog.MODELS.maxOf { it.sizeBytes }
    return tier.longFormBytes + weights
}

/** The phone's available memory against what a transcription on the dictation model adds. */
class NotebookMemory(
    private val context: Context,
    private val config: CoreConfigFlow,
    private val speech: WhisperTranscriptionService,
) : CaptureMemory {
    override fun shortForSpeech(): Boolean {
        val model = config.value.sttConfig.modelName
        val need = longFormNeedBytes(model, loaded = speech.isModelReady && speech.lastModelUsed == model)
        val info = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return memoryShortFor(need, info.availMem, info.threshold).also { short ->
            if (short) logger.w { "memory short for a transcription: available ${info.availMem shr 20} MiB, threshold ${info.threshold shr 20} MiB, need ${need shr 20} MiB" }
        }
    }

    private companion object {
        val logger = Logger.withTag("NotebookMemory")
    }
}
