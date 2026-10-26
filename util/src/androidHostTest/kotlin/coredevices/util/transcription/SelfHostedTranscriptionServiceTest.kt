package coredevices.util.transcription

import com.anopticlabs.gravel.network.EndlessResponse
import com.anopticlabs.gravel.network.NotOnAllowedNetworkException
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
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The server client against a mock engine: the request shape both server
 * families read, the bearer header, the mapping of each failure onto the
 * exception the router acts on (with the server's address kept out of
 * it), the bound on a reply, and the connection test's own client.
 */
class SelfHostedTranscriptionServiceTest {

    private class PlainCipher : SecretCipher {
        /** Set to make decryption fail the way a Keystore hiccup does. */
        var failing = false

        // Reversed rather than copied, so "the token is not stored in the clear" is a real check.
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()
        override fun decrypt(stored: String): DecryptResult = when {
            failing -> DecryptResult.TransientFailure
            stored.startsWith("enc:") -> DecryptResult.Success(stored.removePrefix("enc:").reversed())
            else -> DecryptResult.Unrecoverable
        }
    }

    private class Harness(
        url: String? = "https://stt.example.net/inference",
        model: String? = null,
        token: String? = null,
        serverOnMobileData: Boolean = false,
        spokenLanguage: String? = null,
        val wifi: FakeUnmeteredWifi = FakeUnmeteredWifi(),
        val clock: TestTimeSource = TestTimeSource(),
        recordingLimit: Duration = SelfHostedTranscriptionService.RECORDING_REQUEST_LIMIT,
        client: HttpClient? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val requests = ArrayList<HttpRequestData>()
        val bodies = ArrayList<String>()
        val cipher = PlainCipher()
        val store = SelfHostedServerStore(MapSettings(), cipher).also { it.setToken(url?.let(::serverHostPort), token) }
        val config = MutableStateFlow(
            CoreConfig(sttConfig = STTConfig(serverUrl = url, serverModel = model, serverOnMobileData = serverOnMobileData, spokenLanguage = spokenLanguage)),
        )
        val hostPorts = ArrayList<String>()
        val wifiOnlyClients = ArrayList<Boolean>()
        val answerWaits = ArrayList<Duration>()

        /** The answer wait of the client each request went through. */
        val requestWaits = ArrayList<Duration>()
        val service = SelfHostedTranscriptionService(
            coreConfigFlow = CoreConfigFlow(config),
            store = store,
            unmeteredWifi = wifi,
            clientFactory = { hostPort, _, wifiOnly, answerWait ->
                hostPorts += hostPort
                wifiOnlyClients += wifiOnly
                answerWaits += answerWait
                client ?: HttpClient(MockEngine { request ->
                    requests += request
                    requestWaits += answerWait
                    bodies += request.body.toByteArray().decodeToString()
                    handler(request)
                })
            },
            clock = clock,
            recordingLimit = recordingLimit,
        )

        suspend fun transcribeRecording(pcm: ByteArray = ByteArray(64_000) { 1 }) = service.transcribeRecording(pcm, 16_000)

        suspend fun transcribe(audio: ByteArray = ByteArray(32_000) { 1 }, language: STTLanguage = STTLanguage.Automatic) =
            service.transcribe(flowOf(audio), sampleRate = 16_000, language = language)
                .filterIsInstance<TranscriptionSessionStatus.Transcription>().first()
    }

    private fun jsonOk(text: String) = """{"text":"$text"}"""

    /** A dictation reads the address when its session opens and the token when it posts. */
    @Test
    fun aSaveDuringADictationSendsTheNewTokenNowhereElse() = runBlocking<Unit> {
        val h = Harness(token = "tok-123") { respond(jsonOk("words"), headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val audio = flow {
            emit(ByteArray(32_000) { 1 })
            h.store.setToken("other.example.net:443", "tok-other")
            h.config.value = h.config.value.let { it.copy(sttConfig = it.sttConfig.copy(serverUrl = "https://other.example.net/inference")) }
        }
        h.service.transcribe(audio, sampleRate = 16_000, language = STTLanguage.Automatic)
            .filterIsInstance<TranscriptionSessionStatus.Transcription>().first()
        val request = h.requests.single()
        assertEquals("stt.example.net", request.url.host)
        assertEquals(null, request.headers[HttpHeaders.Authorization])
    }

    /** A request carries the token saved for the address it goes to. */
    @Test
    fun aRequestCarriesTheTokenOnlyToTheServerItWasSavedFor() = runBlocking<Unit> {
        val h = Harness(token = "tok-123") { respond(jsonOk("words"), headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        fun sent() = h.requests.last().let { it.url.host to it.headers[HttpHeaders.Authorization] }

        h.transcribeRecording()
        assertEquals("stt.example.net" to "Bearer tok-123", sent())

        // Only the address changed.
        h.config.value = h.config.value.let { it.copy(sttConfig = it.sttConfig.copy(serverUrl = "https://other.example.net/inference")) }
        h.transcribeRecording()
        assertEquals("other.example.net" to null, sent())
        h.transcribe()
        assertEquals("other.example.net" to null, sent())

        // Only the token changed.
        h.config.value = h.config.value.let { it.copy(sttConfig = it.sttConfig.copy(serverUrl = "https://stt.example.net/inference")) }
        h.store.setToken("other.example.net:443", "tok-other")
        h.transcribeRecording()
        assertEquals("stt.example.net" to null, sent())
        h.transcribe()
        assertEquals("stt.example.net" to null, sent())

        h.config.value = h.config.value.let { it.copy(sttConfig = it.sttConfig.copy(serverUrl = "https://other.example.net:443/v1/audio/transcriptions")) }
        h.transcribe()
        assertEquals("other.example.net" to "Bearer tok-other", sent())
    }

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
    fun aDictationCanceledWhileTheReplyArrivesLeavesNoCopierRunning(): Unit = runBlocking {
        val flood = CompletableDeferred<Unit>()
        val server = EndlessResponse(opening = {
            writeFully(ByteArray(10))
            flush()
            flood.await()
        })
        val h = Harness(client = server.client) { error("the reply comes from the endless response") }
        val readerThread = Executors.newSingleThreadExecutor()
        val holdReader = CountDownLatch(1)
        try {
            val dictation = launch(readerThread.asCoroutineDispatcher()) { h.transcribe() }
            // The copier exists once the reply is being read; the reader then waits for more than the opening bytes.
            withTimeout(10.seconds) { while (server.client.coroutineContext.job.children.none()) delay(5) }
            // This holds the reader only because execute runs its block on the caller's dispatcher (ktor 3.5.1:
            // HttpStatement.kt execute; DispatcherSwitching.jvm.kt useEngineDispatcher, "false" by default).
            val readerHeld = CountDownLatch(1)
            readerThread.execute {
                readerHeld.countDown()
                holdReader.await()
            }
            assertTrue(readerHeld.await(10, TimeUnit.SECONDS), "the reader never let go of its thread")
            // The copier fills its buffer while the reader cannot run.
            flood.complete(Unit)
            Thread.sleep(500)
            dictation.cancel()
            holdReader.countDown()
            withTimeout(10.seconds) { dictation.join() }
            server.assertNoCopierLeft()
        } finally {
            holdReader.countDown()
            readerThread.shutdown()
        }
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

    @Test
    fun offTheAllowedNetworkNothingIsSentUnlessMobileDataIsAllowed() = runBlocking {
        val h = Harness(wifi = FakeUnmeteredWifi(usable = false)) { respond(jsonOk("sent"), HttpStatusCode.OK) }
        assertFalse(h.service.allowedOnCurrentNetwork())
        assertFailsWith<TranscriptionException.ServerNotAllowedOnNetwork> { h.transcribe() }
        assertTrue(h.requests.isEmpty())

        h.wifi.usable.value = true
        assertEquals("sent", h.transcribe().text)

        val allowed = Harness(serverOnMobileData = true, wifi = FakeUnmeteredWifi(usable = false)) {
            respond(jsonOk("sent"), HttpStatusCode.OK)
        }
        assertEquals("sent", allowed.transcribe().text)
    }

    @Test
    fun theRuleIsCheckedOnceTheAudioIsCollected() = runBlocking {
        val h = Harness { respond(jsonOk("sent"), HttpStatusCode.OK) }
        val audio = flow {
            emit(ByteArray(16_000) { 1 })
            h.wifi.usable.value = false
            emit(ByteArray(16_000) { 1 })
        }
        assertFailsWith<TranscriptionException.ServerNotAllowedOnNetwork> {
            h.service.transcribe(audio, sampleRate = 16_000, language = STTLanguage.Automatic)
                .filterIsInstance<TranscriptionSessionStatus.Transcription>().first()
        }
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun withMobileDataOffDictationUsesAWifiOnlyClient() = runBlocking {
        val h = Harness { respond(jsonOk("ok"), HttpStatusCode.OK) }
        h.transcribe()
        h.config.value = h.config.value.copy(sttConfig = h.config.value.sttConfig.copy(serverOnMobileData = true))
        h.transcribe()
        h.service.testConnection("https://stt.example.net/inference", model = null, token = null)
        assertEquals(listOf(true, false, false), h.wifiOnlyClients, "dictation off, dictation on, then the test")
    }

    @Test
    fun aSocketTheRuleRefusesIsReportedAsNotAllowed(): Unit = runBlocking {
        val direct = Harness { throw NotOnAllowedNetworkException() }
        assertFailsWith<TranscriptionException.ServerNotAllowedOnNetwork> { direct.transcribe() }

        val wrapped = Harness { throw IOException("connect failed", NotOnAllowedNetworkException()) }
        assertFailsWith<TranscriptionException.ServerNotAllowedOnNetwork> { wrapped.transcribe() }
    }

    @Test
    fun theConnectionTestRunsOffWifiToo() = runBlocking {
        val h = Harness(wifi = FakeUnmeteredWifi(usable = false)) { respond(jsonOk("ok"), HttpStatusCode.OK) }
        assertEquals(200, h.service.testConnection("https://stt.example.net/inference", model = null, token = null))
    }

    @Test
    fun aFailureOnceTheRuleNoLongerAllowsTheNetworkIsReportedAsNotAllowed(): Unit = runBlocking {
        val wifi = FakeUnmeteredWifi()
        val held = Harness(wifi = wifi) { wifi.usable.value = false; throw IOException("connection reset") }
        assertFailsWith<TranscriptionException.ServerNotAllowedOnNetwork> { held.transcribe() }

        val wifi2 = FakeUnmeteredWifi()
        val unheld = Harness(serverOnMobileData = true, wifi = wifi2) { wifi2.usable.value = false; throw IOException("connection reset") }
        assertFailsWith<TranscriptionException.TranscriptionNetworkError> { unheld.transcribe() }

        val test = Harness(wifi = FakeUnmeteredWifi(usable = false)) { throw IOException("connection refused") }
        assertFailsWith<TranscriptionException.TranscriptionNetworkError> {
            test.service.testConnection("https://stt.example.net/inference", model = null, token = null)
        }
    }

    @Test
    fun aRecordingIsOneWavWithTheConfiguredLanguageAndReturnsItsText() = runBlocking {
        val h = Harness(model = "whisper-1", token = "tok-123", spokenLanguage = "iw") {
            respond(jsonOk(" First part.\\n Second part. "), HttpStatusCode.OK)
        }
        assertEquals("First part. Second part.", h.transcribeRecording(ByteArray(64_000) { 1 }))
        val request = h.requests.single()
        assertEquals("Bearer tok-123", request.headers[HttpHeaders.Authorization])
        val body = h.bodies.single()
        assertTrue(body.contains("RIFF") && body.contains("Content-Type: audio/wav"), body)
        assertTrue(body.contains("filename=\"recording.wav\""), body)
        assertTrue(body.contains("name=\"model\"") && body.contains("whisper-1"), body)
        assertTrue(body.contains("name=\"language\"") && body.contains("\r\n\r\nhe\r\n"), body)
        // 64 000 bytes of audio behind a 44-byte header.
        assertEquals(64_044, h.requests.single().body.toByteArray().let { bytes ->
            val start = String(bytes, Charsets.ISO_8859_1).indexOf("RIFF")
            val riffSize = (0 until 4).fold(0) { acc, i -> acc or ((bytes[start + 4 + i].toInt() and 0xFF) shl (8 * i)) }
            riffSize + 8
        })
    }

    @Test
    fun recordingsAndDictationKeepClientsOfTheirOwnWithTheirOwnAnswerWait() = runBlocking {
        val h = Harness { respond(jsonOk("ok"), HttpStatusCode.OK) }
        h.transcribe()
        h.transcribeRecording()
        h.transcribe()
        h.transcribeRecording()
        assertEquals(listOf(20.seconds, 10.minutes), h.answerWaits, "one client per purpose, each kept")
        assertEquals(listOf(20.seconds, 10.minutes, 20.seconds, 10.minutes), h.requestWaits)
    }

    @Test
    fun aRecordingFollowsTheWifiRuleAndNeedsAServer(): Unit = runBlocking {
        val off = Harness(wifi = FakeUnmeteredWifi(usable = false)) { respond(jsonOk("sent"), HttpStatusCode.OK) }
        assertFailsWith<TranscriptionException.ServerNotAllowedOnNetwork> { off.transcribeRecording() }
        assertTrue(off.requests.isEmpty())
        val mobile = Harness(serverOnMobileData = true, wifi = FakeUnmeteredWifi(usable = false)) { respond(jsonOk("sent"), HttpStatusCode.OK) }
        assertEquals("sent", mobile.transcribeRecording())
        assertEquals(listOf(false), mobile.wifiOnlyClients)

        val unconfigured = Harness(url = null) { respond(jsonOk("x"), HttpStatusCode.OK) }
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> { unconfigured.transcribeRecording() }
    }

    @Test
    fun aRefusedCertificateIsTheServersErrorForARecordingAndUnreachableForDictation(): Unit = runBlocking {
        val refusal = object : java.security.cert.CertificateException("refused"), ServerCertificateRefusal {
            override val fingerprint = "AB:CD"
            override val changed = true
        }
        val h = Harness { throw javax.net.ssl.SSLHandshakeException("handshake").apply { initCause(refusal) } }
        assertFailsWith<TranscriptionException.TranscriptionServiceError> { h.transcribeRecording() }
        assertFailsWith<TranscriptionException.TranscriptionNetworkError> { h.transcribe() }
    }

    @Test
    fun transportFailuresAreToldApartByClass() {
        val ktorRead = java.net.SocketTimeoutException("Socket timeout has expired [url=https://connect.home.lan/inference, socket_timeout=600000] ms")
            .apply { initCause(java.net.SocketTimeoutException("timeout")) }
        val cases = listOf(
            java.net.ConnectException("Failed to connect") to TransportFailure.NOT_CONNECTED,
            java.net.UnknownHostException("stt.example.net") to TransportFailure.NOT_CONNECTED,
            java.net.NoRouteToHostException("no route") to TransportFailure.NOT_CONNECTED,
            io.ktor.client.network.sockets.ConnectTimeoutException("Connect timeout has expired", java.net.SocketTimeoutException("failed to connect")) to
                TransportFailure.NOT_CONNECTED,
            ktorRead to TransportFailure.TIMED_OUT,
            java.net.SocketException("Connection reset") to TransportFailure.LOST,
            java.io.EOFException("unexpected end of stream") to TransportFailure.LOST,
            javax.net.ssl.SSLPeerUnverifiedException("Hostname 10.0.0.5 not verified") to TransportFailure.CERTIFICATE,
            IOException("handshake", object : java.security.cert.CertificateException("refused"), ServerCertificateRefusal {
                override val fingerprint = "AB:CD"
                override val changed = false
            }) to TransportFailure.CERTIFICATE,
        )
        for ((failure, expected) in cases) assertEquals(expected, transportFailure(IOException("wrapped", failure)), "$failure")
    }

    @Test
    fun theConnectFailuresMatchedByNameAreJavaNetClasses() {
        for (name in CONNECT_FAILURES) assertTrue(runCatching { Class.forName("java.net.$name") }.isSuccess, name)
    }

    @Test
    fun aRecordingTimeoutIsTheServersErrorOnlyOnceTheAnswerWaitHasPassed(): Unit = runBlocking {
        val clock = TestTimeSource()
        var waited = 10.minutes - 1.seconds
        val h = Harness(clock = clock) {
            clock += waited
            throw java.net.SocketTimeoutException("timeout")
        }
        val early = assertFailsWith<TranscriptionException.TranscriptionNetworkError> { h.transcribeRecording() }
        assertEquals(TransportFailure.TIMED_OUT, (early.cause as ServerUnreachableException).failure)
        waited = 10.minutes
        assertFailsWith<TranscriptionException.TranscriptionServiceError> { h.transcribeRecording() }
    }

    @Test
    fun aRecordingStillWaitingForAReplyAtItsLimitIsTheServersError(): Unit = runBlocking {
        val h = Harness(recordingLimit = 200.milliseconds) { awaitCancellation() }
        assertFailsWith<TranscriptionException.TranscriptionServiceError> { withTimeout(10.seconds) { h.transcribeRecording() } }
    }

    @Test
    fun aRecordingCanceledByItsCallerEndsCanceled(): Unit = runBlocking {
        val asked = CompletableDeferred<Unit>()
        val h = Harness {
            asked.complete(Unit)
            awaitCancellation()
        }
        var failure: Throwable? = null
        val call = launch {
            try {
                h.transcribeRecording()
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        withTimeout(10.seconds) {
            asked.await()
            call.cancelAndJoin()
        }
        assertIs<CancellationException>(failure)
    }

    @Test
    fun aCallerCancelThatComesAsTheLimitRunsOutEndsCanceled(): Unit = runBlocking {
        val asked = CompletableDeferred<Unit>()
        val limitRanOut = CountDownLatch(1)
        val h = Harness(recordingLimit = 1.seconds) {
            asked.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                limitRanOut.countDown()
            }
        }
        var failure: Throwable? = null
        val call = launch {
            try {
                h.transcribeRecording()
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        withTimeout(10.seconds) { asked.await() }
        // The limit's timer runs on DefaultDelay's thread, not runBlocking's (kotlinx.coroutines 1.11.0, jvm EventLoop.kt
        // BlockingEventLoop, Delay.kt invokeOnTimeout), so blocking here keeps the request from unwinding before the cancel.
        assertTrue(limitRanOut.await(10, TimeUnit.SECONDS))
        call.cancel()
        withTimeout(10.seconds) { call.join() }
        assertIs<CancellationException>(failure)
    }

    @Test
    fun aLostConnectionStaysANetworkErrorThatSaysSo(): Unit = runBlocking {
        val h = Harness { throw java.net.SocketException("Connection reset") }
        val error = assertFailsWith<TranscriptionException.TranscriptionNetworkError> { h.transcribeRecording() }
        assertEquals(TransportFailure.LOST, (error.cause as ServerUnreachableException).failure)
        val refused = Harness { throw java.net.ConnectException("Failed to connect") }
        val unreachable = assertFailsWith<TranscriptionException.TranscriptionNetworkError> { refused.transcribeRecording() }
        assertEquals(TransportFailure.NOT_CONNECTED, (unreachable.cause as ServerUnreachableException).failure)
    }

    @Test
    fun aRecordingsHttpErrorIsAServiceErrorAndAnEmptyTextIsNoSpeech(): Unit = runBlocking {
        val failing = Harness { respond("broken", HttpStatusCode.InternalServerError) }
        assertFailsWith<TranscriptionException.TranscriptionServiceError> { failing.transcribeRecording() }
        val silent = Harness { respond(jsonOk(" "), HttpStatusCode.OK) }
        assertFailsWith<TranscriptionException.NoSpeechDetected> { silent.transcribeRecording() }
        assertFailsWith<TranscriptionException.NoSpeechDetected> { silent.transcribeRecording(ByteArray(0)) }
    }

    @Test
    fun theSettingsStampFollowsTheServerSettingsOnly() {
        val h = Harness(model = "m", token = "t") { respond(jsonOk("x"), HttpStatusCode.OK) }
        fun update(change: STTConfig.() -> STTConfig) {
            h.config.value = h.config.value.copy(sttConfig = h.config.value.sttConfig.change())
        }
        val first = h.service.settingsStamp()
        update { copy(serverOnMobileData = true, flakyNetworkMode = true) }
        assertEquals(first, h.service.settingsStamp(), "the network switches are not server settings")
        update { copy(serverUrl = "https://stt.example.net/other") }
        val pathChanged = h.service.settingsStamp()
        assertNotEquals(first, pathChanged)
        update { copy(serverModel = "n") }
        val modelChanged = h.service.settingsStamp()
        assertNotEquals(pathChanged, modelChanged)
        h.store.setToken("stt.example.net:443", "u")
        val tokenChanged = h.service.settingsStamp()
        assertNotEquals(modelChanged, tokenChanged)
        h.store.trust("stt.example.net:443", "AB:CD")
        assertNotEquals(tokenChanged, h.service.settingsStamp())
    }

    @Test
    fun aTokenThatCannotBeReadForAMomentLeavesTheStampAsItWas() {
        val h = Harness(token = "t") { respond(jsonOk("x"), HttpStatusCode.OK) }
        val stamp = h.service.settingsStamp()
        h.cipher.failing = true
        assertEquals(stamp, h.service.settingsStamp())
    }

    @Test
    fun aSuccessfulReplyFromTheServerSaysWhetherDictationHasALaneOfItsOwn() = runBlocking {
        var lane: String? = "separate"
        var status = HttpStatusCode.OK
        val h = Harness {
            val headers = lane?.let { headersOf("gravel-dictation-lane", it) } ?: headersOf()
            respond(jsonOk("x"), status, headers)
        }
        assertFalse(h.service.hasDictationLane())
        h.transcribe()
        assertTrue(h.service.hasDictationLane(), "the header name is matched whatever its case")
        lane = null
        h.transcribeRecording()
        assertFalse(h.service.hasDictationLane(), "a reply without the header ends it")
        lane = " separate "
        h.service.testConnection("https://stt.example.net/inference", null, null)
        assertTrue(h.service.hasDictationLane())
        lane = "shared"
        h.transcribe()
        assertFalse(h.service.hasDictationLane())
        lane = "separate"
        status = HttpStatusCode.InternalServerError
        assertFailsWith<TranscriptionException.TranscriptionServiceError> { h.transcribe() }
        assertFalse(h.service.hasDictationLane(), "only a successful reply counts")
    }

    @Test
    fun aSuccessfulStatusWithAReplyTooLargeToReadSaysNothingAboutTheLane() = runBlocking {
        val h = Harness {
            respond("x".repeat(SelfHostedTranscriptionService.MAX_REPLY_BYTES + 1), HttpStatusCode.OK, headersOf("Gravel-Dictation-Lane", "separate"))
        }
        assertFailsWith<TranscriptionException.TranscriptionServiceError> { h.transcribe() }
        assertFalse(h.service.hasDictationLane())
    }

    @Test
    fun theLaneIsRememberedForOneAddressOnly() = runBlocking {
        val h = Harness { respond(jsonOk("x"), HttpStatusCode.OK, headersOf("Gravel-Dictation-Lane", "separate")) }
        h.transcribe()
        assertTrue(h.service.hasDictationLane())
        h.config.value = h.config.value.copy(sttConfig = h.config.value.sttConfig.copy(serverUrl = "https://stt.example.net:8443/inference"))
        assertFalse(h.service.hasDictationLane(), "another port is another server")
        h.config.value = h.config.value.copy(sttConfig = h.config.value.sttConfig.copy(serverUrl = "https://stt.example.net/v1/audio/transcriptions"))
        assertTrue(h.service.hasDictationLane(), "the path is not part of the address")
        h.store.rememberDictationLane("other.example.net:443", true)
        assertFalse(h.service.hasDictationLane())

        h.store.keepDictationLaneOnlyFor("stt.example.net:443")
        assertFalse(h.store.hasDictationLane("other.example.net:443"), "saving another address forgets it")
        h.store.rememberDictationLane("stt.example.net:443", true)
        h.store.keepDictationLaneOnlyFor("stt.example.net:443")
        assertTrue(h.service.hasDictationLane(), "saving the same address keeps it")
        h.store.keepDictationLaneOnlyFor(null)
        assertFalse(h.service.hasDictationLane(), "removing the server forgets it")
    }
}
