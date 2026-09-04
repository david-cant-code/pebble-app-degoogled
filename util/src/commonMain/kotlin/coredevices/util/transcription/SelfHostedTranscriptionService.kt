package coredevices.util.transcription

import co.touchlab.kermit.Logger
import coredevices.util.AudioEncoding
import coredevices.util.CoreConfigFlow
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
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
 * (a refused certificate included), non-2xx answers and unreadable bodies as
 * [TranscriptionException.TranscriptionServiceError], and an empty
 * transcript as [TranscriptionException.NoSpeechDetected].
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
    }

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
        val response = post(url, wav, config.serverModel, store.token(), languageCode(language))
        if (!response.status.isSuccess()) {
            throw TranscriptionException.TranscriptionServiceError("Server returned HTTP ${response.status.value}", modelUsed = MODEL)
        }
        val text = parseServerTranscript(response.bodyAsText())
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
     * throws the same mapped exceptions a dictation would.
     */
    suspend fun testConnection(url: String, model: String?, token: String?): Int {
        val wav = DictationCaptureDump.wavBytes(ByteArray(TEST_SAMPLE_RATE * 2), TEST_SAMPLE_RATE)
        return post(url, wav, model, token, language = null).status.value
    }

    private suspend fun post(url: String, wav: ByteArray, model: String?, token: String?, language: String?): HttpResponse {
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
            clientFor(url).submitFormWithBinaryData(url, parts) {
                token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Connection refused, DNS, TLS refusal (an unpinned certificate
            // included), timeouts: the server is unreachable for this call.
            logger.w { "Server request failed: ${e::class.simpleName}: ${e.message}" }
            throw TranscriptionException.TranscriptionNetworkError(e, MODEL)
        } catch (e: Exception) {
            throw TranscriptionException.TranscriptionServiceError("Server request failed: ${e.message}", e, MODEL)
        }
    }
}
