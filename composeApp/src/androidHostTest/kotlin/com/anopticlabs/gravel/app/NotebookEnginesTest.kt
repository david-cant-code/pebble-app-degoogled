package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.llm.LlmEngineTimeoutException
import com.anopticlabs.gravel.llm.LlmEngineUnavailableException
import com.anopticlabs.gravel.models.InferenceExclusion
import com.anopticlabs.gravel.models.LlmModelCatalog
import com.anopticlabs.gravel.models.SpeechModelInstalls
import com.anopticlabs.gravel.models.TextEngineHeldException
import com.anopticlabs.gravel.models.TextGenerator
import com.anopticlabs.gravel.models.TextModelUnavailableException
import com.anopticlabs.gravel.models.TextPromptTooLongException
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.MAX_SERVER_TRIES
import com.anopticlabs.gravel.notebook.capture.SERVER_TRY_SPACING_MILLIS
import com.anopticlabs.gravel.notebook.pipeline.CAPTURE_SAMPLE_RATE
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureNotice
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.pipeline.CaptureRemedy
import com.anopticlabs.gravel.notebook.pipeline.MAX_CAPTURE_MILLIS
import com.anopticlabs.gravel.notebook.pipeline.SHORT_OF_MEMORY
import com.anopticlabs.gravel.notebook.pipeline.ServerCheck
import com.anopticlabs.gravel.notebook.pipeline.SpeechCapture
import com.anopticlabs.gravel.notebook.pipeline.SpeechRoute
import com.anopticlabs.gravel.notebook.pipeline.SpeechSettingWatch
import com.russhwolf.settings.MapSettings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.CoreConfigHolder
import coredevices.util.WeatherUnit
import coredevices.util.models.CactusSTTMode
import com.anopticlabs.gravel.transcription.DictationPriority
import com.anopticlabs.gravel.transcription.LongFormTimeoutException
import com.anopticlabs.gravel.transcription.ServerDictationHold
import com.anopticlabs.gravel.whisper.WhisperLongForm
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.models.WhisperTier
import coredevices.util.transcription.CactusModelPathProvider
import coredevices.util.transcription.ServerUnreachableException
import coredevices.util.transcription.TranscriptionException
import coredevices.util.transcription.TransportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookEnginesTest {

    /** The Notebook refuses what the speech engine would refuse, and nothing it would take. */
    @Test
    fun theCaptureBoundsAreTheSpeechEnginesBounds() {
        assertEquals(WhisperLongForm.MAX_RECORDING_MILLIS, MAX_CAPTURE_MILLIS)
        assertEquals(WhisperLongForm.SAMPLE_RATE, CAPTURE_SAMPLE_RATE)
    }

    @Test
    fun speechFailuresAreReadByKind() {
        assertEquals(CaptureFailureKind.FLAG, speechFailureKind(LongFormTimeoutException("m")))
        assertEquals(CaptureFailureKind.FLAG, speechFailureKind(IllegalArgumentException("long-form audio of 0 bytes")))
        assertEquals(CaptureFailureKind.HOLD, speechFailureKind(TranscriptionException.TranscriptionRequiresDownload("Model not initialized")))
        assertEquals(CaptureFailureKind.RETRY, speechFailureKind(TranscriptionException.TranscriptionServiceUnavailable("m")))
        assertEquals(CaptureFailureKind.HOLD, speechFailureKind(TranscriptionException.TranscriptionInProgress("m")))
        assertEquals(CaptureFailureKind.HOLD, speechFailureKind(TranscriptionException.NotEnoughMemory("m")))
    }

    @Test
    fun textFailuresAreReadByKind() {
        assertEquals(CaptureFailureKind.HOLD, textFailureKind(TextModelUnavailableException("none")))
        assertEquals(CaptureFailureKind.FLAG, textFailureKind(TextPromptTooLongException("long")))
        assertEquals(CaptureFailureKind.FLAG, textFailureKind(IllegalArgumentException("llm formatChat refused")))
        assertEquals(CaptureFailureKind.HOLD, textFailureKind(TextEngineHeldException("held")))
        assertEquals(CaptureFailureKind.FLAG, textFailureKind(LlmEngineTimeoutException("did not answer")))
        assertEquals(CaptureFailureKind.RETRY, textFailureKind(LlmEngineUnavailableException("died")))
        assertEquals(CaptureFailureKind.RETRY, textFailureKind(RuntimeException("llm generate failed")))
    }

    @Test
    fun flaggedEngineFailuresNameTheirReason() {
        assertEquals(CaptureFailureReason.SPEECH_TIMEOUT, speechFailureReason(LongFormTimeoutException("m")))
        assertEquals(CaptureFailureReason.SPEECH_REFUSED, speechFailureReason(IllegalArgumentException("long-form audio of 0 bytes")))
        assertNull(speechFailureReason(TranscriptionException.TranscriptionServiceUnavailable("m")))
        assertEquals(CaptureFailureReason.TEXT_TOO_LONG, textFailureReason(TextPromptTooLongException("long")))
        assertEquals(CaptureFailureReason.TEXT_TIMEOUT, textFailureReason(LlmEngineTimeoutException("did not answer")))
        assertEquals(CaptureFailureReason.TEXT_REFUSED, textFailureReason(IllegalArgumentException("llm formatChat refused")))
        assertNull(textFailureReason(LlmEngineUnavailableException("died")))
    }

    @Test
    fun onlyATextModelProblemOffersTheTextModelPage() {
        assertEquals(CaptureRemedy.TEXT_MODEL, textHoldRemedy(TextModelUnavailableException("none")))
        assertNull(textHoldRemedy(TextEngineHeldException("held")))
        assertNull(textHoldRemedy(LlmEngineUnavailableException("died")))
    }

    /** The host has no text engine attached, so readiness fails on the engine check. */
    @Test
    fun aTextEngineThatCannotRunHoldsWithTheTextModelPageOffered() = runBlocking {
        val text = NotebookText(
            TextGenerator(
                provider = NoModels,
                selectedModelId = { null },
                recommendedModel = { null },
                dictation = NoDictation,
                exclusion = InferenceExclusion(),
            ),
        )
        val held = assertFailsWith<CaptureProcessingException> { text.checkReady() }
        assertEquals(CaptureFailureKind.HOLD, held.kind)
        assertEquals("this device cannot run a text model", held.message)
        assertEquals(CaptureRemedy.TEXT_MODEL, held.remedy)
    }

    private object NoDictation : DictationPriority {
        override val isHeld = false
        override suspend fun awaitRaised() = awaitCancellation()
        override suspend fun awaitReleased() {}
    }

    private object NoModels : CactusModelPathProvider {
        override suspend fun getSTTModelPath() = error("none")
        override suspend fun getLMModelPath() = error("none")
        override suspend fun getModelPath(modelId: String, allowReinstall: Boolean) = error("none")
        override fun isModelDownloaded(modelName: String) = false
        override fun getDownloadedModels() = emptyList<String>()
        override fun getIncompatibleModels() = emptyList<String>()
        override fun deleteModel(modelName: String) {}
        override fun getModelSizeBytes(modelName: String) = 0L
        override fun initTelemetry() {}
    }

    @Test
    fun aTranscriptionNeedsItsModelsTierAndAnUnknownModelTheLargest() {
        assertEquals(WhisperTier.Base.longFormBytes, longFormNeedBytes("whisper-base-en", loaded = true))
        assertEquals(WhisperTier.Tiny.longFormBytes, longFormNeedBytes("whisper-tiny", loaded = true))
        val largest = WhisperTier.entries.maxOf { it.longFormBytes }
        assertEquals(largest, longFormNeedBytes(null, loaded = true))
        assertEquals(largest, longFormNeedBytes("whisper-large", loaded = true))
    }

    @Test
    fun aModelNotYetLoadedAddsItsWeights() {
        val base = WhisperModelCatalog.byId("whisper-base-en")!!
        assertEquals(WhisperTier.Base.longFormBytes + base.sizeBytes, longFormNeedBytes(base.id, loaded = false))
        assertEquals(
            WhisperTier.entries.maxOf { it.longFormBytes } + WhisperModelCatalog.MODELS.maxOf { it.sizeBytes },
            longFormNeedBytes("whisper-large", loaded = false),
        )
    }

    private class FakePhone(var model: Boolean = true, var reply: () -> String = { "phone words" }) : NotebookPhoneSpeech {
        var calls = 0
        override fun available() = model
        override suspend fun transcribe(pcm16: ByteArray): String {
            calls++
            return reply()
        }
    }

    private class FakeServer(var url: Boolean = true, var allowed: Boolean = true, var stamp: Int = 1, var reply: () -> String = { "server words" }) : NotebookServerSpeech {
        @Volatile var calls = 0
        var network = true
        var during: () -> Unit = {}
        var gate: CompletableDeferred<Unit>? = null
        var onStamp: () -> Unit = {}

        /** The first request runs until it is stopped. */
        var holdFirst = false
        override fun configured() = url
        override fun settingsStamp(): Int {
            onStamp()
            return stamp
        }
        override fun allowedOnCurrentNetwork() = allowed
        override fun networkConnected() = network
        override suspend fun transcribe(pcm16: ByteArray): String {
            calls++
            during()
            gate?.await()
            if (holdFirst && calls == 1) awaitCancellation()
            return reply()
        }
    }

    private val phone = FakePhone()
    private val server = FakeServer()
    private val check = ServerCheck()
    private val dictation = ServerDictationHold()
    private val marks = mutableListOf<String>()

    private fun speech(mode: CactusSTTMode) = NotebookSpeech({ mode }, phone, server, check, dictation)

    private fun capture(id: String = "c", phoneTried: Boolean = false, fits: (() -> Boolean)? = { true }) =
        SpeechCapture(id, phoneTried, { checkNotNull(fits) { "memory asked" }() }, { marks += id })

    private suspend fun NotebookSpeech.run(c: SpeechCapture = capture()) = transcribe(ByteArray(4), c)

    private suspend fun failure(block: suspend () -> Unit) = assertFailsWith<CaptureProcessingException> { block() }

    // Every setting combination, with engines that succeed: what the watch is told must match what the drain does.
    @Test
    fun theWatchRecordingWaitMatchesWhatTheDrainDoesWithAWatchRecording() = runBlocking {
        val bools = listOf(false, true)
        var checked = 0
        for (mode in CactusSTTMode.entries) for (model in bools) for (url in bools) for (allowed in bools) for (held in bools) for (flaky in bools) {
            phone.model = model
            server.url = url
            server.allowed = allowed
            val serverCheck = ServerCheck().apply { if (held) fellBack(server.stamp) }
            val s = NotebookSpeech({ mode }, phone, server, serverCheck, dictation, { flaky }, { now })
            val told = s.watchRecordingWait()
            val drained = try {
                s.checkReady()
                s.transcribe(ByteArray(4), SpeechCapture("w", false, { true }, {}, fromWatch = true))
                RecordingWait.NONE
            } catch (e: CaptureProcessingException) {
                assertTrue(e.kind == CaptureFailureKind.HOLD || e.kind == CaptureFailureKind.WAIT, "$mode: ${e.kind} ${e.message}")
                if (e.message == WIFI_HELD) RecordingWait.WIFI else RecordingWait.SETUP
            }
            assertEquals(drained, told, "mode=$mode model=$model url=$url allowed=$allowed held=$held flaky=$flaky")
            checked++
        }
        assertEquals(CactusSTTMode.entries.size * 32, checked)
    }

    @Test
    fun eachSettingRoutesRecordings() {
        assertEquals(SpeechRoute.PHONE, speech(CactusSTTMode.LocalOnly).route())
        assertEquals(SpeechRoute.PHONE, speech(CactusSTTMode.RebbleFirst).route())
        assertEquals(SpeechRoute.PHONE, speech(CactusSTTMode.RebbleFallback).route())
        assertEquals(SpeechRoute.SERVER, speech(CactusSTTMode.RemoteOnly).route())
        assertEquals(SpeechRoute.SERVER_THEN_PHONE, speech(CactusSTTMode.RemoteFirst).route())
        assertEquals(SpeechRoute.PHONE_THEN_SERVER, speech(CactusSTTMode.LocalFirst).route())
        phone.model = false
        assertEquals(SpeechRoute.SERVER, speech(CactusSTTMode.LocalFirst).route())
        assertEquals(SpeechRoute.SERVER, speech(CactusSTTMode.RemoteFirst).route())
        server.url = false
        assertEquals(SpeechRoute.PHONE, speech(CactusSTTMode.LocalFirst).route())
        assertEquals(SpeechRoute.PHONE, speech(CactusSTTMode.RemoteFirst).route())
        assertEquals(SpeechRoute.SERVER, speech(CactusSTTMode.RemoteOnly).route())
    }

    @Test
    fun aServerCountsAsSetUpWhateverTheNetwork() = runBlocking<Unit> {
        server.allowed = false
        phone.model = false
        assertTrue(speech(CactusSTTMode.RemoteOnly).isSetUp())
        assertTrue(speech(CactusSTTMode.RemoteFirst).isSetUp())
        assertTrue(speech(CactusSTTMode.LocalFirst).isSetUp())
        assertFalse(speech(CactusSTTMode.LocalOnly).isSetUp())
        assertFalse(speech(CactusSTTMode.RebbleOnly).isSetUp())
        assertFalse(speech(CactusSTTMode.PlatformOnly).isSetUp())
        server.url = false
        assertFalse(speech(CactusSTTMode.RemoteOnly).isSetUp())
        phone.model = true
        assertTrue(speech(CactusSTTMode.LocalFirst).isSetUp())
        assertTrue(speech(CactusSTTMode.RemoteFirst).isSetUp())
        assertTrue(speech(CactusSTTMode.LocalOnly).isSetUp())
    }

    @Test
    fun settingsTheNotebookCannotUseHoldWithTheSpeechSettingsOffered() = runBlocking<Unit> {
        for ((mode, line) in listOf(CactusSTTMode.RebbleOnly to REBBLE_ONLY_HELD, CactusSTTMode.PlatformOnly to PLATFORM_ONLY_HELD)) {
            val held = failure { speech(mode).checkReady() }
            assertEquals(CaptureFailureKind.HOLD, held.kind)
            assertEquals(line, held.message)
            assertEquals(CaptureRemedy.SPEECH_SETTINGS, held.remedy)
        }
        server.url = false
        val noServer = failure { speech(CactusSTTMode.RemoteOnly).checkReady() }
        assertEquals(NO_SERVER_HELD, noServer.message)
        assertEquals(CaptureRemedy.SPEECH_SETTINGS, noServer.remedy)
        phone.model = false
        assertEquals("on-device speech recognition is not set up", failure { speech(CactusSTTMode.LocalOnly).checkReady() }.message)
    }

    @Test
    fun theServerRouteNeverUsesThePhoneOrAsksForMemory() = runBlocking<Unit> {
        val s = speech(CactusSTTMode.RemoteOnly)
        s.checkReady()
        assertEquals("server words", s.run(capture(fits = null)))
        assertEquals(0, phone.calls)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun offTheAllowedNetworkTheServerRouteHoldsForTheNetworkBeforeAnyUpload() = runBlocking<Unit> {
        server.allowed = false
        val s = speech(CactusSTTMode.RemoteOnly)
        val held = failure { s.checkReady() }
        assertEquals(CaptureFailureKind.HOLD, held.kind)
        assertEquals(WIFI_HELD, held.message)
        assertTrue(held.waitsForNetwork)
        server.allowed = true
        server.reply = { throw TranscriptionException.ServerNotAllowedOnNetwork("s") }
        val dropped = failure { s.run() }
        assertEquals(WIFI_HELD, dropped.message)
        assertTrue(dropped.waitsForNetwork)
    }

    @Test
    fun anUnreachableServerHoldsForTheNetworkAndIsNotAskedAgainThatPass() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.TranscriptionNetworkError(java.io.IOException("refused"), "s") }
        val s = speech(CactusSTTMode.RemoteOnly)
        s.checkReady()
        val held = failure { s.run() }
        assertEquals(CaptureFailureKind.HOLD, held.kind)
        assertEquals(UNREACHABLE_HELD, held.message)
        assertTrue(held.waitsForNetwork)
        assertEquals(UNREACHABLE_HELD, failure { s.run() }.message)
        assertEquals(1, server.calls)
        s.checkReady()
        failure { s.run() }
        assertEquals(2, server.calls)
    }

    @Test
    fun aServerErrorHoldsUntilTryAgainAndTheSameRecordingFailingAgainIsFlagged() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        val s = speech(CactusSTTMode.RemoteOnly)
        val held = failure { s.run(capture("a")) }
        assertEquals(CaptureFailureKind.HOLD, held.kind)
        assertEquals(SERVER_CHECK_HELD, held.message)
        assertEquals(CaptureRemedy.SERVER_CHECK, held.remedy)
        assertFalse(held.waitsForNetwork)
        assertEquals(SERVER_CHECK_HELD, failure { s.checkReady() }.message)
        assertEquals(1, server.calls)
        check.tryAgain()
        s.checkReady()
        val flagged = failure { s.run(capture("a")) }
        assertEquals(CaptureFailureKind.FLAG, flagged.kind)
        assertEquals(CaptureFailureReason.SERVER_ERROR, flagged.reason)
        s.checkReady()
        assertEquals(SERVER_CHECK_HELD, failure { s.run(capture("b")) }.message)
    }

    @Test
    fun aRecordingThatGoesThroughAfterTryAgainIsNotFlaggedForALaterError() = runBlocking<Unit> {
        val s = speech(CactusSTTMode.RemoteOnly)
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        failure { s.run(capture("a")) }
        check.tryAgain()
        server.reply = { "server words" }
        assertEquals("server words", s.run(capture("a")))
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        assertEquals(CaptureFailureKind.HOLD, failure { s.run(capture("a")) }.kind)
    }

    @Test
    fun aChangeOfServerSettingsEndsTheServerErrorHold() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 401") }
        val s = speech(CactusSTTMode.RemoteOnly)
        failure { s.run(capture("a")) }
        server.stamp = 2
        s.checkReady()
    }

    @Test
    fun theServersOtherAnswersMapToTheQueuesKinds() = runBlocking<Unit> {
        val s = speech(CactusSTTMode.RemoteOnly)
        server.reply = { throw TranscriptionException.NoSpeechDetected("empty_result", "s") }
        val silent = failure { s.run() }
        assertEquals(CaptureFailureKind.FLAG, silent.kind)
        assertEquals(CaptureFailureReason.NO_SPEECH, silent.reason)
        server.reply = { throw TranscriptionException.TranscriptionServiceUnavailable("s") }
        assertEquals(NO_SERVER_HELD, failure { s.run() }.message)
        server.reply = { throw IllegalStateException("odd") }
        assertEquals(CaptureFailureKind.RETRY, failure { s.run() }.kind)
    }

    @Test
    fun thePhoneRouteHoldsWhenMemoryStaysShort() = runBlocking<Unit> {
        val held = failure { speech(CactusSTTMode.LocalOnly).run(capture(fits = { false })) }
        assertEquals(CaptureFailureKind.HOLD, held.kind)
        assertEquals(SHORT_OF_MEMORY, held.message)
        assertEquals(0, phone.calls)
        phone.reply = { throw LongFormTimeoutException("m") }
        assertEquals(CaptureFailureReason.SPEECH_TIMEOUT, failure { speech(CactusSTTMode.LocalOnly).run() }.reason)
        assertEquals(0, server.calls)
    }

    @Test
    fun localWithServerFallbackKeepsWhatThePhoneHears() = runBlocking<Unit> {
        assertEquals("phone words", speech(CactusSTTMode.LocalFirst).run())
        assertEquals(0, server.calls)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun localWithServerFallbackSendsWhatThePhoneCannotDoAndMarksWhatItTried() = runBlocking<Unit> {
        val s = speech(CactusSTTMode.LocalFirst)
        phone.reply = { "[BLANK_AUDIO]" }
        assertEquals("server words", s.run(capture("silent")))
        phone.reply = { throw LongFormTimeoutException("m") }
        assertEquals("server words", s.run(capture("slow")))
        assertEquals("server words", s.run(capture("short-of-memory", fits = { false })))
        assertEquals(listOf("silent", "slow"), marks)
        assertEquals(2, phone.calls)
        assertEquals("server words", s.run(capture("tried", phoneTried = true, fits = null)))
        assertEquals(2, phone.calls)
        assertEquals(4, server.calls)
    }

    @Test
    fun localWithServerFallbackKeepsOtherPhoneFailures() = runBlocking<Unit> {
        phone.reply = { throw IllegalArgumentException("long-form audio of 0 bytes") }
        assertEquals(CaptureFailureReason.SPEECH_REFUSED, failure { speech(CactusSTTMode.LocalFirst).run() }.reason)
        assertEquals(0, server.calls)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun aServerStepThatCannotRunMakesOnlyItsRecordingWaitWhenThePhoneTakesTheRest() = runBlocking<Unit> {
        val s = speech(CactusSTTMode.LocalFirst)
        server.allowed = false
        s.checkReady()
        val waiting = failure { s.run(capture(phoneTried = true)) }
        assertEquals(CaptureFailureKind.WAIT, waiting.kind)
        assertTrue(waiting.waitsForNetwork)
        server.allowed = true
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        val broken = failure { s.run(capture("a", phoneTried = true)) }
        assertEquals(CaptureFailureKind.WAIT, broken.kind)
        assertEquals(CaptureRemedy.SERVER_CHECK, broken.remedy)
        assertEquals("phone words", s.run(capture("b")), "the phone goes on")
    }

    @Test
    fun localWithServerFallbackSendsWhatThePhoneEngineCannotLoadOrFitAndMarksOnlyTheUnloadable() = runBlocking<Unit> {
        val s = speech(CactusSTTMode.LocalFirst)
        phone.reply = { throw TranscriptionException.NotEnoughMemory("m") }
        assertEquals("server words", s.run(capture("tight")))
        phone.reply = { throw TranscriptionException.TranscriptionRequiresDownload("Model not initialized") }
        assertEquals("server words", s.run(capture("unloadable")))
        assertEquals(listOf("unloadable"), marks)
        phone.reply = { throw TranscriptionException.TranscriptionServiceUnavailable("m") }
        assertEquals(CaptureFailureKind.RETRY, failure { s.run(capture("engine")) }.kind)
        assertEquals(2, server.calls)
    }

    private val modesWithoutServer = listOf(
        CactusSTTMode.LocalOnly, CactusSTTMode.RebbleOnly, CactusSTTMode.RebbleFirst, CactusSTTMode.RebbleFallback, CactusSTTMode.PlatformOnly,
    )

    /** The phone step can run for minutes, and the setting can change under it. */
    @Test
    fun aSettingThatLeavesTheServerDuringThePhoneStepKeepsTheRecordingOffIt() = runBlocking<Unit> {
        val phoneSteps = listOf<() -> String>(
            { "" },
            { throw TranscriptionException.NotEnoughMemory("m") },
            { throw LongFormTimeoutException("m") },
            { throw TranscriptionException.TranscriptionRequiresDownload("m") },
        )
        for (chosen in modesWithoutServer) for (step in phoneSteps) {
            var mode = CactusSTTMode.LocalFirst
            val s = NotebookSpeech({ mode }, phone, server, check, dictation)
            phone.reply = {
                mode = chosen
                step()
            }
            assertEquals(CaptureFailureKind.REROUTE, failure { s.run() }.kind, "$chosen")
        }
        var mode = CactusSTTMode.LocalFirst
        val s = NotebookSpeech({ mode }, phone, server, check, dictation)
        val turnedAway = capture(fits = {
            mode = CactusSTTMode.LocalOnly
            false
        })
        assertEquals(CaptureFailureKind.REROUTE, failure { s.run(turnedAway) }.kind, "during the memory check")
        assertEquals(0, server.calls)
        for (chosen in listOf(CactusSTTMode.RemoteOnly, CactusSTTMode.RemoteFirst)) {
            mode = CactusSTTMode.LocalFirst
            phone.reply = {
                mode = chosen
                ""
            }
            assertEquals("server words", s.run(), "$chosen still has the server")
        }
    }

    /** Read before the server step's own checks, which would make the recording wait for a server it no longer goes to. */
    @Test
    fun aSettingThatLeftTheServerDuringThePhoneStepReroutesWhateverTheServersState() = runBlocking<Unit> {
        var mode = CactusSTTMode.LocalFirst
        val s = NotebookSpeech({ mode }, phone, server, check, dictation)
        server.allowed = false
        for (chosen in modesWithoutServer) {
            mode = CactusSTTMode.LocalFirst
            phone.reply = {
                mode = chosen
                ""
            }
            assertEquals(CaptureFailureKind.REROUTE, failure { s.run() }.kind, "$chosen off the allowed network")
        }
        server.allowed = true
        check.fellBack(server.stamp)
        mode = CactusSTTMode.LocalFirst
        assertEquals(CaptureFailureKind.REROUTE, failure { s.run() }.kind, "with the server's hold up")
        check.tryAgain()
        mode = CactusSTTMode.LocalFirst
        phone.reply = {
            server.url = false
            ""
        }
        assertEquals(CaptureFailureKind.REROUTE, failure { s.run() }.kind, "the server's URL removed")
        assertEquals(0, server.calls)
    }

    /** The value the Notebook's watch follows, on the app's own config holder. */
    @Test
    fun theWatchedSpeechSettingChangesWithTheSpeechSettingsAndNoOthers() = runBlocking<Unit> {
        val holder = CoreConfigHolder(defaultValue = CoreConfig(), settings = MapSettings(), json = Json { ignoreUnknownKeys = true })
        var requests = 0
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        SpeechSettingWatch(speechWatched(CoreConfigFlow(holder.config), SpeechModelInstalls()), { requests++ }, scope).start()
        suspend fun after(change: (CoreConfig) -> CoreConfig): Int {
            holder.update(change(holder.config.value))
            repeat(5) { yield() }
            return requests
        }
        assertEquals(0, after { it.copy(weatherUnits = WeatherUnit.Metric) })
        assertEquals(1, after { it.copy(sttConfig = it.sttConfig.copy(mode = CactusSTTMode.RemoteOnly)) })
        assertEquals(2, after { it.copy(sttConfig = it.sttConfig.copy(serverUrl = "https://stt.example.net/inference")) })
        assertEquals(3, after { it.copy(sttConfig = it.sttConfig.copy(serverOnMobileData = true)) })
        assertEquals(4, after { it.copy(sttConfig = it.sttConfig.copy(flakyNetworkMode = true)) })
        assertEquals(5, after { it.copy(sttConfig = it.sttConfig.copy(modelName = "whisper-base-en")) })
        scope.cancel()
    }

    /** The other value the watch follows, on the app's own counter. */
    @Test
    fun aSpeechModelDownloadEndingInstalledAsksForARun() = runBlocking<Unit> {
        val holder = CoreConfigHolder(defaultValue = CoreConfig(), settings = MapSettings(), json = Json { ignoreUnknownKeys = true })
        val installs = SpeechModelInstalls()
        var requests = 0
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        SpeechSettingWatch(speechWatched(CoreConfigFlow(holder.config), installs), { requests++ }, scope).start()
        suspend fun after(modelId: String): Int {
            installs.downloaded(modelId)
            repeat(5) { yield() }
            return requests
        }
        assertEquals(1, after(WhisperModelCatalog.MODELS.first().id))
        assertEquals(1, after(LlmModelCatalog.MODELS.first().id))
        assertEquals(2, after(WhisperModelCatalog.MODELS.first().id))
        scope.cancel()
    }

    /** The request's own check, for a setting chosen after the route was read and before the request. */
    @Test
    fun aSettingThatLeavesTheServerJustBeforeTheRequestSendsNothing() = runBlocking<Unit> {
        for (chosen in modesWithoutServer) {
            var mode = CactusSTTMode.RemoteOnly
            val s = NotebookSpeech({ mode }, phone, server, check, dictation)
            server.onStamp = { mode = chosen }
            assertEquals(CaptureFailureKind.REROUTE, failure { s.run() }.kind, "$chosen")
        }
        assertEquals(0, server.calls)
    }

    @Test
    fun aSettingTheNotebookCannotUseChosenMidPassHoldsTheNextCapture() = runBlocking<Unit> {
        var mode = CactusSTTMode.LocalOnly
        val s = NotebookSpeech({ mode }, phone, server, check, dictation)
        s.checkReady()
        mode = CactusSTTMode.RebbleOnly
        val held = failure { s.run() }
        assertEquals(CaptureFailureKind.HOLD, held.kind)
        assertEquals(REBBLE_ONLY_HELD, held.message)
        assertEquals(0, phone.calls)
    }

    @Test
    fun withTheServerHeldThePhoneStillTakesCapturesAndTriedOnesWaitWithoutAnUpload() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        val s = speech(CactusSTTMode.LocalFirst)
        failure { s.run(capture("a", phoneTried = true)) }
        s.checkReady()
        val waiting = failure { s.run(capture("b", phoneTried = true)) }
        assertEquals(CaptureFailureKind.WAIT, waiting.kind)
        assertEquals(CaptureRemedy.SERVER_CHECK, waiting.remedy)
        assertEquals(1, server.calls)
        assertEquals("phone words", s.run(capture("c")))
    }

    @Test
    fun onThePhoneThenServerRouteAnUnreachableServerIsAskedOncePerPass() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.TranscriptionNetworkError(java.io.IOException("refused"), "s") }
        val s = speech(CactusSTTMode.LocalFirst)
        s.checkReady()
        val first = failure { s.run(capture("a", phoneTried = true)) }
        assertEquals(CaptureFailureKind.WAIT, first.kind)
        assertEquals(UNREACHABLE_HELD, first.message)
        assertEquals(UNREACHABLE_HELD, failure { s.run(capture("b", phoneTried = true)) }.message)
        assertEquals(1, server.calls)
        s.checkReady()
        failure { s.run(capture("b", phoneTried = true)) }
        assertEquals(2, server.calls)
    }

    private fun lost(failure: TransportFailure) =
        TranscriptionException.TranscriptionNetworkError(ServerUnreachableException("connection reset", failure), "s")

    @Test
    fun aConnectionLostOnceMadeIsACountedRetryAndTheServerIsAskedAgainThatPass() = runBlocking<Unit> {
        val s = speech(CactusSTTMode.RemoteOnly)
        s.checkReady()
        for (failure in listOf(TransportFailure.LOST, TransportFailure.TIMED_OUT)) {
            server.reply = { throw lost(failure) }
            val retried = failure { s.run() }
            assertEquals(CaptureFailureKind.RETRY, retried.kind, "$failure")
            assertFalse(retried.waitsForNetwork)
        }
        assertEquals(2, server.calls)
        server.reply = { throw lost(TransportFailure.NOT_CONNECTED) }
        assertEquals(UNREACHABLE_HELD, failure { s.run() }.message)
    }

    @Test
    fun theLetThroughIsUsedUpByTheNextRequestWhateverItsOutcome() = runBlocking<Unit> {
        val s = speech(CactusSTTMode.RemoteOnly)
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        failure { s.run(capture("a")) }
        check.tryAgain()
        server.reply = { throw lost(TransportFailure.NOT_CONNECTED) }
        s.checkReady()
        failure { s.run(capture("a")) }
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        s.checkReady()
        assertEquals(CaptureFailureKind.HOLD, failure { s.run(capture("a")) }.kind)
    }

    private var now = 1_790_000_000_000L
    private val serverTries = mutableListOf<Pair<String, Long>>()
    private var triesRecorded = true

    private fun fallback(flaky: Boolean = false) = NotebookSpeech({ CactusSTTMode.RemoteFirst }, phone, server, check, dictation, { flaky }, { now })

    private fun recording(
        id: String = "r",
        long: Boolean = false,
        fromWatch: Boolean = false,
        tries: Int = 0,
        lastTry: Long? = null,
        fits: (() -> Boolean)? = { true },
    ) = SpeechCapture(
        id, false, { checkNotNull(fits) { "memory asked" }() }, { marks += id }, long, fromWatch, tries, lastTry,
        { at -> serverTries += id to at; triesRecorded },
    )

    private val serverErrors = listOf(
        "not allowed" to { server.allowed = false; server.reply = { error("no upload off the allowed network") } },
        "never connected" to { server.reply = { throw lost(TransportFailure.NOT_CONNECTED) } },
        "lost" to { server.reply = { throw lost(TransportFailure.LOST) } },
        "timed out" to { server.reply = { throw lost(TransportFailure.TIMED_OUT) } },
        "server error" to { server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") } },
    )

    @Test
    fun serverWithLocalFallbackSendsShortRecordingsToThePhoneWhateverKeepsTheServerFromThem() = runBlocking<Unit> {
        for ((name, failWith) in serverErrors) {
            server.allowed = true
            failWith()
            val s = fallback()
            s.checkReady()
            assertEquals("phone words", s.run(recording()), name)
            val held = name == "server error"
            assertEquals(if (held) SpeechRoute.PHONE else SpeechRoute.SERVER_THEN_PHONE, s.route(), name)
            assertEquals(held, s.notice() != null, name)
            check.tryAgain()
        }
        assertEquals(serverErrors.size, phone.calls)
        assertTrue(serverTries.isEmpty())
        assertEquals("server words", fallback().also { server.reply = { "server words" } }.run(recording()))
    }

    @Test
    fun aServerThatNeverConnectedIsSkippedForTheRestOfThePassAndALostOneIsNot() = runBlocking<Unit> {
        val s = fallback()
        s.checkReady()
        server.reply = { throw lost(TransportFailure.LOST) }
        s.run(recording("a"))
        s.run(recording("b"))
        assertEquals(2, server.calls)
        server.reply = { throw lost(TransportFailure.NOT_CONNECTED) }
        s.run(recording("c"))
        s.run(recording("d"))
        val waiting = failure { s.run(recording("e", long = true)) }
        assertEquals(SERVER_RETRY_HELD, waiting.message)
        assertEquals(3, server.calls)
        assertTrue(serverTries.isEmpty(), "a recording that did not go to the server has no try counted")
        s.checkReady()
        s.run(recording("f"))
        assertEquals(4, server.calls)
    }

    @Test
    fun aLongRecordingWaitsOffTheAllowedNetworkWithoutATry() = runBlocking<Unit> {
        server.allowed = false
        val waiting = failure { fallback().run(recording(long = true, fits = null)) }
        assertEquals(CaptureFailureKind.WAIT, waiting.kind)
        assertEquals(WIFI_HELD, waiting.message)
        assertTrue(waiting.waitsForNetwork)
        assertEquals(0, server.calls)
        assertEquals(0, phone.calls)
        assertTrue(serverTries.isEmpty())
    }

    @Test
    fun aLongRecordingCountsEachConnectionFailureAsATryAndWaits() = runBlocking<Unit> {
        for (failure in listOf(TransportFailure.NOT_CONNECTED, TransportFailure.LOST, TransportFailure.TIMED_OUT)) {
            server.reply = { throw lost(failure) }
            val s = fallback()
            s.checkReady()
            val waiting = failure { s.run(recording("$failure", long = true, fits = null)) }
            assertEquals(CaptureFailureKind.WAIT, waiting.kind, "$failure")
            assertEquals(SERVER_RETRY_HELD, waiting.message)
            assertTrue(waiting.waitsForNetwork)
        }
        assertEquals(listOf("NOT_CONNECTED" to now, "LOST" to now, "TIMED_OUT" to now), serverTries)
        assertEquals(0, phone.calls)
    }

    @Test
    fun theLastFailedTryReroutesALongRecordingOnceItIsRecorded() = runBlocking<Unit> {
        server.reply = { throw lost(TransportFailure.NOT_CONNECTED) }
        assertEquals(CaptureFailureKind.WAIT, failure { fallback().run(recording(long = true, tries = MAX_SERVER_TRIES - 2, fits = null)) }.kind)
        val rerouted = failure { fallback().run(recording(long = true, tries = MAX_SERVER_TRIES - 1, fits = null)) }
        assertEquals(CaptureFailureKind.REROUTE, rerouted.kind)
        triesRecorded = false
        val s = fallback()
        assertEquals(CaptureFailureKind.WAIT, failure { s.run(recording(long = true, tries = MAX_SERVER_TRIES - 1, fits = null)) }.kind)
        assertEquals(0, phone.calls)
    }

    @Test
    fun aLongRecordingPastItsTriesGoesToThePhoneWithoutTheServer() = runBlocking<Unit> {
        assertEquals("phone words", fallback().run(recording(long = true, tries = MAX_SERVER_TRIES)))
        assertEquals(0, server.calls)
    }

    @Test
    fun aLongRecordingWaitsFifteenMinutesBetweenTries() = runBlocking<Unit> {
        val s = fallback()
        val early = failure { s.run(recording(long = true, lastTry = now - SERVER_TRY_SPACING_MILLIS + 1, fits = null)) }
        assertEquals(CaptureFailureKind.WAIT, early.kind)
        assertEquals(SERVER_RETRY_HELD, early.message)
        assertTrue(early.waitsForNetwork)
        assertEquals(0, server.calls)
        assertEquals("server words", s.run(recording(long = true, lastTry = now - SERVER_TRY_SPACING_MILLIS)))
        assertEquals("server words", s.run(recording(long = true, lastTry = now + 60_000L)), "a clock set back does not hold it")
        assertEquals("server words", s.run(recording(lastTry = now)), "short recordings are not spaced")
        assertEquals(3, server.calls)
    }

    @Test
    fun aServerErrorSendsRecordingsToThePhoneWithTheNoticeUpUntilTryAgain() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        val s = fallback()
        assertNull(s.notice())
        val rerouted = failure { s.run(recording("long", long = true, fits = null)) }
        assertEquals(CaptureFailureKind.REROUTE, rerouted.kind)
        assertEquals(SpeechRoute.PHONE, s.route())
        assertEquals(CaptureNotice(SERVER_FALLBACK_NOTICE, CaptureRemedy.SERVER_CHECK), s.notice())
        assertNull(speech(CactusSTTMode.RemoteOnly).notice(), "Server Only shows the hold, not the notice")
        assertEquals("phone words", s.run(recording("next")))
        assertEquals(1, server.calls)
        check.tryAgain()
        assertNull(s.notice())
        assertEquals(SpeechRoute.SERVER_THEN_PHONE, s.route())
        val again = failure { s.run(recording("long", long = true, fits = null)) }
        assertEquals(CaptureFailureKind.REROUTE, again.kind, "the same recording failing again goes to the phone, not the failed list")
        assertEquals(CaptureNotice(SERVER_FALLBACK_NOTICE, CaptureRemedy.SERVER_CHECK), s.notice())
        phone.model = false
        assertNull(s.notice(), "with no phone model the server's hold shows instead")
        phone.model = true
        assertNull(speech(CactusSTTMode.LocalFirst).notice())
        server.url = false
        assertNull(s.notice(), "with no server set up nothing goes to it")
        server.url = true
        server.stamp = 2
        assertNull(s.notice())
        assertEquals(SpeechRoute.SERVER_THEN_PHONE, s.route())
    }

    @Test
    fun aRefusalOfTheAllowedNetworkDuringTheCallSendsShortsToThePhoneAndCountsNoTry() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.ServerNotAllowedOnNetwork("s") }
        assertEquals("phone words", fallback().run(recording()))
        val waiting = failure { fallback().run(recording(long = true, fits = null)) }
        assertEquals(CaptureFailureKind.WAIT, waiting.kind)
        assertEquals(WIFI_HELD, waiting.message)
        assertTrue(waiting.waitsForNetwork)
        assertTrue(serverTries.isEmpty())
    }

    @Test
    fun aLetThroughFromServerOnlyIsUsedUpByAFallbackRequest() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        failure { speech(CactusSTTMode.RemoteOnly).run(capture("a")) }
        check.tryAgain()
        server.reply = { "server words" }
        fallback().run(recording("a"))
        server.reply = { throw TranscriptionException.TranscriptionServiceError("HTTP 500") }
        val s = speech(CactusSTTMode.RemoteOnly)
        s.checkReady()
        assertEquals(CaptureFailureKind.HOLD, failure { s.run(capture("a")) }.kind)
    }

    @Test
    fun theServerSayingNoSpeechIsFlaggedNotSentToThePhone() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.NoSpeechDetected("empty_result", "s") }
        val flagged = failure { fallback().run(recording()) }
        assertEquals(CaptureFailureKind.FLAG, flagged.kind)
        assertEquals(CaptureFailureReason.NO_SPEECH, flagged.reason)
        assertEquals(0, phone.calls)
    }

    @Test
    fun aServerUnsetMidPassSendsAShortRecordingToThePhoneAndMakesALongOneWait() = runBlocking<Unit> {
        server.reply = { throw TranscriptionException.TranscriptionServiceUnavailable("s") }
        assertEquals("phone words", fallback().run(recording()))
        val waiting = failure { fallback().run(recording(long = true, fits = null)) }
        assertEquals(CaptureFailureKind.WAIT, waiting.kind)
        assertEquals(NO_SERVER_HELD, waiting.message)
        assertEquals(CaptureRemedy.SPEECH_SETTINGS, waiting.remedy)
    }

    @Test
    fun anUnexpectedServerFailureSendsAShortRecordingToThePhoneAndRetriesALongOne() = runBlocking<Unit> {
        server.reply = { throw IllegalStateException("client broke") }
        assertEquals("phone words", fallback().run(recording()))
        assertEquals(CaptureFailureKind.RETRY, failure { fallback().run(recording(long = true, fits = null)) }.kind)
        assertTrue(serverTries.isEmpty())
    }

    @Test
    fun withNoNetworkAtAllALongRecordingWaitsWithoutATry() = runBlocking<Unit> {
        server.network = false
        for (failure in listOf(TransportFailure.NOT_CONNECTED, TransportFailure.LOST)) {
            server.reply = { throw lost(failure) }
            val waiting = failure { fallback().run(recording(long = true, fits = null)) }
            assertEquals(CaptureFailureKind.WAIT, waiting.kind, "$failure")
            assertEquals(NO_NETWORK_HELD, waiting.message)
            assertTrue(waiting.waitsForNetwork)
        }
        assertTrue(serverTries.isEmpty())
        assertEquals("phone words", fallback().run(recording()), "a short one still goes to the phone")
    }

    @Test
    fun withNoNetworkTheLongRecordingsAfterTheFirstInAPassWaitForANetworkToo() = runBlocking<Unit> {
        server.network = false
        server.reply = { throw lost(TransportFailure.NOT_CONNECTED) }
        val s = fallback()
        s.checkReady()
        failure { s.run(recording("a", long = true, fits = null)) }
        assertEquals(NO_NETWORK_HELD, failure { s.run(recording("b", long = true, fits = null)) }.message)
        assertEquals(1, server.calls)
        server.network = true
        assertEquals(SERVER_RETRY_HELD, failure { s.run(recording("c", long = true, fits = null)) }.message)
    }

    @Test
    fun thePhoneShortOfMemoryAfterTheServerMakesOnlyThatRecordingWait() = runBlocking<Unit> {
        server.allowed = false
        val waiting = failure { fallback().run(recording(fits = { false })) }
        assertEquals(CaptureFailureKind.WAIT, waiting.kind)
        assertEquals(SHORT_OF_MEMORY, waiting.message)
        phone.reply = { throw TranscriptionException.NotEnoughMemory("m") }
        val engine = failure { fallback().run(recording()) }
        assertEquals(CaptureFailureKind.WAIT, engine.kind)
        assertEquals(SHORT_OF_MEMORY, engine.message)
        phone.reply = { throw LongFormTimeoutException("m") }
        assertEquals(CaptureFailureReason.SPEECH_TIMEOUT, failure { fallback().run(recording()) }.reason)
    }

    @Test
    fun flakyNetworkModeSendsWatchRecordingsToThePhoneAlone() = runBlocking<Unit> {
        assertEquals("phone words", fallback(flaky = true).run(recording(fromWatch = true)))
        val local = NotebookSpeech({ CactusSTTMode.LocalFirst }, phone, server, check, dictation, { true }, { now })
        phone.reply = { throw LongFormTimeoutException("m") }
        assertEquals(CaptureFailureKind.FLAG, failure { local.run(recording(fromWatch = true)) }.kind, "no server after the phone")
        assertEquals(0, server.calls)
        phone.reply = { "phone words" }
        assertEquals("server words", fallback(flaky = true).run(recording()), "recordings made on the phone still go to the server")
        assertEquals("server words", fallback().run(recording(fromWatch = true)))
        phone.model = false
        assertEquals("server words", fallback(flaky = true).run(recording(fromWatch = true)), "with no phone model the setting changes nothing")
    }

    /** A long recording is the one a watch dictation may need the server back from; a short one finishes first. */
    @Test
    fun onlyLongRecordingsAreSentAsOnesADictationCanStop() = runBlocking<Unit> {
        val seen = mutableListOf<Boolean>()
        server.during = { seen += dictation.longRecordingOnServer }
        val local = NotebookSpeech({ CactusSTTMode.LocalFirst }, phone, server, check, dictation, { false }, { now })
        phone.reply = { "" }
        for (speech in listOf(speech(CactusSTTMode.RemoteOnly), fallback(), local)) {
            speech.checkReady()
            assertEquals("server words", speech.run(recording(long = true)))
            assertEquals("server words", speech.run(recording(long = false)))
        }
        assertEquals(listOf(true, false, true, false, true, false), seen)
        assertFalse(dictation.longRecordingOnServer)
    }

    /** A recording the Notebook stops on the server (Interrupt Long Recordings, a pause) is canceled, not failed. */
    @Test
    fun aLongRecordingStoppedOnTheServerIsCanceledWithoutATry() = runBlocking<Unit>(Dispatchers.Default) {
        for (speech in listOf(speech(CactusSTTMode.RemoteOnly), fallback())) {
            server.calls = 0
            server.gate = CompletableDeferred()
            speech.checkReady()
            val outcome = async {
                try {
                    speech.run(recording(long = true))
                } catch (e: CaptureProcessingException) {
                    "failed: ${e.kind}"
                }
            }
            withTimeout(5_000) { while (server.calls == 0) delay(5) }
            outcome.cancel()
            assertFailsWith<CancellationException> { outcome.await() }
            assertTrue(serverTries.isEmpty())
            assertFalse(check.held(server.stamp))
        }
    }

    private val hangUpRoutes get() = listOf(
        "server only" to speech(CactusSTTMode.RemoteOnly),
        "server first" to fallback(),
        "after the phone" to NotebookSpeech({ CactusSTTMode.LocalFirst }, phone, server, check, dictation, { false }, { now }),
    )

    /** A watch dictation's hold stops a long recording on the server; it is sent again and nothing counts against it. */
    @Test
    fun aLongRecordingStoppedForADictationIsSentAgainWithNothingCounted() = runBlocking<Unit>(Dispatchers.Default) {
        phone.reply = { "" }
        for ((name, speech) in hangUpRoutes) {
            server.calls = 0
            server.holdFirst = true
            speech.checkReady()
            val outcome = async { runCatching { speech.run(recording(long = true)) } }
            withTimeout(5_000) { while (server.calls == 0) delay(5) }
            val hold = dictation.raise()
            withTimeout(5_000) { while (dictation.longRecordingOnServer) delay(5) }
            dictation.release(hold)
            assertEquals("server words", withTimeout(5_000) { outcome.await() }.getOrThrow(), name)
            assertEquals(2, server.calls, name)
            assertTrue(serverTries.isEmpty(), name)
            assertFalse(check.held(server.stamp), name)
            assertNull(speech.notice(), name)
        }
    }

    /** Stops [capture]'s request with a dictation hold, applies [change] while the hold is up, then ends the hold. */
    private suspend fun CoroutineScope.stoppedFor(s: NotebookSpeech, capture: SpeechCapture, change: () -> Unit): Result<String> {
        server.calls = 0
        server.holdFirst = true
        s.checkReady()
        val outcome = async { runCatching { s.run(capture) } }
        withTimeout(5_000) { while (server.calls == 0) delay(5) }
        val hold = dictation.raise()
        withTimeout(5_000) { while (dictation.longRecordingOnServer) delay(5) }
        change()
        dictation.release(hold)
        return withTimeout(5_000) { outcome.await() }
    }

    @Test
    fun aRecordingThatWaitedForADictationGoesThroughTheRulesAgainWhenTheSettingChanged() = runBlocking<Unit>(Dispatchers.Default) {
        for (start in listOf(CactusSTTMode.RemoteOnly, CactusSTTMode.RemoteFirst)) {
            var mode = start
            val s = NotebookSpeech({ mode }, phone, server, check, dictation, { false }, { now })
            val failure = stoppedFor(s, recording(long = true)) { mode = CactusSTTMode.LocalOnly }.exceptionOrNull() as CaptureProcessingException
            assertEquals(CaptureFailureKind.REROUTE, failure.kind, "$start")
            assertEquals(1, server.calls, "$start sent to the server after the setting changed")
            assertTrue(serverTries.isEmpty())
        }
        val s = speech(CactusSTTMode.RemoteOnly)
        val failure = stoppedFor(s, recording(long = true)) { server.stamp = 2 }.exceptionOrNull() as CaptureProcessingException
        assertEquals(CaptureFailureKind.REROUTE, failure.kind)
        assertEquals(1, server.calls)
    }

    @Test
    fun aShortRecordingWaitsWhileADictationHasTheServer() = runBlocking<Unit>(Dispatchers.Default) {
        val s = speech(CactusSTTMode.RemoteOnly)
        s.checkReady()
        val waiting = dictation.supersede()
        // Undispatched, so the recording reaches the hold before the next line.
        val short = async(start = CoroutineStart.UNDISPATCHED) { s.run(recording(long = false)) }
        delay(50)
        assertEquals(0, server.calls)
        dictation.release(waiting)
        assertEquals("server words", withTimeout(5_000) { short.await() })
        server.stamp = 2
        val again = dictation.supersede()
        val changed = async(start = CoroutineStart.UNDISPATCHED) { runCatching { s.run(recording(long = false)) } }
        server.stamp = 3
        dictation.release(again)
        assertEquals(CaptureFailureKind.REROUTE, (withTimeout(5_000) { changed.await() }.exceptionOrNull() as CaptureProcessingException).kind)
        assertEquals(1, server.calls)
    }
}
