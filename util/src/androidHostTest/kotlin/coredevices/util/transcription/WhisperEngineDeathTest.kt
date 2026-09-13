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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

        /** Warm-up passes served: the all-zero input. */
        @Volatile var warmUps = 0

        /** A real decode blocks on this while one is set, so a test can act mid-decode. */
        @Volatile var gate: CountDownLatch? = null

        /** A load blocks on this while one is set, once counted, so a test can act mid-load. */
        @Volatile var initGate: CountDownLatch? = null

        /**
         * The support check blocks on this while one is set; the reload
         * after a death makes that check between its two mutex holds, so
         * a test can park it there. [parkedInSupported] counts the callers
         * that have.
         */
        @Volatile var supportedGate: CountDownLatch? = null
        @Volatile var parkedInSupported = 0

        /** The process dies inside every load. */
        @Volatile var dieOnInit = false

        /** The process dies inside the first inference after every load (the warm-up). */
        @Volatile var dieOnWarmUp = false

        /** The reason the service gave for ending the process, once it has. */
        @Volatile var endedFor: String? = null

        val engine = object : WhisperEngine {
            override fun supported(): Boolean {
                supportedGate?.let {
                    parkedInSupported++
                    it.await(20, TimeUnit.SECONDS)
                }
                return true
            }

            override fun init(modelPath: String): Long {
                synchronized(lock) {
                    initCount++
                    if (!bound) {
                        bound = true
                        generation++
                    }
                }
                initGate?.await(20, TimeUnit.SECONDS)
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
                if (warmUp) {
                    warmUps++
                } else {
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

            override fun endProcess(reason: String) {
                endedFor = reason
                die(report = true)
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

    private fun serviceFor(
        fake: ProcessEngine,
        provider: FakeModelProvider = FakeModelProvider(),
        unwindBound: Duration = 10.seconds,
    ) = WhisperTranscriptionService(
        coreConfigFlow = CoreConfigFlow(config),
        modelProvider = provider,
        analytics = NoopAnalytics,
        inferenceBoost = NoOpInferenceBoost(),
        engine = fake.engine,
        engineUnwindBound = unwindBound,
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
     * A decode that ignores its abort past the unwind bound is abandoned
     * together with its process: the service ends the engine process at
     * once and the death report reloads the model into a fresh one, so
     * the stuck call's own deadline has no process left to end under
     * later work.
     */
    @Test
    fun aWedgedDecodeEndsTheEngineProcessAndReloads() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine().apply { gate = CountDownLatch(1) }
        val service = serviceFor(fake, unwindBound = 200.milliseconds)
        awaitUntil("the first load") { service.isModelReady }

        val dictation = async { runCatching { service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) } }
        awaitUntil("the decode inside the engine") { fake.inRealTranscribe }
        dictation.cancel()
        awaitUntil("the wedged process ended") { fake.endedFor != null }
        assertTrue(fake.endedFor.orEmpty().contains("ignored its abort"), "unexpected reason: ${fake.endedFor}")
        awaitUntil("the reload") { fake.initCount == 2 && service.isModelReady }
        assertEquals(2L, fake.generation, "the reload did not bring up a fresh engine process")

        // The stuck call is released only now, into a process that is gone.
        fake.gate?.countDown()
        fake.gate = null
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        settle()
        assertEquals(2, fake.initCount, "the wedge was followed by more than one reload")
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

    /** A death during the load drops the verification memo too, so the next attempt re-hashes the file it died on. */
    @Test
    fun deathDuringTheLoadIsNotRetriedOnItsOwn() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine().apply { dieOnInit = true }
        val provider = FakeModelProvider()
        val service = serviceFor(fake, provider)
        awaitUntil("the failed first load") { fake.initCount == 1 }
        settle()
        assertEquals(1, fake.initCount, "a load the engine died in was retried without a dictation")
        assertFalse(service.isModelReady)
        assertEquals(listOf(service.configuredModel), provider.forgotten, "a death during the load must drop the verification memo")

        // A dictation makes its own attempt, once, and reports the engine
        // unavailable rather than the model missing.
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> {
            service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        }
        settle()
        assertEquals(2, fake.initCount)
        assertEquals(2, provider.forgotten.size, "the dictation's own load died without dropping the memo")

        fake.dieOnInit = false
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertEquals(3, fake.initCount)
    }

    /**
     * A dictation that arrives while the reload after a death is inside
     * the engine starts its own init job, which queues on the mutex and
     * finds the model resident; the reloaded context still gets its
     * warm-up before the dictation decodes on it.
     */
    @Test
    fun aDictationArrivingMidReloadStillGetsTheWarmUp() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine()
        val service = serviceFor(fake)
        awaitUntil("the first load and its warm-up") { service.isModelReady && fake.warmUps == 1 }

        fake.initGate = CountDownLatch(1)
        fake.die(report = true)
        awaitUntil("the reload inside the engine") { fake.initCount == 2 }
        val dictation = async { runCatching { service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) } }
        // The dictation's own init job queues on the mutex behind the reload.
        settle()
        fake.initGate?.countDown()
        fake.initGate = null
        assertEquals("hello world", dictation.await().getOrThrow())
        assertEquals(2, fake.warmUps, "the reloaded context met its first dictation without a warm-up")
        assertEquals(2, fake.initCount)
    }

    /**
     * A load that takes the mutex between the two holds of the reload
     * after a death (a dictation's own, here) re-hashes the file, since
     * the death itself dropped the memo, and leaves the reload nothing to
     * do and no reload budget to spend.
     */
    @Test
    fun aLoadThatBeatsTheReloadReHashesAndSpendsNoBudget() = runBlocking(Dispatchers.Default) {
        val fake = ProcessEngine()
        val provider = FakeModelProvider()
        val service = serviceFor(fake, provider)
        awaitUntil("the first load") { service.isModelReady }

        val gap = CountDownLatch(1)
        fake.supportedGate = gap
        fake.die(report = true)
        assertEquals(listOf(service.configuredModel), provider.forgotten, "the death itself must drop the verification memo")
        awaitUntil("the reload parked between its mutex holds") { fake.parkedInSupported == 1 }
        fake.supportedGate = null

        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertEquals(2, fake.initCount, "the dictation did not load on its own account")
        gap.countDown()
        settle()
        assertEquals(2, fake.initCount, "the reload loaded over the dictation's handle")
        assertEquals(2, fake.warmUps, "the dictation's load was not warmed up exactly once")

        // The reload spent nothing: every reload of the budget is still there.
        repeat(WhisperTranscriptionService.MAX_PROACTIVE_RELOADS) { death ->
            fake.die(report = true)
            awaitUntil("reload ${death + 1} after the gap") { fake.initCount == death + 3 && service.isModelReady }
        }
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
