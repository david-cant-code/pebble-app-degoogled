package coredevices.util.transcription

import com.russhwolf.settings.MapSettings
import coredevices.api.WisprFlowAuth
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.util.security.DecryptResult
import coredevices.util.security.SecretCipher
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The router's server decisions, which upstream's cloud pair never
 * exercises here: a configured server takes the remote slot in every
 * remote mode, the fallback modes still fall back around it, and
 * availability follows the server. The local model is a fake engine and
 * the server a mock engine, so the whole route runs on the host.
 */
class HybridTranscriptionServiceTest {

    private object PlainCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:$plaintext"
        override fun decrypt(stored: String): DecryptResult =
            if (stored.startsWith("enc:")) DecryptResult.Success(stored.removePrefix("enc:")) else DecryptResult.Unrecoverable
    }

    // The cloud services are ApiClients that resolve their engine through Koin at construction.
    @BeforeTest
    fun startKoinWithAnEngine() {
        startKoin { modules(module { factory<HttpClientEngine> { MockEngine { respond("", HttpStatusCode.NotFound) } } }) }
    }

    @AfterTest
    fun stopKoinAgain() = stopKoin()

    private class Harness(
        mode: CactusSTTMode,
        serverUrl: String? = "https://stt.example.net/inference",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val engine = FakeWhisperEngine()
        val requests = ArrayList<HttpRequestData>()
        val config = MutableStateFlow(CoreConfig(sttConfig = STTConfig(mode = mode, modelName = "model-a", serverUrl = serverUrl)))
        private val configFlow = CoreConfigFlow(config)
        val whisper = WhisperTranscriptionService(
            coreConfigFlow = configFlow,
            modelProvider = FakeModelProvider(),
            analytics = NoopAnalytics,
            inferenceBoost = NoOpInferenceBoost(),
            engine = engine.engine,
            debugBuild = { false },
            clearCaptures = {},
        )
        private val server = SelfHostedTranscriptionService(
            coreConfigFlow = configFlow,
            store = SelfHostedServerStore(MapSettings(), PlainCipher),
            clientFactory = { _, _ -> HttpClient(MockEngine { request -> requests += request; handler(request) }) },
        )
        val hybrid = HybridTranscriptionService(
            coreConfigFlow = configFlow,
            whisper = whisper,
            wisprFlow = WisprFlowRESTTranscriptionService(WisprFlowAuth()),
            kirinki = KirinkiTranscriptionService(),
            analytics = NoopAnalytics,
            platform = PlatformSpeechRecognizer(),
            selfHosted = server,
        )

        /** The local model comes up only in the modes that use it; the others never wait for it. */
        suspend fun awaitModel() {
            if (!config.value.sttConfig.mode.usesLocalCactus()) return
            withTimeout(60.seconds) { while (!whisper.isModelReady) delay(10) }
        }

        suspend fun transcribe(initialTimeout: Duration? = null): TranscriptionSessionStatus.Transcription =
            hybrid.transcribe(flowOf(realPcmBytes()), sampleRate = 16_000, language = STTLanguage.Automatic, initialTimeout = initialTimeout)
                .filterIsInstance<TranscriptionSessionStatus.Transcription>().first()
    }

    private fun jsonOk(text: String) = """{"text":"$text"}"""

    @Test
    fun serverOnlyUsesTheServerAndNeverTheLocalModel() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.RemoteOnly) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        h.awaitModel()
        val result = h.transcribe()
        assertEquals("from server", result.text)
        assertEquals(SelfHostedTranscriptionService.MODEL, result.modelUsed)
        assertEquals(1, h.requests.size)
        assertEquals(0, h.engine.realCalls)
    }

    @Test
    fun serverWithLocalFallbackGoesLocalWhenTheServerFails() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.RemoteFirst) { throw IOException("connection refused") }
        h.awaitModel()
        val result = h.transcribe()
        assertEquals("hello world", result.text)
        assertEquals("model-a", result.modelUsed)
        assertEquals(1, h.requests.size)
        assertEquals(1, h.engine.realCalls)
    }

    @Test
    fun serverWithLocalFallbackGoesLocalWhenTheServerIsSlow() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.RemoteFirst) { delay(30.seconds); respond(jsonOk("late"), HttpStatusCode.OK) }
        h.awaitModel()
        val started = TimeSource.Monotonic.markNow()
        val result = h.transcribe(initialTimeout = 200.milliseconds)
        assertEquals("hello world", result.text)
        assertTrue(started.elapsedNow() < 10.seconds, "the fallback must not wait for the slow server")
        assertEquals(1, h.engine.realCalls)
    }

    @Test
    fun localWithServerFallbackUsesTheServerOnlyForAnEmptyLocalResult() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.LocalFirst) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        h.awaitModel()
        assertEquals("hello world", h.transcribe().text)
        assertTrue(h.requests.isEmpty(), "a local result needs no server")

        h.engine.reply = ""
        assertEquals("from server", h.transcribe().text)
        assertEquals(1, h.requests.size)
    }

    @Test
    fun localOnlyNeverContactsAConfiguredServer() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.LocalOnly) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        h.awaitModel()
        assertEquals("hello world", h.transcribe().text)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun availabilityFollowsTheServerInTheRemoteModes() = runBlocking(Dispatchers.Default) {
        for (mode in listOf(CactusSTTMode.RemoteOnly, CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst, CactusSTTMode.PlatformOnly)) {
            val configured = Harness(mode) { respond("", HttpStatusCode.OK) }
            configured.awaitModel()
            assertTrue(configured.hybrid.isAvailable(), "$mode with a server")
        }
        val serverOnly = Harness(CactusSTTMode.RemoteOnly, serverUrl = null) { respond("", HttpStatusCode.OK) }
        serverOnly.awaitModel()
        assertFalse(serverOnly.hybrid.isAvailable(), "Server Only without a server, and the cloud pair cannot sign in")
        val platformOnly = Harness(CactusSTTMode.PlatformOnly, serverUrl = null) { respond("", HttpStatusCode.OK) }
        platformOnly.awaitModel()
        assertFalse(platformOnly.hybrid.isAvailable())
        val withFallback = Harness(CactusSTTMode.RemoteFirst, serverUrl = null) { respond("", HttpStatusCode.OK) }
        withFallback.awaitModel()
        assertTrue(withFallback.hybrid.isAvailable(), "the local model still serves the fallback mode")
    }
}
