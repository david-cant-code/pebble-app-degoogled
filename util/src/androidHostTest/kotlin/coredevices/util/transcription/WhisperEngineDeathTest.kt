package coredevices.util.transcription

import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.whisper.EnginePlacement
import coredevices.whisper.TranscribeStats
import coredevices.whisper.WhisperEngineUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Host regression guard for what [WhisperTranscriptionService] does when
 * the engine process dies, driven through the [WhisperEngine] seam with
 * a fake that models the process: handles belong to a process
 * generation, a call with a handle from a dead generation fails as the
 * real client's does, and a death reaches the service's listener on the
 * thread that observed it. The policy under test: a lost process costs
 * at most the dictation inside it and is followed by one reload, a death
 * during a load is not retried on its own, the reloads are capped until
 * a decode succeeds, and a dictation that finds the engine gone reports
 * it unavailable rather than the model missing.
 */
class WhisperEngineDeathTest {

    private class ProcessEngine {
        private val lock = Any()
        private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()
        private var bound = false
        private var nextHandle = 1L
        private val handleGenerations = HashMap<Long, Long>()

        /** The generation of the most recently bound process, 0 before the first. */
        @Volatile var generation = 0L
            private set

        @Volatile var initCount = 0
        @Volatile var realCalls = 0
        @Volatile var inRealTranscribe = false

        /** A real decode blocks on this while one is set, so a test can act mid-decode. */
        @Volatile var gate: CountDownLatch? = null

        /** The process dies inside every load. */
        @Volatile var dieOnInit = false

        /** The process dies inside the first inference after every load (the warm-up). */
        @Volatile var dieOnWarmUp = false

        val engine = object : WhisperEngine {
            override fun supported(): Boolean = true

            override fun init(modelPath: String): Long {
                synchronized(lock) {
                    initCount++
                    if (!bound) {
                        bound = true
                        generation++
                    }
                }
                if (dieOnInit) {
                    die(report = true)
                    throw WhisperEngineUnavailableException("engine process died during init")
                }
                return synchronized(lock) {
                    val handle = nextHandle++
                    handleGenerations[handle] = generation
                    handle
                }
            }

            override fun transcribe(
                handle: Long,
                pcm: FloatArray,
                threads: Int,
                language: String?,
                callId: Long,
                placement: EnginePlacement,
                stats: TranscribeStats?,
            ): String {
                val warmUp = pcm.all { it == 0f }
                if (!warmUp) {
                    realCalls++
                    inRealTranscribe = true
                }
                try {
                    // Bounded so a deadlocked test fails instead of hanging the run.
                    if (!warmUp) gate?.await(20, TimeUnit.SECONDS)
                    if (warmUp && dieOnWarmUp) {
                        die(report = true)
                        throw WhisperEngineUnavailableException("engine process died during warm-up")
                    }
                    synchronized(lock) {
                        if (!bound || handleGenerations[handle] != generation) {
                            throw WhisperEngineUnavailableException("handle $handle is from a dead engine process")
                        }
                    }
                    return if (warmUp) "" else "hello world"
                } finally {
                    inRealTranscribe = false
                }
            }

            override fun cancel(callId: Long) {}
            override fun free(handle: Long) {}
            override fun processGeneration(): Long = generation
            override fun lastBindMillis(): Long? = 25L.takeIf { generation > 0L }
            override fun addDeathListener(listener: (generation: Long) -> Unit) {
                listeners += listener
            }
        }

        /**
         * The process dies: its handles stop working and, with [report],
         * the listeners hear which generation went, on this thread, as
         * they do from a binder thread or from inside the failing call.
         */
        fun die(report: Boolean) {
            val dead = synchronized(lock) {
                if (!bound) return
                bound = false
                generation
            }
            if (report) reportDeath(dead)
        }

        /** Delivers a death report for [generation], live or stale. */
        fun reportDeath(generation: Long) {
            for (listener in listeners) listener(generation)
        }
    }

    private val config = MutableStateFlow(
        CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = "model-a")),
    )

    private fun serviceFor(fake: ProcessEngine, provider: FakeModelProvider = FakeModelProvider()) = WhisperTranscriptionService(
        coreConfigFlow = CoreConfigFlow(config),
        modelProvider = provider,
        analytics = NoopAnalytics,
        inferenceBoost = NoOpInferenceBoost(),
        engine = fake.engine,
    )

    // Generous bound: every wait returns as soon as its condition holds, so
    // the value only stretches a failing run.
    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        try {
            withTimeout(60.seconds) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    // Long enough for a reload the service should not make to have shown
    // up in initCount; a regression fails, a passing run only waits.
    private suspend fun settle() = delay(300)

    // The in-flight dictations below are expected to fail, and a failing
    // async child would fail the whole runBlocking scope before the
    // assertion sees it, so they run wrapped and are unwrapped at the
    // assertion.

    /** The reload after a death re-hashes the model file; the first load and a decode do not ask for it. */
    @Test
    fun idleDeathReloadsTheModelAtOnce() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine()
        val provider = FakeModelProvider()
        val service = serviceFor(fake, provider)
        awaitUntil("the first load") { service.isModelReady }
        assertEquals(1, fake.initCount)
        assertEquals(emptyList(), provider.forgotten, "a first load keeps the verification memo")

        fake.die(report = true)
        awaitUntil("the reload") { fake.initCount == 2 && service.isModelReady }
        assertEquals(2L, fake.generation, "the reload did not bring up a fresh engine process")
        assertEquals(listOf(service.configuredModel), provider.forgotten, "the reload after a death must re-hash the model")
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        settle()
        assertEquals(2, fake.initCount, "the death was followed by more than one reload")
    }

    @Test
    fun deathUnderADecodeFailsItAndReloads() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine().apply { gate = CountDownLatch(1) }
        val service = serviceFor(fake)
        awaitUntil("the first load") { service.isModelReady }

        val result = async { runCatching { service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) } }
        awaitUntil("the decode inside the engine") { fake.inRealTranscribe }
        fake.die(report = true)
        fake.gate?.countDown()
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> { result.await().getOrThrow() }

        awaitUntil("the reload") { fake.initCount == 2 && service.isModelReady }
        fake.gate = null
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertEquals(2, fake.realCalls)
    }

    /**
     * The decode can observe the death before the report of it arrives
     * (the report runs on a binder thread); it forgets the handle itself,
     * and the late report still triggers the reload.
     */
    @Test
    fun deathReportedAfterTheDecodeFailedStillReloads() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine().apply { gate = CountDownLatch(1) }
        val service = serviceFor(fake)
        awaitUntil("the first load") { service.isModelReady }

        val result = async { runCatching { service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) } }
        awaitUntil("the decode inside the engine") { fake.inRealTranscribe }
        fake.die(report = false)
        fake.gate?.countDown()
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> { result.await().getOrThrow() }
        assertFalse(service.isModelReady, "the handle of a dead engine process was kept")
        settle()
        assertEquals(1, fake.initCount, "a reload ran before any death was reported")

        fake.reportDeath(1L)
        awaitUntil("the reload") { fake.initCount == 2 && service.isModelReady }
    }

    @Test
    fun deathDuringTheLoadIsNotRetriedOnItsOwn() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine().apply { dieOnInit = true }
        val service = serviceFor(fake)
        awaitUntil("the failed first load") { fake.initCount == 1 }
        settle()
        assertEquals(1, fake.initCount, "a load the engine died in was retried without a dictation")
        assertFalse(service.isModelReady)

        // A dictation makes its own attempt, once, and reports the engine
        // unavailable rather than the model missing.
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> {
            service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        }
        settle()
        assertEquals(2, fake.initCount)

        fake.dieOnInit = false
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertEquals(3, fake.initCount)
    }

    @Test
    fun reloadsAreCappedUntilADecodeSucceeds() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine()
        val service = serviceFor(fake)
        awaitUntil("the first load") { service.isModelReady }

        repeat(WhisperTranscriptionService.MAX_PROACTIVE_RELOADS) { death ->
            fake.die(report = true)
            awaitUntil("reload ${death + 1}") { fake.initCount == death + 2 && service.isModelReady }
        }
        // One more death than the cap: the handle is dropped, nothing reloads.
        fake.die(report = true)
        awaitUntil("the handle dropped") { !service.isModelReady }
        settle()
        assertEquals(WhisperTranscriptionService.MAX_PROACTIVE_RELOADS + 1, fake.initCount, "the cap did not hold")

        // The next dictation loads on its own account and, by decoding,
        // starts the count afresh.
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertEquals(WhisperTranscriptionService.MAX_PROACTIVE_RELOADS + 2, fake.initCount)
        fake.die(report = true)
        awaitUntil("a reload after the count reset") {
            fake.initCount == WhisperTranscriptionService.MAX_PROACTIVE_RELOADS + 3 && service.isModelReady
        }
    }

    /**
     * A model whose first inference kills the engine would otherwise be
     * reloaded forever: each reload's warm-up dies and reports a death of
     * the process behind a loaded handle.
     */
    @Test
    fun engineDyingInEveryWarmUpStopsReloadingAtTheCap() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine().apply { dieOnWarmUp = true }
        val service = serviceFor(fake)
        awaitUntil("the capped reloads") { fake.initCount == WhisperTranscriptionService.MAX_PROACTIVE_RELOADS + 1 }
        settle()
        assertEquals(WhisperTranscriptionService.MAX_PROACTIVE_RELOADS + 1, fake.initCount, "the reloads did not stop")
        assertFalse(service.isModelReady)

        // A dictation's own attempt loads once more, dies in the warm-up
        // again, and reports the engine unavailable; still no loop.
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> {
            service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        }
        settle()
        assertEquals(WhisperTranscriptionService.MAX_PROACTIVE_RELOADS + 2, fake.initCount)

        fake.dieOnWarmUp = false
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertTrue(service.isModelReady)
    }

    /**
     * A death while the configured model is no longer installed has
     * nothing to reload, but the dead process's handle still goes: the
     * service must not keep answering that a model is loaded.
     */
    @Test
    fun deathWithTheModelGoneDropsTheHandleAndReloadsNothing() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine()
        val provider = FakeModelProvider()
        val service = serviceFor(fake, provider)
        awaitUntil("the first load") { service.isModelReady }
        assertTrue(service.isLocalAvailable())

        provider.installed = false
        fake.die(report = true)
        awaitUntil("the handle dropped") { !service.isModelReady }
        settle()
        assertEquals(1, fake.initCount, "a reload ran with no model to load")
        assertFalse(service.isLocalAvailable(), "local dictation offered on a dead process")
    }

    /**
     * The engine-unavailable report belongs to the model the load could
     * not reach the engine for; once that model is gone, the missing model
     * is the cause a dictation reports.
     */
    @Test
    fun unreachableEngineIsNotReportedOnceTheModelIsGone() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine().apply { dieOnInit = true }
        val provider = FakeModelProvider()
        val service = serviceFor(fake, provider)
        awaitUntil("the failed first load") { fake.initCount == 1 }
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> {
            service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        }

        provider.installed = false
        assertFailsWith<TranscriptionException.TranscriptionRequiresDownload> {
            service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        }
        Unit
    }

    /** A report for a process already replaced leaves the fresh handle alone. */
    @Test
    fun staleDeathReportLeavesTheFreshHandleAlone() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine()
        val service = serviceFor(fake)
        awaitUntil("the first load") { service.isModelReady }
        fake.die(report = true)
        awaitUntil("the reload") { fake.initCount == 2 && service.isModelReady }

        fake.reportDeath(1L)
        settle()
        assertEquals(2, fake.initCount, "a stale death report caused a reload")
        assertTrue(service.isModelReady, "a stale death report dropped the fresh handle")
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
    }
}
