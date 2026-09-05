package coredevices.util.transcription

import co.touchlab.kermit.Logger
import coredevices.util.AudioEncoding
import coredevices.util.CoreConfigFlow
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Transcription over the user's own server. One request shape serves both
 * server families in use: a multipart POST with the session audio as a
 * 16-bit mono WAV in `file`, `response_format=json`, the configured model
 * name when there is one, and the spoken language when known, answered
 * with JSON carrying `text`. whisper.cpp's server reads exactly those
 * fields on `/inference`, and OpenAI-style servers on
 * `/v1/audio/transcriptions`. The URL is used as configured, path
 * included. The bearer token, when set, goes in the Authorization header.
 *
 * Transport failures surface as [TranscriptionException.TranscriptionNetworkError]
 * (a refused certificate included), non-2xx answers, unreadable bodies and
 * oversized replies as [TranscriptionException.TranscriptionServiceError],
 * and an empty transcript as [TranscriptionException.NoSpeechDetected].
 * The app log never learns the server's address from this class: transport
 * failures are logged by exception class, and the cause handed to the
 * router is rebuilt without it.
 *
 * The client is built per `host:port` through [clientFactory] (the
 * certificate-pinning client on Android) and rebuilt when the host
 * changes; tests inject a mock engine there.
 */
class SelfHostedTranscriptionService(
    private val coreConfigFlow: CoreConfigFlow,
    private val store: SelfHostedServerStore,
    private val clientFactory: (hostPort: String, pinnedFingerprint: () -> String?) -> HttpClient = ::selfHostedHttpClient,
) : TranscriptionService {
    companion object {
        private val logger = Logger.withTag("SelfHostedTranscriptionService")

        /** The model name reported for results and failures from this path. */
        const val MODEL = "self-hosted-server"

        /** One second of 16 kHz silence: the connection test's payload. */
        private const val TEST_SAMPLE_RATE = 16_000

        /**
         * The most a reply may be before it is refused unread. A transcript
         * of the watch's recording window is a few hundred bytes; the peer
         * is whatever answers at the configured URL, so its reply is never
         * buffered whole.
         */
        internal const val MAX_REPLY_BYTES = 64 * 1024
    }

    /** What a request came back with, the body already bounded. */
    private class ServerReply(val status: HttpStatusCode, val body: String)

    override val onInitialized: Channel<Boolean> = Channel()

    private val config get() = coreConfigFlow.value.sttConfig

    /** The configured URL when it passes [validateServerUrl], else null. */
    fun configuredUrl(): String? = config.serverUrl?.trim()?.takeIf { validateServerUrl(it) == null }

    override suspend fun isAvailable(): Boolean = configuredUrl() != null

    private val clientMutex = Mutex()
    private var client: Pair<String, HttpClient>? = null

    private suspend fun clientFor(url: String): HttpClient {
        val hostPort = serverHostPort(url) ?: throw TranscriptionException.TranscriptionServiceUnavailable(MODEL)
        return clientMutex.withLock {
            client?.takeIf { it.first == hostPort }?.second ?: run {
                client?.second?.close()
                clientFactory(hostPort) { store.pinnedFingerprint(hostPort) }.also { client = hostPort to it }
            }
        }
    }

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
        val wav = DictationCaptureDump.wavBytes(buffer.readByteArray(), sampleRate)
        val reply = post(clientFor(url), url, wav, config.serverModel, store.token(), languageCode(language))
        if (!reply.status.isSuccess()) {
            throw TranscriptionException.TranscriptionServiceError("Server returned HTTP ${reply.status.value}", modelUsed = MODEL)
        }
        val text = parseServerTranscript(reply.body)
            ?: throw TranscriptionException.TranscriptionServiceError("Server reply had no text field", modelUsed = MODEL)
        emit(
            TranscriptionSessionStatus.Transcription(
                normalizeServerTranscript(text).ifBlank { throw TranscriptionException.NoSpeechDetected("empty_result", MODEL) },
                MODEL,
            ),
        )
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
        val client = clientFactory(hostPort) { store.pinnedFingerprint(hostPort) }
        return try {
            val wav = DictationCaptureDump.wavBytes(ByteArray(TEST_SAMPLE_RATE * 2), TEST_SAMPLE_RATE)
            post(client, url, wav, model, token, language = null).status.value
        } finally {
            client.close()
        }
    }

    private suspend fun post(
        client: HttpClient,
        url: String,
        wav: ByteArray,
        model: String?,
        token: String?,
        language: String?,
    ): ServerReply {
        val parts = formData {
            append(
                "file", wav,
                Headers.build {
                    append(HttpHeaders.ContentType, "audio/wav")
                    append(HttpHeaders.ContentDisposition, "filename=\"dictation.wav\"")
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
            }.execute { response -> ServerReply(response.status, boundedBody(response)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptionException) {
            throw e
        } catch (e: IOException) {
            // Connection refused, DNS, TLS refusal (an unpinned certificate
            // included), timeouts: the server is unreachable for this call.
            logger.w { "Server request failed: ${e::class.simpleName}" }
            throw TranscriptionException.TranscriptionNetworkError(ServerUnreachableException(describeTransportFailure(e)), MODEL)
        } catch (e: Exception) {
            logger.w { "Server request failed: ${e::class.simpleName}" }
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
        val channel = response.bodyAsChannel()
        val bytes = channel.readRemaining((MAX_REPLY_BYTES + 1).toLong()).readByteArray()
        if (bytes.size > MAX_REPLY_BYTES) {
            channel.cancel(null)
            throw TranscriptionException.TranscriptionServiceError("Server reply too large", modelUsed = MODEL)
        }
        return bytes.decodeToString()
    }
}
