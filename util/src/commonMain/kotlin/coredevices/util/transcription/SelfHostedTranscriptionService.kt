package coredevices.util.transcription

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.network.NotOnAllowedNetworkException
import com.anopticlabs.gravel.network.UnmeteredWifi
import com.anopticlabs.gravel.network.readBody
import coredevices.util.AudioEncoding
import coredevices.util.CoreConfigFlow
import coredevices.util.writeWavHeader
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Transcription over the user's own server. One request shape serves both
 * server families in use: a multipart POST with the session audio as a
 * 16-bit mono WAV in `file`, `response_format=json`, the configured model
 * name when there is one, and the spoken language when known, answered
 * with JSON carrying `text`. whisper.cpp's server reads exactly those
 * fields on `/inference`, and OpenAI-style servers on
 * `/v1/audio/transcriptions`. The URL is used as configured, path
 * included. The bearer token saved for the URL's host and port, when there
 * is one, goes in the Authorization header.
 *
 * Transport failures surface as [TranscriptionException.TranscriptionNetworkError]
 * (a refused certificate included; [transcribeRecording] differs), non-2xx
 * answers, unreadable bodies and oversized replies as
 * [TranscriptionException.TranscriptionServiceError],
 * and an empty transcript as [TranscriptionException.NoSpeechDetected].
 * The app log never learns the server's address from this class: transport
 * failures are logged by exception class, and the cause handed to the
 * router is rebuilt without it.
 *
 * The client is built per `host:port` and Wi-Fi-only choice through
 * [clientFactory] (the certificate-pinning client on Android) and rebuilt
 * when either changes, one for dictation and one for Notebook recordings;
 * tests inject a mock engine there.
 *
 * Gravel: [transcribe] checks [allowedOnCurrentNetwork] once the audio is
 * collected and, with mobile data off, sends through a client held to the
 * Wi-Fi-only rule. A request refused by the rule, or ending in an I/O error
 * once the rule no longer allows the network, ends in
 * [TranscriptionException.ServerNotAllowedOnNetwork]. The connection test
 * is not held to the rule. Notebook recordings go through
 * [transcribeRecording], on clients of their own.
 */
class SelfHostedTranscriptionService(
    private val coreConfigFlow: CoreConfigFlow,
    private val store: SelfHostedServerStore,
    private val unmeteredWifi: UnmeteredWifi,
    private val clientFactory: SelfHostedClientFactory,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val recordingLimit: Duration = RECORDING_REQUEST_LIMIT,
) : TranscriptionService {
    companion object {
        private val logger = Logger.withTag("SelfHostedTranscriptionService")

        /** The model name reported for results and failures from this path. */
        const val MODEL = "self-hosted-server"

        /** One second of 16 kHz silence: the connection test's payload. */
        private const val TEST_SAMPLE_RATE = 16_000

        /**
         * The most a reply may be before it is refused unread. A transcript
         * of the watch's recording window is a few hundred bytes, and of a
         * 5-minute Notebook recording a few kilobytes; the peer is whatever
         * answers at the configured URL, so its reply is never buffered whole.
         */
        internal const val MAX_REPLY_BYTES = 64 * 1024

        /** How long a dictation request may go without a byte from the server. */
        val DICTATION_ANSWER_WAIT = 20.seconds

        /** Gravel: the same for a Notebook recording, up to 5 minutes of audio that the server transcribes before answering. */
        val RECORDING_ANSWER_WAIT = 10.minutes

        /** Gravel: how long a Notebook recording's whole request may take, however the server spaces its bytes. */
        val RECORDING_REQUEST_LIMIT = 15.minutes

        /** Gravel: what a server's successful replies carry when it transcribes dictation apart from long recordings. */
        internal const val DICTATION_LANE_HEADER = "Gravel-Dictation-Lane"
        internal const val DICTATION_LANE_SEPARATE = "separate"
    }

    /** What a request came back with, the body already bounded. */
    private class ServerReply(val status: HttpStatusCode, val body: String)

    override val onInitialized: Channel<Boolean> = Channel()

    private val config get() = coreConfigFlow.value.sttConfig

    /** The configured URL when it passes [validateServerUrl], else null. */
    fun configuredUrl(): String? = config.serverUrl?.trim()?.takeIf { validateServerUrl(it) == null }

    override suspend fun isAvailable(): Boolean = configuredUrl() != null

    fun allowedOnCurrentNetwork(): Boolean = config.serverOnMobileData || unmeteredWifi.usable.value

    /** Gravel: whether the configured server's last successful reply announced a separate dictation lane. */
    fun hasDictationLane(): Boolean = configuredUrl()?.let(::serverHostPort)?.let(store::hasDictationLane) == true

    /** Gravel: changes when the saved URL, model, token, or the certificate pinned for the URL's host changes. */
    fun settingsStamp(): Int {
        val url = config.serverUrl?.trim()
        return listOf(url, config.serverModel?.trim(), store.storedToken(), url?.let(::serverHostPort)?.let(store::pinnedFingerprint)).hashCode()
    }

    // Dictation and Notebook recordings each keep their own client, so a client one of them
    // replaces is never closed under the other's request.
    private inner class ClientSlot(private val answerWait: Duration) {
        private val mutex = Mutex()
        private var client: Pair<Pair<String, Boolean>, HttpClient>? = null

        suspend fun clientFor(url: String, wifiOnly: Boolean): HttpClient {
            val hostPort = serverHostPort(url) ?: throw TranscriptionException.TranscriptionServiceUnavailable(MODEL)
            val key = hostPort to wifiOnly
            return mutex.withLock {
                client?.takeIf { it.first == key }?.second ?: run {
                    client?.second?.close()
                    clientFactory.create(hostPort, { store.pinnedFingerprint(hostPort) }, wifiOnly, answerWait).also { client = key to it }
                }
            }
        }
    }

    private val dictationClients = ClientSlot(DICTATION_ANSWER_WAIT)
    private val recordingClients = ClientSlot(RECORDING_ANSWER_WAIT)

    /** ISO 639-1 code for the request, null for in-server detection. */
    private fun languageCode(language: STTLanguage): String? = when (language) {
        is STTLanguage.Specific -> normalizeSpokenLanguage(language.languageCodes.firstOrNull())
        STTLanguage.Automatic -> null
    }

    override suspend fun transcribe(
        audioStreamFrames: Flow<ByteArray>?,
        sampleRate: Int,
        language: STTLanguage,
        conversationContext: STTConversationContext?,
        dictionaryContext: List<String>?,
        contentContext: String?,
        encoding: AudioEncoding,
        initialTimeout: Duration?,
    ): Flow<TranscriptionSessionStatus> = flow {
        emit(TranscriptionSessionStatus.Open)
        val url = configuredUrl() ?: throw TranscriptionException.TranscriptionServiceUnavailable(MODEL)
        val frames = audioStreamFrames
            ?: throw TranscriptionException.TranscriptionServiceError("The server path needs an audio stream", modelUsed = MODEL)
        val buffer = Buffer()
        frames.collect { buffer.write(it) }
        if (buffer.size == 0L) throw TranscriptionException.NoSpeechDetected("No audio data received", MODEL)
        val wav = wavOf(buffer.readByteArray(), sampleRate)
        if (!allowedOnCurrentNetwork()) throw TranscriptionException.ServerNotAllowedOnNetwork(MODEL)
        val wifiOnly = !config.serverOnMobileData
        val reply = post(dictationClients.clientFor(url, wifiOnly), url, wav, config.serverModel, store.tokenFor(serverHostPort(url)), languageCode(language), wifiOnly)
        emit(TranscriptionSessionStatus.Transcription(transcriptOf(reply), MODEL))
    }

    /**
     * Gravel: one Notebook recording, [pcm16] at [sampleRate], sent as a WAV built in memory with
     * [RECORDING_ANSWER_WAIT] for the answer and [recordingLimit] for the whole request. Fails as
     * [transcribe] does, except that a refused certificate, a timeout once the answer wait has passed
     * since the call started, and a request still open at [recordingLimit] are the server's errors
     * ([TranscriptionException.TranscriptionServiceError]); a network error's
     * [ServerUnreachableException] cause tells a connection never made from one lost.
     */
    suspend fun transcribeRecording(pcm16: ByteArray, sampleRate: Int): String {
        val url = configuredUrl() ?: throw TranscriptionException.TranscriptionServiceUnavailable(MODEL)
        if (pcm16.isEmpty()) throw TranscriptionException.NoSpeechDetected("No audio data received", MODEL)
        val wav = wavOf(pcm16, sampleRate)
        if (!allowedOnCurrentNetwork()) throw TranscriptionException.ServerNotAllowedOnNetwork(MODEL)
        val wifiOnly = !config.serverOnMobileData
        val language = normalizeSpokenLanguage(config.spokenLanguage)
        val started = clock.markNow()
        val reply = try {
            // Null only when this limit's own timer ran out (kotlinx.coroutines 1.11.0, Timeout.kt, withTimeoutOrNull).
            withTimeoutOrNull(recordingLimit) {
                post(recordingClients.clientFor(url, wifiOnly), url, wav, config.serverModel, store.tokenFor(serverHostPort(url)), language, wifiOnly, "recording.wav")
            } ?: run {
                // A caller's cancel that came as the limit ran out stays a cancel.
                currentCoroutineContext().ensureActive()
                throw TranscriptionException.TranscriptionServiceError("Server request failed: not finished within $recordingLimit", modelUsed = MODEL)
            }
        } catch (e: TranscriptionException.TranscriptionNetworkError) {
            val failure = (e.cause as? ServerUnreachableException)?.failure
            val noAnswer = failure == TransportFailure.TIMED_OUT && started.elapsedNow() >= RECORDING_ANSWER_WAIT
            if (failure != TransportFailure.CERTIFICATE && !noAnswer) throw e
            throw TranscriptionException.TranscriptionServiceError("Server request failed: ${e.cause?.message}", modelUsed = MODEL)
        }
        return transcriptOf(reply)
    }

    private fun transcriptOf(reply: ServerReply): String {
        if (!reply.status.isSuccess()) {
            throw TranscriptionException.TranscriptionServiceError("Server returned HTTP ${reply.status.value}", modelUsed = MODEL)
        }
        val text = parseServerTranscript(reply.body)
            ?: throw TranscriptionException.TranscriptionServiceError("Server reply had no text field", modelUsed = MODEL)
        return normalizeServerTranscript(text).ifBlank { throw TranscriptionException.NoSpeechDetected("empty_result", MODEL) }
    }

    /**
     * The settings dialog's connection test: one second of silence to
     * [url] with the given credentials (the dialog's unsaved values), so a
     * wrong token or path is found before saving. Returns the HTTP status;
     * throws the same mapped exceptions a dictation would. Runs on a
     * client of its own, closed here, so the cached one is never replaced
     * or closed under a dictation's request.
     */
    suspend fun testConnection(url: String, model: String?, token: String?): Int {
        val hostPort = serverHostPort(url) ?: throw TranscriptionException.TranscriptionServiceUnavailable(MODEL)
        val client = clientFactory.create(hostPort, { store.pinnedFingerprint(hostPort) }, wifiOnly = false, DICTATION_ANSWER_WAIT)
        return try {
            val wav = wavOf(ByteArray(TEST_SAMPLE_RATE * 2), TEST_SAMPLE_RATE)
            post(client, url, wav, model, token, language = null, wifiOnly = false).status.value
        } finally {
            client.close()
        }
    }

    /** The request payload: a mono 16-bit PCM WAV around [pcm], the one shape both server families accept. */
    private fun wavOf(pcm: ByteArray, sampleRate: Int): ByteArray =
        Buffer().apply {
            writeWavHeader(sampleRate, pcm.size)
            write(pcm)
        }.readByteArray()

    private suspend fun post(
        client: HttpClient,
        url: String,
        wav: ByteArray,
        model: String?,
        token: String?,
        language: String?,
        wifiOnly: Boolean,
        fileName: String = "dictation.wav",
    ): ServerReply {
        val parts = formData {
            append(
                "file", wav,
                Headers.build {
                    append(HttpHeaders.ContentType, "audio/wav")
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                },
            )
            append("response_format", "json")
            model?.trim()?.takeIf { it.isNotEmpty() }?.let { append("model", it) }
            language?.let { append("language", it) }
        }
        return try {
            // A prepared request streams the reply, so the body is read
            // through the bound below and never buffered whole by the client.
            client.prepareRequest(url) {
                method = HttpMethod.Post
                setBody(MultiPartFormDataContent(parts))
                token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
            }.execute { response ->
                ServerReply(response.status, boundedBody(response)).also {
                    if (response.status.isSuccess()) {
                        serverHostPort(url)?.let { store.rememberDictationLane(it, response.headers[DICTATION_LANE_HEADER]?.trim() == DICTATION_LANE_SEPARATE) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptionException) {
            throw e
        } catch (e: Exception) {
            val refused = generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is NotOnAllowedNetworkException }
            if (refused || (wifiOnly && e is IOException && !allowedOnCurrentNetwork())) {
                throw TranscriptionException.ServerNotAllowedOnNetwork(MODEL)
            }
            logger.w { "Server request failed: ${e::class.simpleName}" }
            // Connection refused, DNS, TLS refusal (an unpinned certificate
            // included), timeouts: the server is unreachable for this call.
            if (e is IOException) {
                throw TranscriptionException.TranscriptionNetworkError(ServerUnreachableException(describeTransportFailure(e), transportFailure(e)), MODEL)
            }
            throw TranscriptionException.TranscriptionServiceError("Server request failed: ${e::class.simpleName}", modelUsed = MODEL)
        }
    }

    /**
     * The reply body under [MAX_REPLY_BYTES]: a declared length past it is
     * refused unread, and a body that runs past it is refused at that
     * point with the rest left on the wire.
     */
    private suspend fun boundedBody(response: HttpResponse): String {
        val declared = response.contentLength()
        if (declared != null && declared > MAX_REPLY_BYTES) {
            throw TranscriptionException.TranscriptionServiceError("Server reply too large ($declared bytes)", modelUsed = MODEL)
        }
        val bytes = response.readBody { it.readRemaining((MAX_REPLY_BYTES + 1).toLong()).readByteArray() }
        if (bytes.size > MAX_REPLY_BYTES) {
            throw TranscriptionException.TranscriptionServiceError("Server reply too large", modelUsed = MODEL)
        }
        return bytes.decodeToString()
    }
}
