package coredevices.util.transcription

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.whisper.EnginePlacement
import coredevices.whisper.TranscribeStats
import coredevices.whisper.WhisperEngineUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the wiring behind the cold-path diagnostics in
 * [WhisperTranscriptionService]: every cold model load writes exactly one
 * `dictation coldpath:` line carrying what each term cost, the engine
 * line's `initWaitMs` is the time the dictation really blocked on that
 * load, and a wait that hits its ceiling is reported before it fails the
 * dictation. Driven through the engine and provider seams with scripted
 * delays and gates, and read back through a Kermit writer, because the
 * lines are what a reporter's log zip carries. Every timing assertion is
 * a lower bound: a scripted delay guarantees at least that much elapsed,
 * and nothing bounds a loaded runner from above.
 */
class WhisperColdPathDiagnosticsTest {

    /** Keeps the service's own lines; other tags are somebody else's. */
    private class CapturingLogWriter : LogWriter() {
        val lines = CopyOnWriteArrayList<String>()

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            if (tag == "WhisperTranscriptionService") lines += message
        }

        fun coldPathLines(): List<String> = lines.filter { it.startsWith("dictation coldpath:") }
        fun engineLines(): List<String> = lines.filter { it.startsWith("dictation engine:") }
    }

    /** A provider whose path resolve takes at least [pathMillis], standing in for the re-hash. */
    private class SlowProvider(private val pathMillis: Long) : CactusModelPathProvider {
        override suspend fun getSTTModelPath(): String = error("unused in these tests")
        override suspend fun getLMModelPath(): String = error("unused in these tests")
        override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String {
            delay(pathMillis)
            return "/fake/$modelId"
        }
        override fun isModelDownloaded(modelName: String): Boolean = true
        override fun getDownloadedModels(): List<String> = emptyList()
        override fun getIncompatibleModels(): List<String> = emptyList()
        override fun deleteModel(modelName: String) {}
        override fun getModelSizeBytes(modelName: String): Long = 0L
        override fun initTelemetry() {}
    }

    /**
     * An engine whose init takes at least [initMillis], then waits on
     * [initGate] while one is set, then fails when [failInit] is set. A
     * transcribe of silent PCM (the warm-up input) takes at least
     * [warmUpMillis]; one of real audio loses the engine process while
     * [failDecode] is set. With [bindMillis] set it models an engine
     * process: the first init binds one (generation 1, the bind cost
     * reported as the real client does), later inits find it bound, and
     * its placement facts are the engine's while it is bound.
     */
    private class ScriptedEngine(
        private val initMillis: Long = 0,
        private val bindMillis: Long? = null,
        private val warmUpMillis: Long = 0,
    ) : WhisperEngine {
        @Volatile var initGate: CountDownLatch? = null
        @Volatile var failInit = false
        @Volatile var engineUnreachable = false
        @Volatile var failDecode = false
        @Volatile private var generation = 0L

        override fun supported(): Boolean = true

        override fun init(modelPath: String): Long {
            if (initMillis > 0) Thread.sleep(initMillis)
            // Bounded so a deadlocked test fails instead of hanging the run.
            initGate?.await(20, TimeUnit.SECONDS)
            if (failInit) throw RuntimeException("whisper init failed: scripted")
            if (engineUnreachable) throw WhisperEngineUnavailableException("the engine process did not connect")
            if (bindMillis != null && generation == 0L) generation = 1L
            return 1L
        }

        override fun processGeneration(): Long = generation
        override fun lastBindMillis(): Long? = bindMillis.takeIf { generation > 0L }
        override fun runtimeSnapshot(): EngineRuntimeSnapshot? = ENGINE_FACTS.takeIf { generation > 0L }

        override fun transcribe(
            handle: Long,
            pcm: FloatArray,
            threads: Int,
            language: String?,
            callId: Long,
            placement: EnginePlacement,
            stats: TranscribeStats?,
        ): String {
            if (pcm.all { it == 0f }) {
                if (warmUpMillis > 0) Thread.sleep(warmUpMillis)
                return ""
            }
            if (failDecode) throw WhisperEngineUnavailableException("engine process died during transcribe")
            return "hello world"
        }

        override fun cancel(callId: Long) {}
        override fun free(handle: Long) {}
    }

    private companion object {
        /** What a bound engine process reports about itself in the scripted engine. */
        val ENGINE_FACTS = EngineRuntimeSnapshot(
            allowedCpus = 6, cpuset = "/engine-cpuset", importance = null, oomScoreAdj = 1,
            process = EngineRuntimeSnapshot.PROCESS_ENGINE,
        )
    }

    private val capture = CapturingLogWriter()
    private lateinit var writersBefore: List<LogWriter>

    private var config = MutableStateFlow(
        CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = "model-a")),
    )

    @BeforeTest
    fun captureServiceLog() {
        writersBefore = Logger.config.logWriterList
        Logger.addLogWriter(capture)
    }

    @AfterTest
    fun restoreLog() {
        Logger.setLogWriters(writersBefore)
    }

    private fun serviceFor(engine: WhisperEngine, provider: CactusModelPathProvider) =
        WhisperTranscriptionService(
            coreConfigFlow = CoreConfigFlow(config),
            modelProvider = provider,
            analytics = NoopAnalytics,
            inferenceBoost = NoOpInferenceBoost(),
            engine = engine,
        )

    private fun field(line: String, key: String): String =
        Regex(" $key=(\\S+)").find(line)?.groupValues?.get(1) ?: fail("no $key in '$line'")

    // Generous bound: every wait returns as soon as its condition holds, so
    // the value only stretches a failing run.
    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        try {
            withTimeout(60.seconds) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    @Test
    fun coldLoadWritesOneLineWithEveryTerm() = runBlocking(Dispatchers.Default) {
        val service = serviceFor(ScriptedEngine(initMillis = 30, warmUpMillis = 25), SlowProvider(pathMillis = 20))
        awaitUntil("the coldpath line of the construction-time load") { capture.coldPathLines().isNotEmpty() }

        val line = capture.coldPathLines().single()
        assertEquals("model-a", field(line, "model"))
        assertTrue(field(line, "modelPathMs").toLong() >= 20, line)
        assertTrue(field(line, "engineInitMs").toLong() >= 30, line)
        assertTrue(field(line, "warmUpMs").toLong() >= 25, line)
        assertEquals("ok", field(line, "outcome"))

        // A dictation on the resident model re-kicks nothing: still one
        // cold load, and its engine line carries a numeric wait of its own.
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertEquals(1, capture.coldPathLines().size, capture.lines.toString())
        val engineLine = capture.engineLines().single()
        assertNotNull(field(engineLine, "initWaitMs").toLongOrNull(), engineLine)
        Unit
    }

    @Test
    fun engineLineReportsTheTimeBlockedOnTheLoad() = runBlocking(Dispatchers.Default) {
        val engine = ScriptedEngine().apply { initGate = CountDownLatch(1) }
        val service = serviceFor(engine, SlowProvider(pathMillis = 0))
        try {
            // Unconfined runs the dictation on this thread up to its first
            // suspension, which is the wait for the held load, so the wait
            // clock is running before the release below is timed.
            val result = async(Dispatchers.Unconfined) {
                service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
            }
            delay(150)
            engine.initGate?.countDown()
            assertEquals("hello world", result.await())

            val line = capture.engineLines().single()
            assertTrue(field(line, "initWaitMs").toLong() >= 150, line)
            assertEquals("ok", field(line, "outcome"))
        } finally {
            // The released load logs its coldpath line on completion; let
            // it land here rather than in the next test's capture.
            engine.initGate?.countDown()
            awaitUntil("the released load to settle") { capture.coldPathLines().isNotEmpty() }
        }
    }

    /** A decode that loses the engine process names the stable token on its engine line. */
    @Test
    fun decodeThatLosesTheEngineIsNamedOnTheEngineLine() = runBlocking(Dispatchers.Default) {
        val engine = ScriptedEngine()
        val service = serviceFor(engine, SlowProvider(pathMillis = 0))
        awaitUntil("the coldpath line") { capture.coldPathLines().isNotEmpty() }
        engine.failDecode = true
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> {
            service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        }
        assertEquals("error:engine_unavailable", field(capture.engineLines().single(), "outcome"))
    }

    /**
     * The first load brings the engine process up, so its line carries
     * the bind cost inside the init term; a later load against the
     * process already bound (a model switch) pays no bind. Both lines
     * name the process their placement facts describe: this one before
     * the first bind, when there is no other, and the engine's once it is
     * bound, as does the engine line of a decode.
     */
    @Test
    fun linesSplitOutTheBindAndNameTheProcess() = runBlocking(Dispatchers.Default) {
        val service = serviceFor(ScriptedEngine(initMillis = 30, bindMillis = 40), SlowProvider(pathMillis = 0))
        awaitUntil("the coldpath line of the first load") { capture.coldPathLines().isNotEmpty() }
        val first = capture.coldPathLines().single()
        assertEquals("40", field(first, "bindMs"), first)
        assertTrue(field(first, "engineInitMs").toLong() >= 30, first)
        assertEquals("host", field(first, "proc"), first)

        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        val engineLine = capture.engineLines().single()
        assertEquals("engine", field(engineLine, "proc"), engineLine)
        assertEquals("6", field(engineLine, "allowedCpus"), engineLine)
        assertEquals("/engine-cpuset", field(engineLine, "cpuset"), engineLine)

        config.value = CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = "model-b"))
        awaitUntil("the coldpath line of the switch") { capture.coldPathLines().size == 2 }
        val switch = capture.coldPathLines()[1]
        assertEquals("model-b", field(switch, "model"), switch)
        assertEquals("0", field(switch, "bindMs"), switch)
        assertEquals("engine", field(switch, "proc"), switch)
    }

    /** An engine with no process of its own (the default seam answers) reports no bind and this process. */
    @Test
    fun engineWithoutAProcessReportsNoBind() = runBlocking(Dispatchers.Default) {
        val service = serviceFor(ScriptedEngine(), SlowProvider(pathMillis = 0))
        awaitUntil("the coldpath line") { capture.coldPathLines().isNotEmpty() }
        val line = capture.coldPathLines().single()
        assertEquals("0", field(line, "bindMs"), line)
        assertEquals("host", field(line, "proc"), line)
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertEquals(1, capture.engineLines().size, capture.lines.toString())
        assertEquals("host", field(capture.engineLines().single(), "proc"))
    }

    @Test
    fun loadThatFailsStillReportsThePaidTerms() = runBlocking(Dispatchers.Default) {
        val engine = ScriptedEngine().apply { failInit = true }
        serviceFor(engine, SlowProvider(pathMillis = 20))
        awaitUntil("the coldpath line of the failed load") { capture.coldPathLines().isNotEmpty() }

        val line = capture.coldPathLines().single()
        assertTrue(field(line, "modelPathMs").toLong() >= 20, line)
        assertEquals("?", field(line, "engineInitMs"), line)
        assertEquals("?", field(line, "warmUpMs"), line)
        assertEquals("error:RuntimeException", field(line, "outcome"), line)
    }

    /** A load that could not reach the engine process reports the stable token, not a class name. */
    @Test
    fun unreachableEngineIsNamedOnTheColdPathLine() = runBlocking(Dispatchers.Default) {
        serviceFor(ScriptedEngine().apply { engineUnreachable = true }, SlowProvider(pathMillis = 0))
        awaitUntil("the coldpath line of the failed load") { capture.coldPathLines().isNotEmpty() }
        assertEquals("error:engine_unavailable", field(capture.coldPathLines().single(), "outcome"))
    }

    @Test
    fun waitThatHitsItsCeilingIsReportedAndFailsTheDictation() = runBlocking(Dispatchers.Default) {
        val engine = ScriptedEngine().apply { initGate = CountDownLatch(1) }
        val service = serviceFor(engine, SlowProvider(pathMillis = 0))
        try {
            try {
                service.transcribeLocal(realPcmBytes(), sampleRate = 16_000, initTimeout = 100.milliseconds)
                fail("a dictation whose model never came up returned a result")
            } catch (_: TimeoutCancellationException) {
                // The dictation fails; the ceiling is what the log must say.
            }
            assertTrue(
                capture.lines.any { it.startsWith("Whisper STT model still initializing after") },
                capture.lines.toString(),
            )
            assertTrue(capture.engineLines().isEmpty(), "no engine ran, so no engine line")
        } finally {
            // Release the load and let it finish before returning: it
            // logs its coldpath line on completion, and a line logged
            // after this test ends lands in the next test's capture.
            engine.initGate?.countDown()
            awaitUntil("the released load to settle") { capture.coldPathLines().isNotEmpty() }
        }
    }
}
