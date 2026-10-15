package coredevices.util.transcription

import com.anopticlabs.gravel.transcription.ServerDictationHold
import com.russhwolf.settings.MapSettings
import coredevices.api.WisprFlowAuth
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.util.security.DecryptResult
import coredevices.util.security.SecretCipher
import coredevices.whisper.WhisperEngineUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        wifi: Boolean = true,
        mobileData: Boolean = false,
        flaky: Boolean = false,
        private val modelInstalled: Boolean = true,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val engine = FakeWhisperEngine()
        val analytics = EventRecordingAnalytics()
        val requests = ArrayList<HttpRequestData>()
        val config = MutableStateFlow(
            CoreConfig(
                sttConfig = STTConfig(
                    mode = mode,
                    modelName = "model-a",
                    serverUrl = serverUrl,
                    serverOnMobileData = mobileData,
                    flakyNetworkMode = flaky,
                ),
            ),
        )
        private val configFlow = CoreConfigFlow(config)
        val whisper = WhisperTranscriptionService(
            coreConfigFlow = configFlow,
            modelProvider = FakeModelProvider(modelInstalled),
            analytics = NoopAnalytics,
            inferenceBoost = NoOpInferenceBoost(),
            engine = engine.engine,
            debugBuild = { false },
            clearCaptures = {},
        )
        val store = SelfHostedServerStore(MapSettings(), PlainCipher)
        val serverDictation = ServerDictationHold(bound = 30.seconds)
        private val server = SelfHostedTranscriptionService(
            coreConfigFlow = configFlow,
            store = store,
            unmeteredWifi = FakeUnmeteredWifi(wifi),
            clientFactory = { _, _, _, _ -> HttpClient(MockEngine { request -> requests += request; handler(request) }) },
        )
        val hybrid = HybridTranscriptionService(
            coreConfigFlow = configFlow,
            whisper = whisper,
            wisprFlow = WisprFlowRESTTranscriptionService(WisprFlowAuth()),
            kirinki = KirinkiTranscriptionService(),
            analytics = analytics,
            platform = PlatformSpeechRecognizer(),
            selfHosted = server,
            serverDictation = serverDictation,
        )

        fun announceLane() = store.rememberDictationLane(checkNotNull(serverHostPort(checkNotNull(config.value.sttConfig.serverUrl))), true)

        /** A long Notebook recording's request that runs until a hold stops it, then answers when sent again. */
        @Volatile
        var longRequests = 0
        val longStopped = CompletableDeferred<Unit>()

        suspend fun longRecording(): String? = serverDictation.sendRecording(long = true, stillWanted = { true }) {
            if (++longRequests == 1) {
                try {
                    awaitCancellation()
                } finally {
                    longStopped.complete(Unit)
                }
            }
            "recording words"
        }

        /** The local model comes up only in the modes that use it; the others never wait for it. */
        suspend fun awaitModel() {
            if (!config.value.sttConfig.mode.usesLocalCactus() || !modelInstalled) return
            withTimeout(60.seconds) { while (!whisper.isModelReady) delay(10) }
        }

        fun serverFailures() = analytics.events.filter { (name, params) ->
            name == TRANSCRIPTION_FAILURE_EVENT && params?.get("service") == "server"
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

    /** A local decode that fails outright, not only an empty one, goes to the server, and the server is what succeeded. */
    @Test
    fun localWithServerFallbackUsesTheServerWhenTheLocalDecodeFails() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.LocalFirst) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        h.awaitModel()
        h.engine.failure = WhisperEngineUnavailableException("engine process died during transcribe")
        val result = h.transcribe()
        assertEquals("from server", result.text)
        assertEquals(SelfHostedTranscriptionService.MODEL, result.modelUsed)
        assertEquals(1, h.engine.realCalls)
        assertEquals(1, h.requests.size)
        assertEquals(CactusSTTMode.RemoteOnly, h.hybrid.lastSuccessfulMode)
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

    @Test
    fun serverOnlyOffWifiRefusesTheSessionAndSendsNothing() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.RemoteOnly, wifi = false) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        assertFalse(h.hybrid.isAvailable())
        assertFailsWith<TranscriptionException.ServerNotAllowedOnNetwork> { h.transcribe() }
        assertTrue(h.requests.isEmpty())
        assertEquals(0, h.engine.realCalls)

        val onWifi = Harness(CactusSTTMode.RemoteOnly) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        assertTrue(onWifi.hybrid.isAvailable())
    }

    @Test
    fun theFallbackModesOffWifiStayOnThePhone() = runBlocking(Dispatchers.Default) {
        for (mode in listOf(CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst)) {
            val h = Harness(mode, wifi = false) { respond(jsonOk("from server"), HttpStatusCode.OK) }
            assertTrue(h.hybrid.isAvailable(), "$mode with a model on the phone, before it is loaded")
            h.awaitModel()
            assertEquals("hello world", h.transcribe().text, "$mode")
            assertEquals(CactusSTTMode.LocalOnly, h.hybrid.lastSuccessfulMode, "$mode")

            h.engine.reply = ""
            assertFailsWith<TranscriptionException.NoSpeechDetected> { h.transcribe() }
            assertTrue(h.requests.isEmpty(), "$mode sent audio off Wi-Fi")
            assertTrue(h.serverFailures().isEmpty(), "$mode tried the server: ${h.serverFailures()}")
        }
    }

    /** Refused at setup; a session the network turned away from mid-recording ends as a network refusal. */
    @Test
    fun theFallbackModesOffWifiWithoutAModelHaveNoPath() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.RemoteFirst, wifi = false, modelInstalled = false) {
            respond(jsonOk("from server"), HttpStatusCode.OK)
        }
        assertFalse(h.hybrid.isAvailable())
        h.hybrid.earlyInit()
        assertFalse(h.whisper.dictationPriority.isHeld, "nothing on the phone will run")
        assertFailsWith<TranscriptionException.ServerNotAllowedOnNetwork> { h.transcribe() }
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun flakyNetworkModeKeepsDictationOnThePhone() = runBlocking(Dispatchers.Default) {
        for (mode in listOf(CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst)) {
            val h = Harness(mode, flaky = true) { respond(jsonOk("from server"), HttpStatusCode.OK) }
            h.awaitModel()
            assertEquals("hello world", h.transcribe().text, "$mode")
            h.engine.reply = ""
            assertFailsWith<TranscriptionException.NoSpeechDetected> { h.transcribe() }
            assertTrue(h.requests.isEmpty(), "$mode sent audio with Flaky Network Mode on")
        }
    }

    @Test
    fun flakyNetworkModeWithoutAModelStillUsesTheServer() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.RemoteFirst, flaky = true, modelInstalled = false) {
            respond(jsonOk("from server"), HttpStatusCode.OK)
        }
        assertEquals("from server", h.transcribe().text)
        assertEquals(1, h.requests.size)
    }

    @Test
    fun flakyNetworkModeLeavesServerOnlyAlone() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.RemoteOnly, flaky = true) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        h.awaitModel()
        assertEquals("from server", h.transcribe().text)
        assertEquals(0, h.engine.realCalls)
    }

    /** Where the phone answers first, the dictation's recording already stops a Notebook transcription. */
    @Test
    fun serverWithLocalFallbackHoldsTheEngineEarlyOnlyWhenItStaysOnThePhone() = runBlocking(Dispatchers.Default) {
        val offWifi = Harness(CactusSTTMode.RemoteFirst, wifi = false) { respond(jsonOk("x"), HttpStatusCode.OK) }
        offWifi.hybrid.earlyInit()
        assertTrue(offWifi.whisper.dictationPriority.isHeld)

        val flaky = Harness(CactusSTTMode.RemoteFirst, flaky = true) { respond(jsonOk("x"), HttpStatusCode.OK) }
        flaky.hybrid.earlyInit()
        assertTrue(flaky.whisper.dictationPriority.isHeld)

        val toTheServer = Harness(CactusSTTMode.RemoteFirst) { respond(jsonOk("x"), HttpStatusCode.OK) }
        toTheServer.hybrid.earlyInit()
        assertFalse(toTheServer.whisper.dictationPriority.isHeld)
    }

    @Test
    fun withMobileDataAllowedTheServerModesRouteAsBefore() = runBlocking(Dispatchers.Default) {
        val serverOnly = Harness(CactusSTTMode.RemoteOnly, wifi = false, mobileData = true) {
            respond(jsonOk("from server"), HttpStatusCode.OK)
        }
        assertTrue(serverOnly.hybrid.isAvailable())
        assertEquals("from server", serverOnly.transcribe().text)

        val serverFirst = Harness(CactusSTTMode.RemoteFirst, wifi = false, mobileData = true) {
            respond(jsonOk("from server"), HttpStatusCode.OK)
        }
        serverFirst.awaitModel()
        serverFirst.hybrid.earlyInit()
        assertFalse(serverFirst.whisper.dictationPriority.isHeld)
        assertEquals("from server", serverFirst.transcribe().text)
        assertEquals(0, serverFirst.engine.realCalls)

        val localFirst = Harness(CactusSTTMode.LocalFirst, wifi = false, mobileData = true) {
            respond(jsonOk("from server"), HttpStatusCode.OK)
        }
        localFirst.awaitModel()
        localFirst.engine.reply = ""
        assertEquals("from server", localFirst.transcribe().text)
    }

    @Test
    fun platformOnlyFollowsTheRuleForItsServerPath() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.PlatformOnly, wifi = false) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        assertFalse(h.hybrid.isAvailable())
    }

    @Test
    fun withoutAServerTheFallbackModesAreNotKeptOnThePhone() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.RemoteFirst, serverUrl = null, wifi = false) { respond(jsonOk("x"), HttpStatusCode.OK) }
        h.awaitModel()
        h.hybrid.earlyInit()
        assertFalse(h.whisper.dictationPriority.isHeld)
    }

    /** Server Only on a server without a separate dictation lane: the session opening stops a long recording there. */
    @Test
    fun aDictationOnlyTheServerCanAnswerHangsUpALongRecordingWhichGoesAgainAfterTheAnswer() = runBlocking(Dispatchers.Default) {
        lateinit var h: Harness
        h = Harness(CactusSTTMode.RemoteOnly) {
            assertEquals(1, h.longRequests, "the recording was sent again before the dictation's answer")
            respond(jsonOk("from server"), HttpStatusCode.OK)
        }
        val long = async { h.longRecording() }
        withTimeout(5.seconds) { while (h.longRequests == 0) delay(5) }
        h.hybrid.earlyInit()
        withTimeout(5.seconds) { h.longStopped.await() }
        assertEquals("from server", h.transcribe().text)
        assertEquals("recording words", withTimeout(5.seconds) { long.await() })
        assertEquals(2, h.longRequests)
        assertFalse(h.serverDictation.isHeld)
    }

    @Test
    fun theFallbackModesWithoutAPhoneModelHangUpLikeServerOnly() = runBlocking(Dispatchers.Default) {
        for (mode in listOf(CactusSTTMode.RemoteOnly, CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst)) {
            val h = Harness(mode, modelInstalled = false) { respond(jsonOk("x"), HttpStatusCode.OK) }
            h.hybrid.earlyInit()
            assertTrue(h.serverDictation.isHeld, "$mode")
        }
        for (mode in listOf(CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst)) {
            val h = Harness(mode) { respond(jsonOk("x"), HttpStatusCode.OK) }
            h.hybrid.earlyInit()
            assertFalse(h.serverDictation.isHeld, "$mode has the phone to answer")
        }
        val offWifi = Harness(CactusSTTMode.RemoteOnly, wifi = false) { respond(jsonOk("x"), HttpStatusCode.OK) }
        offWifi.hybrid.earlyInit()
        assertFalse(offWifi.serverDictation.isHeld, "the dictation cannot reach the server")
        val noServer = Harness(CactusSTTMode.RemoteOnly, serverUrl = null) { respond(jsonOk("x"), HttpStatusCode.OK) }
        noServer.hybrid.earlyInit()
        assertFalse(noServer.serverDictation.isHeld)
        for (mode in listOf(CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst)) {
            val refused = Harness(mode, wifi = false, modelInstalled = false) { respond(jsonOk("x"), HttpStatusCode.OK) }
            refused.hybrid.earlyInit()
            assertFalse(refused.serverDictation.isHeld, "$mode off Wi-Fi")
            val lane = Harness(mode, modelInstalled = false) { respond(jsonOk("x"), HttpStatusCode.OK) }
            lane.announceLane()
            lane.hybrid.earlyInit()
            assertFalse(lane.serverDictation.isHeld, "$mode with a lane")
        }
        val flaky = Harness(CactusSTTMode.RemoteFirst, flaky = true, modelInstalled = false) { respond(jsonOk("x"), HttpStatusCode.OK) }
        flaky.hybrid.earlyInit()
        assertTrue(flaky.serverDictation.isHeld, "Flaky Network Mode without a model still needs the server")
        for (model in listOf(true, false)) {
            val rebble = Harness(CactusSTTMode.RebbleFallback, modelInstalled = model) { respond(jsonOk("x"), HttpStatusCode.OK) }
            rebble.hybrid.earlyInit()
            assertFalse(rebble.serverDictation.isHeld)
        }
    }

    @Test
    fun withALongRecordingOnTheServerTheFallbackModesKeepDictationOnThePhoneAlone() = runBlocking(Dispatchers.Default) {
        for (mode in listOf(CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst)) {
            val h = Harness(mode) { respond(jsonOk("from server"), HttpStatusCode.OK) }
            h.awaitModel()
            val long = async { h.longRecording() }
            withTimeout(5.seconds) { while (h.longRequests == 0) delay(5) }
            h.hybrid.earlyInit()
            assertTrue(h.whisper.dictationPriority.isHeld, "$mode")
            assertEquals("hello world", h.transcribe().text, "$mode")
            h.engine.reply = ""
            assertFailsWith<TranscriptionException.NoSpeechDetected>("$mode") { h.transcribe() }
            assertTrue(h.requests.isEmpty(), "$mode sent the dictation behind the recording")
            assertEquals(1, h.longRequests, "$mode stopped the recording")
            long.cancel()
        }
    }

    @Test
    fun aServerWithADictationLaneGetsDictationAsIfNoRecordingWereOnIt() = runBlocking(Dispatchers.Default) {
        val cases = listOf(CactusSTTMode.RemoteOnly to false, CactusSTTMode.RemoteFirst to true, CactusSTTMode.RemoteFirst to false, CactusSTTMode.LocalFirst to false, CactusSTTMode.LocalFirst to true)
        for ((mode, model) in cases) {
            // The request stays open long enough for a hold raised by mistake to stop the recording.
            val h = Harness(mode, modelInstalled = model) { delay(300); respond(jsonOk("from server"), HttpStatusCode.OK) }
            h.awaitModel()
            h.engine.reply = ""
            h.announceLane()
            val long = async { h.longRecording() }
            withTimeout(5.seconds) { while (h.longRequests == 0) delay(5) }
            h.hybrid.earlyInit()
            assertFalse(h.serverDictation.isHeld, "$mode")
            assertEquals("from server", h.transcribe().text, "$mode model=$model")
            assertEquals(1, h.longRequests, "$mode hung the recording up")
            assertFalse(h.longStopped.isCompleted, "$mode model=$model stopped the recording")
            assertTrue(h.serverDictation.longRecordingOnServer)
            long.cancel()
        }
    }

    /** The hold ends with the dictation, whether it failed on the server or never reached it. */
    @Test
    fun theRecordingIsSentAgainAsSoonAsTheDictationIsOver() = runBlocking(Dispatchers.Default) {
        val dictations = listOf<suspend (Harness) -> Unit>(
            { h -> assertFailsWith<TranscriptionException.TranscriptionServiceError> { h.transcribe() } },
            { h ->
                assertFailsWith<TranscriptionException.NoSpeechDetected> {
                    h.hybrid.transcribe(flowOf(ByteArray(100)), sampleRate = 16_000, language = STTLanguage.Automatic)
                        .filterIsInstance<TranscriptionSessionStatus.Transcription>().first()
                }
            },
        )
        for (dictate in dictations) {
            val h = Harness(CactusSTTMode.RemoteOnly) { respond("", HttpStatusCode.InternalServerError) }
            val long = async { h.longRecording() }
            withTimeout(5.seconds) { while (h.longRequests == 0) delay(5) }
            h.hybrid.earlyInit()
            withTimeout(5.seconds) { h.longStopped.await() }
            dictate(h)
            assertEquals("recording words", withTimeout(5.seconds) { long.await() }, "the hold outlived the dictation")
        }
    }

    @Test
    fun aDictationThatNoLongerNeedsTheServerEndsTheHoldsRaisedAsItsSessionOpened() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.RemoteOnly) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        val long = async { h.longRecording() }
        withTimeout(5.seconds) { while (h.longRequests == 0) delay(5) }
        h.hybrid.earlyInit()
        withTimeout(5.seconds) { h.longStopped.await() }
        h.announceLane()
        val dictation = async { h.transcribe() }
        assertEquals("recording words", withTimeout(5.seconds) { long.await() })
        assertEquals("from server", dictation.await().text)
    }

    @Test
    fun localWithServerFallbackStaysOnThePhoneWhenALongRecordingReachedTheServerDuringTheLocalAttempt() = runBlocking(Dispatchers.Default) {
        val h = Harness(CactusSTTMode.LocalFirst) { respond(jsonOk("from server"), HttpStatusCode.OK) }
        h.awaitModel()
        h.engine.reply = ""
        val gate = CountDownLatch(1)
        h.engine.gate = gate
        val dictation = async { runCatching { h.transcribe() } }
        withTimeout(5.seconds) { while (!h.engine.inRealTranscribe) delay(5) }
        val long = async { h.longRecording() }
        withTimeout(5.seconds) { while (h.longRequests == 0) delay(5) }
        gate.countDown()
        assertTrue(dictation.await().exceptionOrNull() is TranscriptionException.NoSpeechDetected)
        assertTrue(h.requests.isEmpty(), "the dictation went behind the recording")
        long.cancel()
    }
}
