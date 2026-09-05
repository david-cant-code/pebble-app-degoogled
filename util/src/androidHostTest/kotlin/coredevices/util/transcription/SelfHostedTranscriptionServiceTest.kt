package coredevices.util.transcription

import com.russhwolf.settings.MapSettings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.security.DecryptResult
import coredevices.util.security.SecretCipher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The server client against a mock engine: the request shape both server
 * families read, the bearer header, the mapping of each failure onto the
 * exception the router acts on (with the server's address kept out of
 * it), the bound on a reply, and the connection test's own client.
 */
class SelfHostedTranscriptionServiceTest {

    private object PlainCipher : SecretCipher {
        // Reversed rather than copied, so "the token is not stored in the clear" is a real check.
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()
        override fun decrypt(stored: String): DecryptResult =
            if (stored.startsWith("enc:")) DecryptResult.Success(stored.removePrefix("enc:").reversed()) else DecryptResult.Unrecoverable
    }

    private class Harness(
        url: String? = "https://stt.example.net/inference",
        model: String? = null,
        token: String? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val requests = ArrayList<HttpRequestData>()
        val bodies = ArrayList<String>()
        val store = SelfHostedServerStore(MapSettings(), PlainCipher).also { it.setToken(token) }
        val config = MutableStateFlow(CoreConfig(sttConfig = STTConfig(serverUrl = url, serverModel = model)))
        val hostPorts = ArrayList<String>()
        val service = SelfHostedTranscriptionService(
            coreConfigFlow = CoreConfigFlow(config),
            store = store,
            clientFactory = { hostPort, _ ->
                hostPorts += hostPort
                HttpClient(MockEngine { request ->
                    requests += request
                    bodies += request.body.toByteArray().decodeToString()
                    handler(request)
                })
            },
        )

        suspend fun transcribe(audio: ByteArray = ByteArray(32_000) { 1 }, language: STTLanguage = STTLanguage.Automatic) =
            service.transcribe(flowOf(audio), sampleRate = 16_000, language = language)
                .filterIsInstance<TranscriptionSessionStatus.Transcription>().first()
    }

    private fun jsonOk(text: String) = """{"text":"$text"}"""

    @Test
    fun postsAMultipartWavWithTheFieldsBothServerFamiliesRead() = runBlocking {
        val h = Harness(model = "whisper-1", token = "tok-123") {
            respond(jsonOk(" Hello there.\\n Second line. "), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val result = h.transcribe(language = STTLanguage.Specific(setOf("iw")))
        assertEquals("Hello there. Second line.", result.text)
        assertEquals(SelfHostedTranscriptionService.MODEL, result.modelUsed)

        val request = h.requests.single()
        assertEquals("https://stt.example.net/inference", request.url.toString())
        assertEquals("Bearer tok-123", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))
        val body = h.bodies.single()
        assertTrue(body.contains("name=\"file\""), body)
        assertTrue(body.contains("filename=\"dictation.wav\""), body)
        assertTrue(body.contains("Content-Type: audio/wav"), body)
        assertTrue(body.contains("RIFF"), body)
        assertTrue(body.contains("name=\"response_format\"") && body.contains("json"), body)
        assertTrue(body.contains("name=\"model\"") && body.contains("whisper-1"), body)
        // The legacy locale code is normalized before it reaches the server.
        assertTrue(body.contains("name=\"language\"") && body.contains("\r\n\r\nhe\r\n"), body)
        assertEquals(listOf("stt.example.net:443"), h.hostPorts)
    }

    @Test
    fun omitsModelLanguageAndAuthWhenUnset() = runBlocking {
        val h = Harness { respond(jsonOk("ok"), HttpStatusCode.OK) }
        h.transcribe()
        val body = h.bodies.single()
        assertTrue(!body.contains("name=\"model\""), body)
        assertTrue(!body.contains("name=\"language\""), body)
        assertEquals(null, h.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun httpErrorsAreServiceErrorsAndTransportErrorsAreNetworkErrors(): Unit = runBlocking {
        val unauthorized = Harness { respond("denied", HttpStatusCode.Unauthorized) }
        val serviceError = assertFailsWith<TranscriptionException.TranscriptionServiceError> { unauthorized.transcribe() }
        assertTrue(assertNotNull(serviceError.message).contains("401"))

        val broken = Harness { throw IOException("connection refused") }
        assertFailsWith<TranscriptionException.TranscriptionNetworkError> { broken.transcribe() }

        val notJson = Harness { respond("<html>", HttpStatusCode.OK) }
        assertFailsWith<TranscriptionException.TranscriptionServiceError> { notJson.transcribe() }
    }

    @Test
    fun anEmptyTranscriptIsNoSpeechAndAMissingServerIsUnavailable() = runBlocking {
        val silent = Harness { respond(jsonOk("  "), HttpStatusCode.OK) }
        assertFailsWith<TranscriptionException.NoSpeechDetected> { silent.transcribe() }

        val unconfigured = Harness(url = null) { respond(jsonOk("x"), HttpStatusCode.OK) }
        assertEquals(false, unconfigured.service.isAvailable())
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> { unconfigured.transcribe() }

        val insecure = Harness(url = "http://stt.example.net/inference") { respond(jsonOk("x"), HttpStatusCode.OK) }
        assertEquals(false, insecure.service.isAvailable())
    }

    @Test
    fun transportFailuresReachTheRouterWithoutTheServersAddress() = runBlocking {
        val h = Harness { throw java.net.ConnectException("Failed to connect to stt.example.net/10.0.0.5:443") }
        val error = assertFailsWith<TranscriptionException.TranscriptionNetworkError> { h.transcribe() }
        val printed = error.stackTraceToString()
        assertFalse(printed.contains("example.net"), printed)
        assertFalse(printed.contains("10.0.0.5"), printed)
        assertEquals("connection refused", error.cause?.message)
    }

    @Test
    fun anOversizedReplyIsRefusedAsAServiceError(): Unit = runBlocking {
        val declared = Harness { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000000")) }
        assertTrue(assertFailsWith<TranscriptionException.TranscriptionServiceError> { declared.transcribe() }.message!!.contains("too large"))

        val undeclared = Harness { respond(jsonOk("a ".repeat(SelfHostedTranscriptionService.MAX_REPLY_BYTES)), HttpStatusCode.OK) }
        assertTrue(assertFailsWith<TranscriptionException.TranscriptionServiceError> { undeclared.transcribe() }.message!!.contains("too large"))

        // At the bound the reply is still read in full.
        val padding = " ".repeat(SelfHostedTranscriptionService.MAX_REPLY_BYTES - jsonOk("ok").length)
        val atTheBound = Harness { respond(jsonOk("ok") + padding, HttpStatusCode.OK) }
        assertEquals("ok", atTheBound.transcribe().text)
    }

    @Test
    fun theConnectionTestRunsOnItsOwnClient() = runBlocking {
        val h = Harness { respond(jsonOk("ok"), HttpStatusCode.OK) }
        h.transcribe()
        h.service.testConnection("https://stt.example.net/inference", model = null, token = null)
        h.transcribe()
        assertEquals(listOf("stt.example.net:443", "stt.example.net:443"), h.hostPorts, "one cached client for dictation, one per test")
    }

    @Test
    fun connectionTestUsesTheGivenCredentialsAndReportsTheStatus() = runBlocking {
        val h = Harness(token = "stored") { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer typed") respond(jsonOk(""), HttpStatusCode.OK)
            else respond("denied", HttpStatusCode.Unauthorized)
        }
        assertEquals(200, h.service.testConnection("https://stt.example.net/inference", model = "m", token = "typed"))
        assertEquals(401, h.service.testConnection("https://stt.example.net/inference", model = null, token = null))
        assertTrue(h.bodies.first().contains("name=\"model\"") && h.bodies.first().contains("\r\n\r\nm\r\n"))
    }
}
