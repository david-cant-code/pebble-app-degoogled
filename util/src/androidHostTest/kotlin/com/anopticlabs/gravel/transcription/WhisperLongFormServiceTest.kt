package com.anopticlabs.gravel.transcription

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.anopticlabs.gravel.whisper.WhisperEngineTimeoutException
import com.anopticlabs.gravel.whisper.WhisperLongForm
import com.anopticlabs.gravel.whisper.WhisperLongFormResult
import com.anopticlabs.gravel.whisper.WhisperMemoryException
import com.anopticlabs.gravel.whisper.WhisperSegment
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.util.transcription.FakeModelProvider
import coredevices.util.transcription.NoOpInferenceBoost
import coredevices.util.transcription.NoopAnalytics
import coredevices.util.transcription.TranscriptionException
import coredevices.util.transcription.WhisperEngine
import coredevices.util.transcription.WhisperTranscriptionService
import coredevices.util.transcription.realPcmBytes
import coredevices.whisper.EnginePlacement
import coredevices.whisper.TranscribeStats
import coredevices.whisper.WhisperEngineUnavailableException
import io.rebble.libpebblecommon.voice.DICTATION_DEADLINE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Host guard for long-form transcription in [WhisperTranscriptionService]:
 * one engine call per recording, dictation's priority (a session opening
 * or a dictation transcribing stops the call, which starts again from the
 * beginning once the dictation is done), and the give-up rules. Real
 * threads and real time, as in the other service tests; the fake engine
 * models whisper's windows at a compressed pace.
 */
class WhisperLongFormServiceTest {

    private companion object {
        const val WINDOW_MILLIS = 30_000L
        const val WINDOW_WORK_MILLIS = 150L
        const val WORD_EVERY_MILLIS = 7_000L
        const val WORD_MILLIS = 2_000L
    }

    private class Recording(val pcm16: ByteArray, val words: List<WhisperSegment>)

    /** [minutes] of a word every [WORD_EVERY_MILLIS], as silence the engine never hears. */
    private fun recording(minutes: Int): Recording {
        val audioMillis = minutes * 60_000L
        val words = generateSequence(1_000L) { it + WORD_EVERY_MILLIS }
            .takeWhile { it + WORD_MILLIS <= audioMillis }
            .mapIndexed { i, start -> WhisperSegment(start, start + WORD_MILLIS, "word ${i + 1}") }
            .toList()
        return Recording(ByteArray((audioMillis * WhisperLongForm.SAMPLE_RATE / 1000 * 2).toInt()), words)
    }

    /**
     * Whisper's long-form loop in miniature: 30 s windows, one per
     * [windowWork] of wall time. An abort is seen only once the window in
     * progress has been computed, as an abort after an encoder pass is, and
     * a stopped call returns nothing, as the shim does.
     */
    private class WindowEngine {
        @Volatile var windowWork = WINDOW_WORK_MILLIS
        @Volatile var words: List<WhisperSegment> = emptyList()
        val calls = AtomicInteger()
        private val canceled = ConcurrentHashMap.newKeySet<Long>()
        @Volatile var inLongForm = false
        @Volatile var windowsFinished = 0
        @Volatile var dictations = 0
        val inits = AtomicInteger()

        /** While set, a canceled call blocks here instead of stopping: an engine that ignores its abort. */
        @Volatile var wedge: CountDownLatch? = null
        @Volatile var failure: Throwable? = null
        @Volatile var failuresLeft = Int.MAX_VALUE
        @Volatile var endedFor: String? = null

        /** While set, a model load blocks here. */
        @Volatile var initGate: CountDownLatch? = null
        val firstLoader = AtomicReference<Thread>()
        val supportedCalls = AtomicInteger()

        /** Set: every model load fails with it, as a model the engine cannot read does. */
        @Volatile var initFailure: Throwable? = null
        val initAttempts = AtomicInteger()

        /** Set: every call answers "stopped" at once, though nothing asked it to stop. */
        @Volatile var stopsUnasked = false

        /** Runs inside every warm-up call. */
        @Volatile var onWarmUp: (() -> Unit)? = null

        /** Set: the next dictation runs this long, then fails with it. */
        @Volatile var dictationFailure: Pair<Long, Throwable>? = null

        /** Set: the next dictation blocks here, as a decode that ignores its abort, then fails. */
        @Volatile var dictationWedge: CountDownLatch? = null

        val engine = object : WhisperEngine {
            override fun supported(): Boolean {
                supportedCalls.incrementAndGet()
                return true
            }

            override fun init(modelPath: String): Long {
                firstLoader.compareAndSet(null, Thread.currentThread())
                initAttempts.incrementAndGet()
                initGate?.await(20, TimeUnit.SECONDS)
                initFailure?.let { throw it }
                return inits.incrementAndGet().toLong()
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
                if (pcm.all { it == 0f }) {
                    onWarmUp?.invoke()
                    return ""
                }
                dictationFailure?.let { (work, failure) ->
                    dictationFailure = null
                    Thread.sleep(work)
                    throw failure
                }
                dictationWedge?.let { wedge ->
                    dictationWedge = null
                    wedge.await(20, TimeUnit.SECONDS)
                    throw RuntimeException("never answered")
                }
                dictations++
                return "hello world"
            }

            override fun transcribeLongForm(
                handle: Long,
                pcm16: ByteArray,
                threads: Int,
                language: String?,
                callId: Long,
                placement: EnginePlacement,
            ): WhisperLongFormResult {
                calls.incrementAndGet()
                failure?.let { if (failuresLeft-- > 0) throw it }
                if (stopsUnasked) return WhisperLongFormResult(emptyList(), complete = false)
                val audioMillis = pcm16.size / 2 * 1000L / WhisperLongForm.SAMPLE_RATE
                inLongForm = true
                try {
                    var at = 0L
                    while (at < audioMillis) {
                        Thread.sleep(windowWork)
                        if (callId in canceled) {
                            wedge?.await(20, TimeUnit.SECONDS)
                            return WhisperLongFormResult(emptyList(), complete = false)
                        }
                        windowsFinished++
                        at += WINDOW_MILLIS
                    }
                    return WhisperLongFormResult(words, complete = true)
                } finally {
                    inLongForm = false
                }
            }

            override fun cancel(callId: Long) {
                canceled += callId
            }

            override fun free(handle: Long) {}

            override fun endProcess(reason: String) {
                endedFor = reason
            }
        }

        fun hear(recording: Recording): ByteArray {
            words = recording.words
            return recording.pcm16
        }
    }

    private val config = MutableStateFlow(
        CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = "model-a")),
    )

    private fun serviceFor(
        fake: WindowEngine,
        holdBound: Duration = 30.seconds,
        unwindBound: Duration = 10.seconds,
        holdWait: Duration = 5.seconds,
        provider: FakeModelProvider = FakeModelProvider(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = WhisperTranscriptionService(
        coreConfigFlow = CoreConfigFlow(config),
        modelProvider = provider,
        analytics = NoopAnalytics,
        inferenceBoost = NoOpInferenceBoost(),
        engine = fake.engine,
        engineUnwindBound = unwindBound,
        dictationHoldBound = holdBound,
        longFormHoldWait = holdWait,
        longFormUnwindBound = unwindBound,
        dispatcher = dispatcher,
        ioDispatcher = ioDispatcher,
    )

    private suspend fun awaitUntil(what: String, within: Duration = 30.seconds, condition: () -> Boolean) {
        try {
            withTimeout(within) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    @Test
    fun aRecordingIsTranscribedInOneCall() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { windowWork = 5 }
        val recording = recording(minutes = 5)
        assertEquals(recording.words, serviceFor(fake).transcribeLongForm(fake.hear(recording)))
        assertEquals(1, fake.calls.get())
    }

    @Test
    fun aDictationSessionMidRecordingMeetsItsDeadlineAndTheRecordingStartsAgain() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine()
        val service = serviceFor(fake)
        val recording = recording(minutes = 5)
        val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
        awaitUntil("two windows finished") { fake.windowsFinished >= 2 }

        val opened = TimeSource.Monotonic.markNow()
        service.earlyInit()
        awaitUntil("the long-form call stopped") { !fake.inLongForm }
        assertTrue(opened.elapsedNow() < 1.seconds, "the call took ${opened.elapsedNow()} to give the engine up")

        // The recording: the long-form must stay off the engine meanwhile.
        delay(WINDOW_WORK_MILLIS * 3)
        assertEquals(1, fake.calls.get(), "the long-form started again during the dictation's recording")

        val asked = TimeSource.Monotonic.markNow()
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        val decodeWait = asked.elapsedNow()
        assertTrue(decodeWait < 2.seconds && decodeWait < DICTATION_DEADLINE, "the dictation waited $decodeWait")

        assertEquals(recording.words, longForm.await())
        assertEquals(2, fake.calls.get())
    }

    @Test
    fun aDictationWithoutASessionStillStopsTheRecording() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine()
        val service = serviceFor(fake)
        val recording = recording(minutes = 5)
        val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
        awaitUntil("a window finished") { fake.windowsFinished >= 1 }

        val asked = TimeSource.Monotonic.markNow()
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        val decodeWait = asked.elapsedNow()
        assertTrue(decodeWait < 2.seconds, "the dictation waited $decodeWait")

        assertEquals(recording.words, longForm.await())
        assertEquals(2, fake.calls.get())
    }

    /** Where local transcription is only a fallback, opening a session does not stop the recording. */
    @Test
    fun aSessionOpeningInARemoteFirstModeLeavesTheRecordingRunning() = runBlocking<Unit>(Dispatchers.Default) {
        config.value = CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.RemoteFirst, modelName = "model-a"))
        val fake = WindowEngine()
        val service = serviceFor(fake)
        val recording = recording(minutes = 2)
        val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
        awaitUntil("a window finished") { fake.windowsFinished >= 1 }
        service.earlyInit()
        assertEquals(recording.words, longForm.await())
        assertEquals(1, fake.calls.get(), "a session served remotely stopped the recording")
    }

    @Test
    fun aSessionThatNeverTranscribesHoldsTheRecordingOnlyUntilItsBound() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine()
        val service = serviceFor(fake, holdBound = 600.milliseconds)
        val recording = recording(minutes = 3)
        val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
        awaitUntil("a window finished") { fake.windowsFinished >= 1 }
        val opened = TimeSource.Monotonic.markNow()
        service.earlyInit()
        awaitUntil("the long-form started again") { fake.calls.get() == 2 }
        assertTrue(opened.elapsedNow() >= 600.milliseconds, "started again after ${opened.elapsedNow()}, inside the hold")
        assertEquals(recording.words, longForm.await())
        assertEquals(0, fake.dictations)
    }

    @Test
    fun aRecordingQueuedDuringADictationSessionWaitsWithoutStarting() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { windowWork = 10 }
        val service = serviceFor(fake)
        service.earlyInit()
        val recording = recording(minutes = 2)
        val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
        delay(300)
        assertEquals(0, fake.calls.get(), "the long-form started inside a dictation session")
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertEquals(recording.words, longForm.await())
        assertEquals(1, fake.calls.get())
    }

    /** A dictation's stop is not a failure: more of them than the retry budget still end in a transcript. */
    @Test
    fun aRecordingStoppedMoreOftenThanItsRetryBudgetStillCompletes() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { windowWork = 20 }
        val service = serviceFor(fake)
        val recording = recording(minutes = 2)
        val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
        val stops = WhisperTranscriptionService.MAX_LONG_FORM_RETRIES + 3
        repeat(stops) {
            awaitUntil("a call running") { fake.inLongForm }
            val finished = fake.windowsFinished
            awaitUntil("a window finished") { fake.windowsFinished > finished }
            service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        }
        assertEquals(recording.words, longForm.await())
        assertEquals(stops + 1, fake.calls.get())
    }

    @Test
    fun aHoldThatNeverEndsGivesUpAsBusy() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine()
        val service = serviceFor(fake, holdBound = 30.seconds, holdWait = 50.milliseconds)
        service.earlyInit()
        assertFailsWith<TranscriptionException.TranscriptionInProgress> {
            service.transcribeLongForm(fake.hear(recording(minutes = 1)))
        }
        assertEquals(0, fake.calls.get())
    }

    @Test
    fun anEngineDeathIsRetriedAndTheRecordingCompletes() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply {
            windowWork = 5
            failure = WhisperEngineUnavailableException("engine process died")
            failuresLeft = 1
        }
        val recording = recording(minutes = 2)
        assertEquals(recording.words, serviceFor(fake).transcribeLongForm(fake.hear(recording)))
        assertEquals(2, fake.calls.get())
        assertEquals(2, fake.inits.get(), "the lost handle was not reloaded")
    }

    @Test
    fun anEngineThatKeepsDyingFailsAsUnavailable() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { failure = WhisperEngineUnavailableException("engine process died") }
        assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> {
            serviceFor(fake).transcribeLongForm(fake.hear(recording(minutes = 1)))
        }
        assertEquals(WhisperTranscriptionService.MAX_LONG_FORM_RETRIES + 1, fake.calls.get())
    }

    /** A call its deadline ended would end the same way again, so it is not retried. */
    @Test
    fun aCallPastItsDeadlineFailsWithoutARetry() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { failure = WhisperEngineTimeoutException("did not answer in time") }
        assertFailsWith<LongFormTimeoutException> {
            serviceFor(fake).transcribeLongForm(fake.hear(recording(minutes = 1)))
        }
        assertEquals(1, fake.calls.get())
    }

    /** An engine that reports a stop nobody requested is ended and counted, not restarted forever. */
    @Test
    fun aStopNothingRequestedEndsTheEngineAndCountsAsALoss() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { stopsUnasked = true }
        withTimeout(10.seconds) {
            assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> {
                serviceFor(fake).transcribeLongForm(fake.hear(recording(minutes = 1)))
            }
        }
        assertEquals(WhisperTranscriptionService.MAX_LONG_FORM_RETRIES + 1, fake.calls.get())
        assertTrue(fake.endedFor.orEmpty().contains("nothing requested"), "unexpected reason: ${fake.endedFor}")
    }

    /** A load job that checked the handle before an unasked stop lost the model does not stand in for a new load. */
    @Test
    fun aLoadThatSawTheModelBeforeAStopNothingRequestedCostsNoRetry() =
        aLoadThatSawTheModelBeforeItWasLostCostsNoRetry { stopsUnasked = true }

    /** As above, for an engine process lost under the call. */
    @Test
    fun aLoadThatSawTheModelBeforeTheEngineWasLostCostsNoRetry() =
        aLoadThatSawTheModelBeforeItWasLostCostsNoRetry { failure = WhisperEngineUnavailableException("engine process died") }

    private fun aLoadThatSawTheModelBeforeItWasLostCostsNoRetry(loss: WindowEngine.() -> Unit) = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply(loss)
        withSecondLoadHeld(fake) {
            val transcription = async(loop) { runCatching { service.transcribeLongForm(fake.hear(recording(minutes = 1))) } }
            startSecondLoad()
            fake.initGate?.countDown()
            awaitUntil("a second engine call while the second load job is held", within = 15.seconds) { fake.calls.get() >= 2 }
            assertTrue(order.held.get(), "the second load job was never held after its check")
            order.release.countDown()
            val result = withTimeout(30.seconds) { transcription.await() }
            assertIs<TranscriptionException.TranscriptionServiceUnavailable>(result.exceptionOrNull())
            assertEquals(WhisperTranscriptionService.MAX_LONG_FORM_RETRIES + 1, fake.calls.get())
            assertEquals(WhisperTranscriptionService.MAX_LONG_FORM_RETRIES + 1, fake.inits.get(), "one load per engine call")
        }
    }

    /** A dictation after one that lost the engine loads the model again while a job that checked before the loss is held. */
    @Test
    fun aDictationAfterALostEngineDoesNotWaitOnALoadThatSawTheModel() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { dictationFailure = 0L to WhisperEngineUnavailableException("engine process died") }
        withSecondLoadHeld(fake) {
            val first = async(loop) { dictate() }
            startSecondLoad()
            fake.initGate?.countDown()
            assertIs<TranscriptionException.TranscriptionServiceUnavailable>(withTimeout(30.seconds) { first.await() }.exceptionOrNull())
            assertTrue(order.held.get(), "the second load job was never held after its check")
            val second = withTimeout(15.seconds) { async(loop) { dictate() }.await() }
            assertEquals("hello world", second.getOrNull(), "the second dictation failed: ${second.exceptionOrNull()}")
            assertEquals(2, fake.inits.get(), "the lost model was not loaded again")
        }
    }

    /** As above, after a Notebook call that ran past its deadline. */
    @Test
    fun aDictationAfterACallPastItsDeadlineDoesNotWaitOnALoadThatSawTheModel() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { failure = WhisperEngineTimeoutException("did not answer in time") }
        withSecondLoadHeld(fake) {
            val transcription = async(loop) { runCatching { service.transcribeLongForm(fake.hear(recording(minutes = 1))) } }
            startSecondLoad()
            fake.initGate?.countDown()
            assertIs<LongFormTimeoutException>(withTimeout(30.seconds) { transcription.await() }.exceptionOrNull())
            assertTrue(order.held.get(), "the second load job was never held after its check")
            val second = withTimeout(15.seconds) { async(loop) { dictate() }.await() }
            assertEquals("hello world", second.getOrNull(), "the second dictation failed: ${second.exceptionOrNull()}")
            assertEquals(2, fake.inits.get(), "the lost model was not loaded again")
        }
    }

    /** As above, after a dictation whose decode ignored its abort past the unwind bound. */
    @Test
    fun aDictationAfterAWedgedDecodeDoesNotWaitOnALoadThatSawTheModel() = runBlocking<Unit>(Dispatchers.Default) {
        val wedge = CountDownLatch(1)
        val fake = WindowEngine().apply { dictationWedge = wedge }
        // The bound also covers the startup load's warm-up, which withSecondLoadHeld holds at the engine.
        withSecondLoadHeld(fake, unwindBound = 5.seconds) {
            try {
                val first = async(loop) { runCatching { service.transcribeLocal(realPcmBytes(), sampleRate = 16_000, timeout = 100.milliseconds) } }
                startSecondLoad()
                fake.initGate?.countDown()
                assertIs<TimeoutCancellationException>(withTimeout(30.seconds) { first.await() }.exceptionOrNull())
                assertTrue(fake.endedFor.orEmpty().contains("ignored its abort"), "the decode was not abandoned: ${fake.endedFor}")
                assertTrue(order.held.get(), "the second load job was never held after its check")
                val second = withTimeout(15.seconds) { async(loop) { dictate() }.await() }
                assertEquals("hello world", second.getOrNull(), "the second dictation failed: ${second.exceptionOrNull()}")
                assertEquals(2, fake.inits.get(), "the lost model was not loaded again")
            } finally {
                wedge.countDown()
            }
        }
    }

    /**
     * The config watcher's load job is recorded before it runs: with the watcher held after starting it until a
     * dictation has lost the engine, the next dictation loads the model again.
     */
    @Test
    fun aLoadJobStartedBeforeALossIsNotWaitedOnAfterIt() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { dictationFailure = 0L to WhisperEngineUnavailableException("engine process died") }
        val io = HoldingDispatcher()
        val dictations = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            withSecondLoadHeld(fake, ioDispatcher = io) {
                val first = async(dictations) { dictate() }
                // On a thread of its own the first dictation is waiting on the startup load once this returns.
                withContext(dictations) {}
                io.holdNextDispatchFrom(withContext(loop) { Thread.currentThread() })
                startSecondLoad()
                assertTrue(io.holding, "the config watcher was not held at the second job's start")
                fake.initGate?.countDown()
                assertIs<TranscriptionException.TranscriptionServiceUnavailable>(withTimeout(30.seconds) { first.await() }.exceptionOrNull())
                assertTrue(order.held.get(), "the second load job was never held after its check")
                assertTrue(io.holding, "the config watcher was let go before the first dictation lost the engine")
                io.release()
                withContext(loop) {}
                val second = withTimeout(15.seconds) { async(dictations) { dictate() }.await() }
                assertEquals("hello world", second.getOrNull(), "the second dictation failed: ${second.exceptionOrNull()}")
            }
        } finally {
            io.close()
            dictations.close()
        }
    }

    /**
     * A service whose startup load waits at the engine. [startSecondLoad] changes the config, so the config watcher
     * starts a second load job, which finds the model loaded once the gate opens and is then held ([SecondLoadOrder]).
     * The service and its config watcher run on [loop], one thread, so an operation started there before
     * [startSecondLoad] that reaches its wait without suspending first is waiting on the startup load before the
     * second job exists.
     */
    private inner class SecondLoad(
        val fake: WindowEngine,
        val service: WhisperTranscriptionService,
        val loop: CoroutineDispatcher,
        val order: SecondLoadOrder,
    ) {
        suspend fun startSecondLoad() {
            val checks = fake.supportedCalls.get()
            config.value = config.value.copy(sttConfig = config.value.sttConfig.copy(spokenLanguage = "en"))
            // The second job asks whether the engine is supported once it is past the config watcher's check.
            awaitUntil("the second load job started") { fake.supportedCalls.get() > checks }
        }

        suspend fun dictate() = runCatching { service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) }
    }

    private suspend fun withSecondLoadHeld(
        fake: WindowEngine,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        unwindBound: Duration = 10.seconds,
        test: suspend SecondLoad.() -> Unit,
    ) {
        val order = SecondLoadOrder(fake)
        val writersBefore = Logger.config.logWriterList
        val loop = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        fake.initGate = CountDownLatch(1)
        fake.onWarmUp = order::firstWarmUp
        Logger.addLogWriter(order)
        try {
            val service = serviceFor(fake, unwindBound = unwindBound, dispatcher = loop, ioDispatcher = ioDispatcher)
            awaitUntil("the startup load reached the engine") { fake.initAttempts.get() == 1 }
            SecondLoad(fake, service, loop, order).test()
        } finally {
            fake.initGate?.countDown()
            order.releaseAll()
            Logger.setLogWriters(writersBefore)
            loop.close()
        }
    }

    /**
     * Orders the startup load (the fake's first loader thread) and the second load job: the startup load waits at its
     * warm-up until the second job, having found the model loaded, reaches its own; the startup load's warm-up then
     * holds the engine until the second is turned away, and the second is held there until [release].
     */
    private class SecondLoadOrder(private val fake: WindowEngine) : LogWriter() {
        val held = AtomicBoolean()
        val release = CountDownLatch(1)
        private val secondAtWarmUp = CountDownLatch(1)
        private val firstInWarmUp = CountDownLatch(1)
        private val secondTurnedAway = CountDownLatch(1)
        @Volatile private var second: Thread? = null

        fun firstWarmUp() {
            if (firstInWarmUp.count == 0L) return
            firstInWarmUp.countDown()
            secondTurnedAway.await(10, TimeUnit.SECONDS)
        }

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            if (tag != "WhisperTranscriptionService") return
            val first = (fake.firstLoader.get() ?: return) == Thread.currentThread()
            when {
                message.startsWith("Warming up") && first -> secondAtWarmUp.await(10, TimeUnit.SECONDS)
                message.startsWith("Warming up") && secondAtWarmUp.count > 0 -> {
                    second = Thread.currentThread()
                    secondAtWarmUp.countDown()
                    firstInWarmUp.await(10, TimeUnit.SECONDS)
                }
                message.startsWith("Skipping warmup") && second == Thread.currentThread() && held.compareAndSet(false, true) -> {
                    secondTurnedAway.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
        }

        fun releaseAll() {
            listOf(release, secondAtWarmUp, firstInWarmUp, secondTurnedAway).forEach { it.countDown() }
        }
    }

    /** Runs blocks on its own threads; the next dispatch from the thread [holdNextDispatchFrom] names then waits there for [release]. */
    private class HoldingDispatcher : CoroutineDispatcher(), AutoCloseable {
        private val threads = Executors.newCachedThreadPool()
        private val released = CountDownLatch(1)
        @Volatile private var holdFrom: Thread? = null
        @Volatile var holding = false
            private set

        fun holdNextDispatchFrom(thread: Thread) {
            holdFrom = thread
        }

        fun release() = released.countDown()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val hold = holdFrom == Thread.currentThread()
            if (hold) {
                holdFrom = null
                holding = true
            }
            threads.execute(block)
            if (hold) {
                released.await(20, TimeUnit.SECONDS)
                holding = false
            }
        }

        override fun close() {
            released.countDown()
            threads.shutdown()
        }
    }

    /** A model that fails to load for a reason a reload does not change is not reloaded again and again. */
    @Test
    fun aModelThatCannotLoadIsNotRetried() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { initFailure = RuntimeException("whisper init failed") }
        val service = serviceFor(fake)
        awaitUntil("the startup load failed") { fake.initAttempts.get() >= 1 }
        val before = fake.initAttempts.get()
        assertFailsWith<TranscriptionException.TranscriptionRequiresDownload> {
            service.transcribeLongForm(fake.hear(recording(minutes = 1)))
        }
        assertTrue(fake.initAttempts.get() - before <= 1, "${fake.initAttempts.get() - before} loads for one recording")
        assertEquals(0, fake.calls.get())
    }

    @Test
    fun aRegionThatCannotBeAllocatedIsNotEnoughMemory() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply { failure = WhisperMemoryException("no region") }
        assertFailsWith<TranscriptionException.NotEnoughMemory> { serviceFor(fake).transcribeLongForm(fake.hear(recording(minutes = 1))) }
    }

    @Test
    fun cancelingTheRecordingStopsTheEngineCall() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine()
        val service = serviceFor(fake)
        val longForm = async { service.transcribeLongForm(fake.hear(recording(minutes = 5))) }
        awaitUntil("a window finished") { fake.windowsFinished >= 1 }
        longForm.cancelAndJoin()
        assertFalse(fake.inLongForm, "the engine call outlived its canceled caller")
        assertTrue(runCatching { longForm.await() }.exceptionOrNull() is CancellationException)
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
    }

    /** The stuck call's process is ended; the dictation runs, and the recording starts again on a fresh handle. */
    @Test
    fun anEngineThatIgnoresAPreemptionEndsItsProcessAndBothFinish() = runBlocking<Unit>(Dispatchers.Default) {
        val wedge = CountDownLatch(1)
        val fake = WindowEngine().apply { this.wedge = wedge }
        val service = serviceFor(fake, unwindBound = 300.milliseconds)
        try {
            val recording = recording(minutes = 3)
            val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
            awaitUntil("a window finished") { fake.windowsFinished >= 1 }
            service.earlyInit()
            awaitUntil("the wedged process ended") { fake.endedFor != null }
            assertTrue(fake.endedFor.orEmpty().contains("ignored its abort"), "unexpected reason: ${fake.endedFor}")
            fake.wedge = null
            assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
            assertEquals(recording.words, longForm.await())
        } finally {
            wedge.countDown()
        }
    }

    /**
     * A model switch queues its load behind the running call; a dictation waiting on that load still
     * stops the call, in a mode where no session hold was raised, instead of timing out behind it.
     */
    @Test
    fun aDictationWaitingForAModelLoadStopsTheRecording() = runBlocking<Unit>(Dispatchers.Default) {
        config.value = CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.RemoteFirst, modelName = "model-a"))
        val fake = WindowEngine().apply { windowWork = 600 }
        val service = serviceFor(fake)
        val recording = recording(minutes = 5)
        val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
        awaitUntil("a window finished") { fake.windowsFinished >= 1 }
        config.value = CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.RemoteFirst, modelName = "model-b"))
        delay(50)
        val asked = TimeSource.Monotonic.markNow()
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        val decodeWait = asked.elapsedNow()
        assertTrue(decodeWait < 2.seconds, "the dictation waited $decodeWait behind the recording")
        assertEquals(recording.words, longForm.await())
    }

    /** A hold raised while the recording waited for its model is honored before the call starts. */
    @Test
    fun aHoldRaisedDuringTheModelLoadKeepsTheCallFromStarting() = runBlocking<Unit>(Dispatchers.Default) {
        val gate = CountDownLatch(1)
        val fake = WindowEngine().apply { initGate = gate }
        val service = serviceFor(fake, holdBound = 800.milliseconds)
        try {
            val recording = recording(minutes = 1)
            val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
            delay(100)
            service.earlyInit()
            gate.countDown()
            delay(300)
            assertEquals(0, fake.calls.get(), "the call started inside the hold")
            assertEquals(recording.words, longForm.await())
            assertEquals(1, fake.calls.get())
        } finally {
            gate.countDown()
        }
    }

    /** A dictation queued behind a call whose engine is abandoned reads a lost engine, not a missing model. */
    @Test
    fun aDictationBehindAnAbandonedCallIsReportedUnavailable() = runBlocking<Unit>(Dispatchers.Default) {
        val wedge = CountDownLatch(1)
        val fake = WindowEngine().apply { this.wedge = wedge }
        val service = serviceFor(fake, unwindBound = 300.milliseconds)
        try {
            val recording = recording(minutes = 3)
            val longForm = async { service.transcribeLongForm(fake.hear(recording)) }
            awaitUntil("a window finished") { fake.windowsFinished >= 1 }
            assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable> {
                service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
            }
            fake.wedge = null
            assertEquals(recording.words, longForm.await())
        } finally {
            wedge.countDown()
        }
    }

    /** A call that finds the handle lost by a dictation's engine death is retried after a reload, not failed. */
    @Test
    fun aRecordingBehindADictationThatLostTheEngineReloadsAndCompletes() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine().apply {
            windowWork = 5
            dictationFailure = 500L to WhisperEngineUnavailableException("engine process died")
        }
        val service = serviceFor(fake, holdBound = 50.milliseconds)
        val recording = recording(minutes = 2)
        val dictation = async { runCatching { service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) } }
        delay(20)
        assertEquals(recording.words, service.transcribeLongForm(fake.hear(recording)))
        assertTrue(dictation.await().isFailure)
        assertTrue(fake.inits.get() >= 2, "the lost handle was not reloaded")
    }

    /** The caller's own timeout ends the recording as a cancellation, not as an unavailable engine. */
    @Test
    fun aCallersTimeoutDuringTheModelLoadIsACancellation() = runBlocking<Unit>(Dispatchers.Default) {
        val gate = CountDownLatch(1)
        val fake = WindowEngine().apply { initGate = gate }
        try {
            val service = serviceFor(fake)
            assertEquals(null, withTimeoutOrNull(300.milliseconds) { service.transcribeLongForm(fake.hear(recording(minutes = 1))) })
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun aModeWithoutLocalTranscriptionIsUnavailableNotAMissingDownload() = runBlocking<Unit>(Dispatchers.Default) {
        for (mode in listOf(CactusSTTMode.RemoteOnly, CactusSTTMode.RebbleOnly, CactusSTTMode.PlatformOnly)) {
            config.value = CoreConfig(sttConfig = STTConfig(mode = mode, modelName = "model-a"))
            val fake = WindowEngine()
            assertFailsWith<TranscriptionException.TranscriptionServiceUnavailable>("in $mode") {
                serviceFor(fake).transcribeLongForm(fake.hear(recording(minutes = 1)))
            }
            assertEquals(0, fake.calls.get(), "in $mode")
        }
    }

    /** What the Notebook checks before it starts a recording's work. */
    @Test
    fun longFormIsAvailableOnlyInALocalModeWithTheModelInstalled() = runBlocking<Unit>(Dispatchers.Default) {
        assertTrue(serviceFor(WindowEngine()).isLongFormAvailable())
        assertFalse(serviceFor(WindowEngine(), provider = FakeModelProvider(installed = false)).isLongFormAvailable())
        config.value = CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.RemoteOnly, modelName = "model-a"))
        assertFalse(serviceFor(WindowEngine()).isLongFormAvailable())
    }

    /** Text generation watches the same hold a watch session raises. */
    @Test
    fun aSessionOpeningHoldsTheEngineForOtherInferenceToo() = runBlocking<Unit>(Dispatchers.Default) {
        val service = serviceFor(WindowEngine())
        assertFalse(service.dictationPriority.isHeld)
        service.earlyInit()
        assertTrue(service.dictationPriority.isHeld)
    }

    @Test
    fun audioOutsideTheBoundIsRefusedBeforeTheEngine() = runBlocking<Unit>(Dispatchers.Default) {
        val fake = WindowEngine()
        val service = serviceFor(fake)
        assertFailsWith<IllegalArgumentException> { service.transcribeLongForm(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { service.transcribeLongForm(ByteArray(3)) }
        assertFailsWith<IllegalArgumentException>("one sample past five minutes") {
            service.transcribeLongForm(ByteArray(2 * (WhisperLongForm.MAX_CALL_SAMPLES + 1)))
        }
        assertEquals(0, fake.calls.get())
    }
}
