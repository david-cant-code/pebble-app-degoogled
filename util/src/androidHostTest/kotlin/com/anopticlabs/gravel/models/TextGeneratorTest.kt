package com.anopticlabs.gravel.models

import com.anopticlabs.gravel.llm.ChatMessage
import com.anopticlabs.gravel.llm.LlmEngineLimits
import com.anopticlabs.gravel.llm.LlmEngineTimeoutException
import com.anopticlabs.gravel.llm.LlmEngineUnavailableException
import com.anopticlabs.gravel.llm.LlmGenerateStats
import com.anopticlabs.gravel.transcription.DictationHold
import com.anopticlabs.gravel.transcription.DictationPriority
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.transcription.CactusModelPathProvider
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TextGeneratorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val large = LlmModelCatalog.MODELS.maxBy { it.sizeBytes }
    private val small = LlmModelCatalog.MODELS.minBy { it.sizeBytes }

    @AfterTest
    fun tearDown() = scope.cancel()

    private class FakeProvider(var installed: Set<String>, var incompatible: Set<String> = emptySet()) : CactusModelPathProvider {
        var verifyFails = false
        val forgotten = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun getSTTModelPath(): String = error("unused")
        override suspend fun getLMModelPath(): String = error("unused")
        override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String {
            check(!allowReinstall) { "the generator must never download" }
            if (verifyFails) throw SecurityException("failed load-time verification")
            return "/models/$modelId"
        }
        override fun forgetLoadVerification(modelId: String) {
            forgotten += modelId
        }
        override fun isModelDownloaded(modelName: String): Boolean = error("speech only")
        override fun getDownloadedModels(): List<String> = installed.toList()
        override fun getIncompatibleModels(): List<String> = incompatible.toList()
        override fun deleteModel(modelName: String) = Unit
        override fun getModelSizeBytes(modelName: String): Long = 0L
        override fun initTelemetry() = Unit
    }

    private class FakeEngine : TextEngineCalls {
        var supported = true
        @Volatile var generation = 1L
        val inits = Collections.synchronizedList(mutableListOf<String>())
        val freed = Collections.synchronizedList(mutableListOf<Long>())
        val prompts = Collections.synchronizedList(mutableListOf<String>())
        val formatted = Collections.synchronizedList(mutableListOf<List<ChatMessage>>())
        val maxTokens = Collections.synchronizedList(mutableListOf<Int>())
        val cancels = Collections.synchronizedList(mutableListOf<Long>())
        val calls = AtomicInteger()
        private val nextHandle = AtomicInteger(100)
        private var deathListener: ((Long) -> Unit)? = null

        // Consulted per generate call; the default answers at once.
        @Volatile var onGenerate: (call: Int, callId: Long, stats: LlmGenerateStats) -> String = { _, _, _ -> "reply" }
        @Volatile var canceled: CountDownLatch? = null
        @Volatile var ended: String? = null

        // Consulted per init call before it answers; the default loads.
        @Volatile var onInit: (attempt: Int) -> Unit = {}
        val initAttempts = AtomicInteger()
        val callIds = Collections.synchronizedList(mutableListOf<Long>())

        override fun supported() = supported
        override fun init(modelPath: String, contextTokens: Int): Long {
            assertEquals(TextGenerator.CONTEXT_TOKENS, contextTokens)
            onInit(initAttempts.incrementAndGet())
            inits += modelPath
            return nextHandle.incrementAndGet().toLong()
        }
        override fun formatChat(handle: Long, messages: List<ChatMessage>): String {
            formatted += messages
            return messages.joinToString("|") { "${it.role}:${it.content}" }
        }
        override fun generate(handle: Long, prompt: String, maxTokens: Int, threads: Int, callId: Long, stats: LlmGenerateStats): String {
            prompts += prompt
            this.maxTokens += maxTokens
            callIds += callId
            val call = calls.incrementAndGet()
            // As the shim does: a cancel that arrived before the call stops it at its start.
            if (callId in cancels) throw RuntimeException("llm generate failed: generation canceled before it started")
            return onGenerate(call, callId, stats)
        }
        override fun cancel(callId: Long) {
            cancels += callId
            canceled?.countDown()
        }
        override fun free(handle: Long) {
            freed += handle
        }
        override fun processGeneration(): Long = generation
        override fun addDeathListener(listener: (generation: Long) -> Unit) {
            deathListener = listener
        }
        override fun endProcess(reason: String) {
            ended = reason
        }

        fun die() {
            val dead = generation
            generation++
            deathListener?.invoke(dead)
        }
    }

    private fun generator(
        provider: FakeProvider,
        engine: FakeEngine,
        holds: DictationHold = DictationHold(scope, 30.seconds),
        selected: String? = null,
        recommended: LlmModel? = null,
        exclusion: InferenceExclusion = InferenceExclusion(),
        idleTimeout: Duration = 60.seconds,
        holdWait: Duration = 5.seconds,
        threads: () -> Int = { 2 },
    ) = TextGenerator(
        provider = provider,
        selectedModelId = { selected },
        recommendedModel = { recommended },
        dictation = holds,
        exclusion = exclusion,
        engine = engine,
        threads = threads,
        scope = scope,
        idleTimeout = idleTimeout,
        holdWait = holdWait,
        unwindBound = 2.seconds,
    )

    @Test
    fun theChosenModelWinsWhenInstalled() {
        assertEquals(small, pickTextModel(listOf(large, small), small.id, recommended = large))
    }

    @Test
    fun theRecommendedModelStandsInForAChoiceThatIsNotInstalled() {
        assertEquals(small, pickTextModel(listOf(large, small), "gone", recommended = small))
    }

    @Test
    fun withoutAnInstalledChoiceOrRecommendationTheLargestInstalledIsPicked() {
        assertEquals(large, pickTextModel(listOf(small, large), null, recommended = null))
        assertEquals(small, pickTextModel(listOf(small), null, recommended = large))
        assertNull(pickTextModel(emptyList(), small.id, recommended = small))
    }

    /** Only text models are ever candidates, whatever the setting names. */
    @Test
    fun aSpeechModelNamedInTheSettingIsNeverLoaded() = runBlocking {
        val speech = WhisperModelCatalog.MODELS.first()
        val engine = FakeEngine()
        val provider = FakeProvider(installed = setOf(speech.id))
        assertFailsWith<TextModelUnavailableException> { generator(provider, engine, selected = speech.id).generate("s", "u", 16) }
        assertTrue(engine.inits.isEmpty())
    }

    @Test
    fun aTornInstallDoesNotCount() = runBlocking {
        val provider = FakeProvider(installed = setOf(large.id, small.id), incompatible = setOf(large.id))
        assertEquals(small, generator(provider, FakeEngine()).installedModel())
    }

    @Test
    fun aGenerationTrimsItsContentsAppendsTheModelsSuffixAndClampsItsTokens() = runBlocking {
        val engine = FakeEngine()
        val gen = generator(FakeProvider(setOf(small.id)), engine)
        assertEquals("reply", gen.generate("  system \n", "\n user  ", LlmEngineLimits.MAX_TOKENS + 5))
        assertEquals(listOf(ChatMessage("system", "system"), ChatMessage("user", "user")), engine.formatted.single())
        assertEquals("system:system|user:user" + small.promptSuffix, engine.prompts.single())
        assertEquals(LlmEngineLimits.MAX_TOKENS, engine.maxTokens.single())
        assertEquals(listOf("/models/${small.id}"), engine.inits)
    }

    @Test
    fun theModelIsLoadedOnceForConsecutiveGenerations() = runBlocking {
        val engine = FakeEngine()
        val gen = generator(FakeProvider(setOf(small.id)), engine)
        gen.prepare()
        gen.generate("s", "u", 16)
        gen.generate("s", "u", 16)
        assertEquals(1, engine.inits.size)
    }

    @Test
    fun aChangedChoiceFreesTheOldModelBeforeLoadingTheNew() = runBlocking {
        val engine = FakeEngine()
        var selected: String? = small.id
        val gen = TextGenerator(
            FakeProvider(setOf(small.id, large.id)), { selected }, { null }, DictationHold(scope, 30.seconds),
            InferenceExclusion(), engine, { 2 }, scope,
        )
        gen.generate("s", "u", 16)
        selected = large.id
        gen.generate("s", "u", 16)
        assertEquals(listOf("/models/${small.id}", "/models/${large.id}"), engine.inits)
        assertEquals(1, engine.freed.size)
    }

    @Test
    fun noInstalledModelIsUnavailableAndLoadsNothing() = runBlocking {
        val engine = FakeEngine()
        assertFailsWith<TextModelUnavailableException> { generator(FakeProvider(emptySet()), engine).generate("s", "u", 16) }
        assertTrue(engine.inits.isEmpty())
    }

    @Test
    fun aCpuBelowTheEnginesFloorIsUnavailable() = runBlocking {
        val engine = FakeEngine().apply { supported = false }
        assertFailsWith<TextModelUnavailableException> { generator(FakeProvider(setOf(small.id)), engine).generate("s", "u", 16) }
        assertTrue(engine.inits.isEmpty())
    }

    @Test
    fun aModelThatFailsItsCheckIsUnavailableAndNeverReachesTheEngine() = runBlocking {
        val engine = FakeEngine()
        val provider = FakeProvider(setOf(small.id)).apply { verifyFails = true }
        assertFailsWith<TextModelUnavailableException> { generator(provider, engine).generate("s", "u", 16) }
        assertTrue(engine.inits.isEmpty())
    }

    /** The handle went with its process, and the file is hashed again before the reload. */
    @Test
    fun anEngineDeathReloadsTheModelAfterAFreshCheck() = runBlocking {
        val engine = FakeEngine()
        val provider = FakeProvider(setOf(small.id))
        val gen = generator(provider, engine)
        gen.generate("s", "u", 16)
        engine.die()
        assertEquals(listOf(small.id), provider.forgotten)
        gen.generate("s", "u", 16)
        assertEquals(2, engine.inits.size)
    }

    @Test
    fun aLostEngineIsRetriedAndThenReported() = runBlocking {
        val engine = FakeEngine().apply { onGenerate = { _, _, _ -> throw LlmEngineUnavailableException("died") } }
        val provider = FakeProvider(setOf(small.id))
        assertFailsWith<LlmEngineUnavailableException> { generator(provider, engine).generate("s", "u", 16) }
        assertEquals(TextGenerator.MAX_RETRIES + 1, engine.calls.get())
        assertEquals(TextGenerator.MAX_RETRIES + 1, engine.inits.size)
    }

    @Test
    fun aLossFollowedByASuccessAnswers() = runBlocking {
        val engine = FakeEngine().apply {
            onGenerate = { call, _, _ -> if (call == 1) throw LlmEngineUnavailableException("died") else "second" }
        }
        assertEquals("second", generator(FakeProvider(setOf(small.id)), engine).generate("s", "u", 16))
    }

    @Test
    fun aPromptTheContextCannotHoldIsReportedAsTooLong() = runBlocking {
        val engine = FakeEngine().apply {
            onGenerate = { _, _, stats ->
                stats.promptTokens = TextGenerator.CONTEXT_TOKENS + 1
                throw RuntimeException("llm generate failed: prompt does not fit")
            }
        }
        assertFailsWith<TextPromptTooLongException> { generator(FakeProvider(setOf(small.id)), engine).generate("s", "u", 16) }
        assertEquals(1, engine.calls.get())
    }

    @Test
    fun anEngineFailureOfAnotherKindIsNotRetriedHere() = runBlocking {
        val engine = FakeEngine().apply { onGenerate = { _, _, _ -> throw RuntimeException("llm generate failed: decode") } }
        val failure = assertFailsWith<RuntimeException> { generator(FakeProvider(setOf(small.id)), engine).generate("s", "u", 16) }
        assertEquals("llm generate failed: decode", failure.message)
        assertEquals(1, engine.calls.get())
    }

    @Test
    fun aGenerationWaitsWhileADictationHoldsTheEngine() = runBlocking(Dispatchers.Default) {
        val engine = FakeEngine()
        val holds = DictationHold(scope, 30.seconds)
        val hold = holds.raise()
        val pending = async { generator(FakeProvider(setOf(small.id)), engine, holds).generate("s", "u", 16) }
        delay(300)
        assertEquals(0, engine.calls.get(), "generated beside a dictation")
        holds.release(hold)
        assertEquals("reply", withTimeout(5.seconds) { pending.await() })
    }

    /** A dictation raised mid-call stops the call, which then starts again and answers; the restart is not a retry. */
    @Test
    fun aDictationRaisedMidCallStopsItAndItStartsAgain() = runBlocking(Dispatchers.Default) {
        val holds = DictationHold(scope, 30.seconds)
        val started = CountDownLatch(1)
        val engine = FakeEngine()
        engine.canceled = CountDownLatch(1)
        engine.onGenerate = { call, _, _ ->
            if (call == 1) {
                started.countDown()
                assertTrue(engine.canceled!!.await(5, TimeUnit.SECONDS), "the call was never canceled")
                throw RuntimeException("llm generate failed: canceled")
            }
            "after the dictation"
        }
        val pending = async { generator(FakeProvider(setOf(small.id)), engine, holds).generate("s", "u", 16) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val hold = holds.raise()
        delay(200)
        assertEquals(1, engine.cancels.size)
        assertEquals(1, engine.calls.get(), "restarted while the dictation still held the engine")
        holds.release(hold)
        assertEquals("after the dictation", withTimeout(5.seconds) { pending.await() })
        assertEquals(2, engine.calls.get())
    }

    @Test
    fun aHoldThatOutlastsEveryWaitFailsTheGeneration() = runBlocking(Dispatchers.Default) {
        val engine = FakeEngine()
        val holds = DictationHold(scope, 30.seconds)
        holds.raise()
        assertFailsWith<TextEngineHeldException> {
            generator(FakeProvider(setOf(small.id)), engine, holds, holdWait = 50.milliseconds).generate("s", "u", 16)
        }
        assertEquals(0, engine.calls.get())
    }

    /** The speed probe takes the same exclusion, so it cannot time the CPU under a generation. */
    @Test
    fun theExclusionIsHeldForTheWholeGeneration() = runBlocking(Dispatchers.Default) {
        val exclusion = InferenceExclusion()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val engine = FakeEngine().apply {
            onGenerate = { _, _, _ ->
                started.countDown()
                finish.await(5, TimeUnit.SECONDS)
                "done"
            }
        }
        val pending = async { generator(FakeProvider(setOf(small.id)), engine, exclusion = exclusion).generate("s", "u", 16) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertNull(withTimeoutOrNull(200.milliseconds) { exclusion.withLock { } }, "the exclusion was free during a generation")
        finish.countDown()
        assertEquals("done", pending.await())
        withTimeout(1.seconds) { exclusion.withLock { } }
    }

    @Test
    fun anIdleModelIsFreedAndReloadedOnTheNextUse() = runBlocking(Dispatchers.Default) {
        val engine = FakeEngine()
        val gen = generator(FakeProvider(setOf(small.id)), engine, idleTimeout = 100.milliseconds)
        gen.generate("s", "u", 16)
        withTimeout(5.seconds) { while (engine.freed.isEmpty()) delay(20) }
        gen.generate("s", "u", 16)
        assertEquals(2, engine.inits.size)
    }

    @Test
    fun aUseInsideTheIdleWindowKeepsTheModel() = runBlocking(Dispatchers.Default) {
        val engine = FakeEngine()
        val gen = generator(FakeProvider(setOf(small.id)), engine, idleTimeout = 2.seconds)
        gen.generate("s", "u", 16)
        delay(1_200)
        gen.generate("s", "u", 16)
        delay(1_200)
        assertTrue(engine.freed.isEmpty(), "freed inside the idle window of the last use")
        assertEquals(1, engine.inits.size)
    }

    /** The idle window counts from the end of the last use, however long the use itself ran. */
    @Test
    fun aUseLongerThanTheIdleWindowKeepsTheModelAfterward() = runBlocking(Dispatchers.Default) {
        val engine = FakeEngine()
        val gen = generator(FakeProvider(setOf(small.id)), engine, idleTimeout = 150.milliseconds)
        gen.generate("s", "u", 16)
        engine.onGenerate = { _, _, _ ->
            Thread.sleep(500)
            "slow"
        }
        gen.generate("s", "u", 16)
        delay(50)
        assertTrue(engine.freed.isEmpty(), "the model was freed as soon as a long use ended")
    }

    @Test
    fun aModelTheEngineRefusesToLoadIsUnusableWhileItIsThePick() = runBlocking<Unit> {
        val engine = FakeEngine().apply { onInit = { throw RuntimeException("llm init failed: llama_init_from_model failed") } }
        val provider = FakeProvider(setOf(small.id, large.id))
        var selected: String? = large.id
        val gen = TextGenerator(provider, { selected }, { null }, DictationHold(scope, 30.seconds), InferenceExclusion(), engine, { 2 }, scope)
        assertFailsWith<TextModelUnavailableException> { gen.generate("s", "u", 16) }
        assertEquals(1, engine.initAttempts.get(), "a refused load was retried")
        assertEquals(listOf(large.id), provider.forgotten)
        assertTrue(gen.lastLoadFailed())
        selected = small.id
        assertFalse(gen.lastLoadFailed())
        selected = large.id
        assertTrue(gen.lastLoadFailed(), "the mark was dropped while another model was the pick")
    }

    /** A death while loading is what a corrupt file would cause, so the file is hashed again before the retry. */
    @Test
    fun anEngineDeathDuringALoadDropsTheMemoAndIsRetried() = runBlocking<Unit> {
        val engine = FakeEngine().apply { onInit = { attempt -> if (attempt == 1) throw LlmEngineUnavailableException("died") } }
        val provider = FakeProvider(setOf(small.id))
        val gen = generator(provider, engine)
        assertEquals("reply", gen.generate("s", "u", 16))
        assertEquals(listOf(small.id), provider.forgotten)
        assertFalse(gen.lastLoadFailed())
    }

    @Test
    fun anEngineThatDiesDuringEveryLoadMakesTheModelUnusable() = runBlocking<Unit> {
        val engine = FakeEngine().apply { onInit = { throw LlmEngineUnavailableException("died") } }
        val gen = generator(FakeProvider(setOf(small.id)), engine)
        assertFailsWith<TextModelUnavailableException> { gen.prepare() }
        assertEquals(TextGenerator.MAX_RETRIES + 1, engine.initAttempts.get())
        assertTrue(gen.lastLoadFailed())
    }

    @Test
    fun aLoadLossAfterGenerationLossesLeavesTheModelUsable() = runBlocking<Unit> {
        val engine = FakeEngine().apply {
            onGenerate = { _, _, _ -> throw LlmEngineUnavailableException("died") }
            onInit = { attempt -> if (attempt > TextGenerator.MAX_RETRIES) throw LlmEngineUnavailableException("died") }
        }
        val gen = generator(FakeProvider(setOf(small.id)), engine)
        assertFailsWith<LlmEngineUnavailableException> { gen.generate("s", "u", 16) }
        assertEquals(TextGenerator.MAX_RETRIES, engine.calls.get())
        assertEquals(TextGenerator.MAX_RETRIES + 1, engine.initAttempts.get())
        assertFalse(gen.lastLoadFailed())
    }

    /** A hold that outlasts the first [waits] waits of a use and is down from then on. */
    private fun heldThrough(waits: Int) = object : DictationPriority {
        private val waited = AtomicInteger()
        override val isHeld get() = false
        override suspend fun awaitReleased() {
            if (waited.incrementAndGet() <= waits) awaitCancellation()
        }
        override suspend fun awaitRaised() {
            awaitCancellation()
        }
    }

    private fun generatorWhoseLoadsAreLost(engine: FakeEngine, holds: DictationPriority): TextGenerator {
        engine.onInit = { throw LlmEngineUnavailableException("died") }
        return TextGenerator(
            FakeProvider(setOf(small.id)), { null }, { null }, holds, InferenceExclusion(), engine, { 2 }, scope, holdWait = 50.milliseconds,
        )
    }

    @Test
    fun aLoadLossAfterHoldWaitsThatRanOutLeavesTheModelUsable() = runBlocking(Dispatchers.Default) {
        val engine = FakeEngine()
        val gen = generatorWhoseLoadsAreLost(engine, heldThrough(TextGenerator.MAX_RETRIES))
        assertFailsWith<LlmEngineUnavailableException> { gen.prepare() }
        assertEquals(1, engine.initAttempts.get())
        assertFalse(gen.lastLoadFailed())
    }

    @Test
    fun loadLossesOnAllButOneOfAUsesCountedEventsLeaveTheModelUsable() = runBlocking(Dispatchers.Default) {
        val engine = FakeEngine()
        val gen = generatorWhoseLoadsAreLost(engine, heldThrough(1))
        assertFailsWith<LlmEngineUnavailableException> { gen.prepare() }
        assertEquals(TextGenerator.MAX_RETRIES, engine.initAttempts.get())
        assertFalse(gen.lastLoadFailed())
    }

    @Test
    fun aLoadPastItsDeadlineMakesTheModelUnusableWithoutARetry() = runBlocking<Unit> {
        val engine = FakeEngine().apply { onInit = { throw LlmEngineTimeoutException("did not answer init") } }
        val gen = generator(FakeProvider(setOf(small.id)), engine)
        assertFailsWith<TextModelUnavailableException> { gen.generate("s", "u", 16) }
        assertEquals(1, engine.initAttempts.get())
        assertTrue(gen.lastLoadFailed())
    }

    @Test
    fun aGenerationPastItsDeadlineIsNotRetried() = runBlocking<Unit> {
        val engine = FakeEngine().apply { onGenerate = { _, _, _ -> throw LlmEngineTimeoutException("did not answer generate") } }
        assertFailsWith<LlmEngineTimeoutException> { generator(FakeProvider(setOf(small.id)), engine).generate("s", "u", 16) }
        assertEquals(1, engine.calls.get())
    }

    @Test
    fun restartsForDictationAreNotCountedAgainstTheRetries() = runBlocking(Dispatchers.Default) {
        val holds = DictationHold(scope, 30.seconds)
        val restarts = TextGenerator.MAX_RETRIES + 2
        val started = java.util.concurrent.LinkedBlockingQueue<Long>()
        val engine = FakeEngine()
        engine.onGenerate = { call, callId, _ ->
            if (call <= restarts) {
                started.put(callId)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (callId !in engine.cancels && System.nanoTime() < deadline) Thread.sleep(5)
                throw RuntimeException("llm generate failed: canceled")
            }
            "finally"
        }
        val pending = async { generator(FakeProvider(setOf(small.id)), engine, holds).generate("s", "u", 16) }
        repeat(restarts) {
            assertNotNull(withContext(Dispatchers.IO) { started.poll(5, TimeUnit.SECONDS) }, "restart $it never started")
            val hold = holds.raise()
            withTimeout(5.seconds) { while (engine.cancels.size < it + 1) delay(5) }
            holds.release(hold)
        }
        assertEquals("finally", withTimeout(10.seconds) { pending.await() })
        assertNull(engine.ended)
    }

    /** A hold raised while the call is being prepared, before the watch starts, still stops that call. */
    @Test
    fun aHoldRaisedAsTheCallIsPreparedStillStopsIt() = runBlocking(Dispatchers.Default) {
        val holds = DictationHold(scope, 30.seconds)
        val engine = FakeEngine()
        engine.onGenerate = { call, callId, _ ->
            if (call == 1) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (callId !in engine.cancels && System.nanoTime() < deadline) Thread.sleep(5)
                throw RuntimeException("llm generate failed: canceled")
            }
            "reply"
        }
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val gen = generator(FakeProvider(setOf(small.id)), engine, holds, threads = {
            if (first.getAndSet(false)) {
                val hold = holds.raise()
                scope.launch {
                    delay(300)
                    holds.release(hold)
                }
            }
            2
        })
        assertEquals("reply", withTimeout(10.seconds) { gen.generate("s", "u", 16) })
        assertEquals(2, engine.calls.get())
        assertNull(engine.ended, "the call ran on until the engine was ended")
    }

    @Test
    fun aLoadWaitsWhileADictationHoldsTheEngine() = runBlocking(Dispatchers.Default) {
        val engine = FakeEngine()
        val holds = DictationHold(scope, 30.seconds)
        val hold = holds.raise()
        val pending = async { generator(FakeProvider(setOf(small.id)), engine, holds).prepare() }
        delay(300)
        assertTrue(engine.inits.isEmpty(), "loaded beside a dictation")
        holds.release(hold)
        withTimeout(5.seconds) { pending.await() }
        assertEquals(1, engine.inits.size)
    }

    @Test
    fun aPreloadLoadsTheModelInTheBackground() = runBlocking(Dispatchers.Default) {
        val engine = FakeEngine()
        generator(FakeProvider(setOf(small.id)), engine).preload()
        withTimeout(5.seconds) { while (engine.inits.isEmpty()) delay(20) }
    }

    @Test
    fun aReleaseFreesTheModelAtOnce() = runBlocking<Unit> {
        val engine = FakeEngine()
        val gen = generator(FakeProvider(setOf(small.id)), engine)
        gen.generate("s", "u", 16)
        gen.release()
        assertEquals(1, engine.freed.size)
        gen.generate("s", "u", 16)
        assertEquals(2, engine.inits.size)
    }

    @Test
    fun installedModelsAreTheUsableCatalogTextModels() {
        val provider = FakeProvider(setOf(small.id, large.id, "base-en"), incompatible = setOf(large.id))
        assertEquals(listOf(small), generator(provider, FakeEngine()).installedModels())
    }

    @Test
    fun aDeletedModelThatIsLoadedIsFreedAtOnce() = runBlocking<Unit> {
        val engine = FakeEngine()
        val provider = FakeProvider(setOf(small.id))
        val gen = generator(provider, engine)
        gen.generate("s", "u", 16)
        provider.installed = emptySet()
        gen.onModelDeleted(small.id)
        assertEquals(1, engine.freed.size)
    }

    @Test
    fun deletingAModelThatIsNotLoadedFreesNothing() = runBlocking<Unit> {
        val engine = FakeEngine()
        val gen = generator(FakeProvider(setOf(small.id, large.id)), engine, selected = small.id)
        gen.generate("s", "u", 16)
        gen.onModelDeleted(large.id)
        assertTrue(engine.freed.isEmpty())
    }

    @Test
    fun aDeleteWaitsForTheGenerationUsingTheModel() = runBlocking(Dispatchers.Default) {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val engine = FakeEngine().apply {
            onGenerate = { _, _, _ ->
                started.countDown()
                finish.await(5, TimeUnit.SECONDS)
                "done"
            }
        }
        val gen = generator(FakeProvider(setOf(small.id)), engine)
        val pending = async { gen.generate("s", "u", 16) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val deleted = async { gen.onModelDeleted(small.id) }
        delay(200.milliseconds)
        assertTrue(engine.freed.isEmpty(), "the model was freed under a running generation")
        finish.countDown()
        assertEquals("done", pending.await())
        withTimeout(5.seconds) { deleted.await() }
        assertEquals(1, engine.freed.size)
    }

    @Test
    fun aDeletedModelsFailedLoadIsForgotten() = runBlocking<Unit> {
        val engine = FakeEngine().apply { onInit = { throw RuntimeException("llm init failed") } }
        val gen = generator(FakeProvider(setOf(small.id)), engine)
        assertFailsWith<TextModelUnavailableException> { gen.generate("s", "u", 16) }
        assertTrue(gen.lastLoadFailed())
        gen.onModelDeleted(small.id)
        assertFalse(gen.lastLoadFailed())
    }

    @Test
    fun aLoadThatFailsWhileADeleteWaitsIsForgottenToo() = runBlocking(Dispatchers.Default) {
        val loading = CountDownLatch(1)
        val fail = CountDownLatch(1)
        val engine = FakeEngine().apply {
            onInit = {
                loading.countDown()
                fail.await(5, TimeUnit.SECONDS)
                throw RuntimeException("llm init failed")
            }
        }
        val gen = generator(FakeProvider(setOf(small.id)), engine)
        val use = async { runCatching { gen.generate("s", "u", 16) } }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        val deleted = async { gen.onModelDeleted(small.id) }
        delay(200.milliseconds)
        fail.countDown()
        assertTrue(use.await().exceptionOrNull() is TextModelUnavailableException)
        withTimeout(5.seconds) { deleted.await() }
        assertFalse(gen.lastLoadFailed())
    }

    @Test
    fun twoGeneratorsNeverShareACallId() = runBlocking<Unit> {
        val engine = FakeEngine()
        generator(FakeProvider(setOf(small.id)), engine).generate("s", "u", 16)
        generator(FakeProvider(setOf(small.id)), engine).generate("s", "u", 16)
        assertEquals(2, engine.callIds.toSet().size)
    }
}
