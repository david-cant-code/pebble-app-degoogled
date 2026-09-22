package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.llm.PROMPT_CONTENT_BUDGET
import com.anopticlabs.gravel.notebook.llm.Prompts
import com.anopticlabs.gravel.notebook.reminders.ReminderProposal
import com.anopticlabs.gravel.notebook.reminders.ReminderTimeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone

class OnDeviceCaptureBackendTest {

    private class Generation(val system: String, val user: String, val maxTokens: Int)

    private class FakeText(var reply: String = """{"title": "Buy milk", "tags": ["shopping"]}""") : CaptureText {
        val generations = mutableListOf<Generation>()
        var prepared = 0
        var ready = 0
        var released = 0
        var failRelease = false
        override suspend fun checkReady() {
            ready++
        }
        override suspend fun prepare() {
            prepared++
        }
        override suspend fun release() {
            released++
            if (failRelease) throw IllegalStateException("free refused")
        }
        override suspend fun generate(system: String, user: String, maxTokens: Int): String {
            generations += Generation(system, user, maxTokens)
            return reply
        }
    }

    /** Asks [SpeechCapture.phoneFits] first unless [askFits] is off, as a phone transcription does. */
    private class FakeSpeech(var transcript: String, val askFits: Boolean = true) : CaptureSpeech {
        var heard: ByteArray? = null
        var capture: SpeechCapture? = null
        var fits: Boolean? = null
        var ready = 0
        override suspend fun checkReady() {
            ready++
        }
        override fun route() = SpeechRoute.PHONE
        override suspend fun transcribe(pcm16: ByteArray, capture: SpeechCapture): String {
            this.capture = capture
            if (askFits) fits = capture.phoneFits()
            heard = pcm16
            return transcript
        }
    }

    private val plenty = CaptureMemory { false }

    private val decoded = byteArrayOf(1, 2, 3, 4)
    private val decoder = CaptureAudioDecoder { decoded }

    // 2026-09-22T15:00:00Z, 17:00 in Berlin.
    private val capturedAt = Instant.fromEpochMilliseconds(1_790_089_200_000L)
    private val berlin = TimeZone.of("Europe/Berlin")

    private fun request(
        settings: CaptureSettings = CaptureSettings(),
        folders: List<String> = listOf("unclassified", "shopping"),
        folderTags: Map<String, List<String>> = mapOf("shopping" to listOf("shopping")),
        id: String = "c1",
        phoneTried: Boolean = false,
        markPhoneTried: suspend () -> Unit = {},
    ) = CaptureRequest(byteArrayOf(9), capturedAt, berlin, folders, folderTags, settings, id, phoneTried, markPhoneTried)

    private suspend fun run(
        transcript: String,
        text: FakeText = FakeText(),
        settings: CaptureSettings = CaptureSettings(),
        stages: MutableList<CaptureStage> = mutableListOf(),
    ): BuiltCapture {
        val speech = FakeSpeech(transcript)
        val built = OnDeviceCaptureBackend(decoder, speech, text, plenty).process(request(settings)) { stages += it }
        assertTrue(speech.heard.contentEquals(decoded), "the speech engine did not get the decoded audio")
        return built
    }

    @Test
    fun aNoteIsTitledByTheModelWithTheTranscriptAsItsBodyAndDatedByTheCapture() = runBlocking<Unit> {
        val text = FakeText()
        val stages = mutableListOf<CaptureStage>()
        val built = assertIs<BuiltCapture.Note>(run("get milk and eggs on the way home", text, stages = stages))
        assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.THINKING, CaptureStage.CREATING_NOTE), stages)
        assertEquals("Buy milk", built.note.title)
        assertEquals("shopping", built.note.folder)
        assertEquals("2026-09-22", built.note.date)
        assertTrue(built.note.filename.endsWith("_09.22.26_1700.md"), built.note.filename)
        assertTrue(built.note.markdown.contains("get milk and eggs on the way home"))
        val generation = text.generations.single()
        assertEquals(Prompts.NOTE_SYSTEM, generation.system)
        assertEquals(Prompts.notePromptVerbatim("get milk and eggs on the way home"), generation.user)
        assertEquals(REPLY_TOKENS, generation.maxTokens)
        assertEquals(1, text.prepared)
    }

    @Test
    fun theModelSeesACappedTranscriptAndTheNoteKeepsAllOfIt() = runBlocking<Unit> {
        val transcript = "word ".repeat(PROMPT_CONTENT_BUDGET / 4)
        val text = FakeText()
        val built = assertIs<BuiltCapture.Note>(run(transcript, text))
        assertTrue(text.generations.single().user.length < transcript.length)
        assertTrue(built.note.markdown.contains(transcript.trim()))
    }

    /** The rules read the time from the moment of the recording; the model's own time is not used. */
    @Test
    fun aReminderTimeIsReadByTheRulesAgainstTheTimeOfTheRecording() = runBlocking<Unit> {
        val text = FakeText("""{"remind_in_seconds": 3600, "message": "Check the oven"}""")
        val stages = mutableListOf<CaptureStage>()
        val built = assertIs<BuiltCapture.Reminder>(run("remind me in ten minutes to check the oven", text, stages = stages))
        assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.THINKING, CaptureStage.SETTING_REMINDER), stages)
        assertEquals(ReminderTimeStatus.USABLE, built.proposal.timeStatus)
        assertEquals(capturedAt.toEpochMilliseconds() + 600_000L, built.proposal.remindAtMillis)
        assertEquals("Check the oven", built.proposal.message)
        val generation = text.generations.single()
        assertEquals(Prompts.REMINDER_MESSAGE_SYSTEM, generation.system)
        assertEquals(Prompts.reminderMessagePrompt("remind me in ten minutes to check the oven"), generation.user)
        assertEquals(REPLY_TOKENS, generation.maxTokens)
    }

    /** A clock time is read in the recording's zone, from the whole transcript rather than the copy capped for the model. */
    @Test
    fun aClockTimeIsReadInTheRecordingsZoneFromTheWholeTranscript() = runBlocking<Unit> {
        val text = FakeText("""{"message": "Call Sam"}""")
        val transcript = "remind me to call Sam " + "um ".repeat(PROMPT_CONTENT_BUDGET / 2) + "tomorrow at 9"
        val built = assertIs<BuiltCapture.Reminder>(run(transcript, text))
        assertTrue(text.generations.single().user.length < transcript.length)
        assertEquals(Instant.parse("2026-09-23T07:00:00Z").toEpochMilliseconds(), built.proposal.remindAtMillis)
    }

    @Test
    fun aTimeTheRulesCannotReadIsToCheck() = runBlocking<Unit> {
        val text = FakeText("""{"remind_in_seconds": 600, "message": "Call Sam"}""")
        val built = assertIs<BuiltCapture.Reminder>(run("remind me to call Sam after lunch", text))
        assertEquals(ReminderProposal("Call Sam", null, ReminderTimeStatus.TO_CHECK), built.proposal)
    }

    /** The claim is decided before the text model, which a completion never needs. */
    @Test
    fun aTriggerWordCaptureIsParkedWithoutTheTextModel() = runBlocking<Unit> {
        val text = FakeText()
        val stages = mutableListOf<CaptureStage>()
        val built = run("Done, fed the cat", text, CaptureSettings(triggerEnabled = true, triggerWord = "done"), stages)
        assertEquals(BuiltCapture.Completion("fed the cat"), built)
        assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.COMPLETING_TASK), stages)
        assertTrue(text.generations.isEmpty())
        assertEquals(0, text.prepared)
    }

    @Test
    fun aTriggerWordBeatsAReminderCue() = runBlocking<Unit> {
        val built = run("done remind me to call mom", FakeText(), CaptureSettings(triggerEnabled = true, triggerWord = "done"))
        assertIs<BuiltCapture.Completion>(built)
    }

    @Test
    fun theTriggerWordAloneParksAnEmptyClaim() = runBlocking<Unit> {
        assertEquals(BuiltCapture.Completion(""), run("Done.", FakeText(), CaptureSettings(triggerEnabled = true, triggerWord = "done")))
    }

    @Test
    fun withTheTriggerOnButNotSaidTheNoteIsWritten() = runBlocking<Unit> {
        assertIs<BuiltCapture.Note>(run("get milk", FakeText(), CaptureSettings(triggerEnabled = true, triggerWord = "done")))
    }

    @Test
    fun aStoredTriggerWordThatNoLongerValidatesIsOff() = runBlocking<Unit> {
        assertIs<BuiltCapture.Note>(run("note to self get milk", FakeText(), CaptureSettings(triggerEnabled = true, triggerWord = "note")))
        assertIs<BuiltCapture.Note>(run("done get milk", FakeText(), CaptureSettings(triggerEnabled = false, triggerWord = "done")))
    }

    @Test
    fun aRecordingWithNoSpeechIsFlaggedBeforeTheTextModel() = runBlocking<Unit> {
        for (heard in listOf("", "  ", "[BLANK_AUDIO]", "(wind blowing) [Music]", "a")) {
            val text = FakeText()
            val failure = assertFailsWith<CaptureProcessingException>(heard) { run(heard, text) }
            assertEquals(CaptureFailureKind.FLAG, failure.kind)
            assertEquals(CaptureFailureReason.NO_SPEECH, failure.reason)
            assertTrue(text.generations.isEmpty())
            assertEquals(0, text.prepared)
        }
    }

    @Test
    fun speechIsTwoLettersOrDigitsOutsideNonSpeechTokens() {
        assertTrue(hasSpeech("ok"))
        assertTrue(hasSpeech("[Music] call 42"))
        assertFalse(hasSpeech("(laughs) a"))
        assertFalse(hasSpeech("..."))
    }

    @Test
    fun segmentTextsJoinAsWhisperSpacedThem() {
        assertEquals("Hello there. General Kenobi.", joinTranscript(listOf(" Hello there.", " General Kenobi.", " ")))
    }

    @Test
    fun readinessAsksBothEngines() = runBlocking<Unit> {
        val speech = FakeSpeech("x")
        val text = FakeText()
        OnDeviceCaptureBackend(decoder, speech, text, plenty).checkReady()
        assertEquals(1, speech.ready)
        assertEquals(1, text.ready)
    }

    @Test
    fun theTextModelStaysLoadedWhileMemoryIsNotShort() = runBlocking<Unit> {
        val text = FakeText()
        val speech = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, speech, text, plenty).process(request()) {}
        assertEquals(0, text.released)
        assertEquals(true, speech.fits)
    }

    @Test
    fun shortMemoryFreesTheTextModelAndChecksAgain() = runBlocking<Unit> {
        val text = FakeText()
        val speech = FakeSpeech("get milk")
        val built = OnDeviceCaptureBackend(decoder, speech, text, CaptureMemory { text.released == 0 }).process(request()) {}
        assertIs<BuiltCapture.Note>(built)
        assertEquals(1, text.released)
        assertEquals(true, speech.fits)
    }

    @Test
    fun aTextModelThatCannotBeFreedLeavesTheDecisionToTheSecondCheck() = runBlocking<Unit> {
        val text = FakeText().apply { failRelease = true }
        var checks = 0
        val fitting = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, fitting, text, CaptureMemory { checks++ == 0 }).process(request()) {}
        assertEquals(true, fitting.fits)
        val short = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, short, text, CaptureMemory { true }).process(request()) {}
        assertEquals(false, short.fits)
    }

    @Test
    fun memoryStillShortAfterFreeingTheTextModelReadsAsNotFitting() = runBlocking<Unit> {
        val text = FakeText()
        val speech = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, speech, text, CaptureMemory { true }).process(request()) {}
        assertEquals(false, speech.fits)
        assertEquals(1, text.released)
    }

    @Test
    fun memoryIsReadOnlyWhenTheSpeechStepAsks() = runBlocking<Unit> {
        val text = FakeText()
        val speech = FakeSpeech("get milk", askFits = false)
        val built = OnDeviceCaptureBackend(decoder, speech, text, CaptureMemory { error("memory read") }).process(request()) {}
        assertIs<BuiltCapture.Note>(built)
        assertEquals(0, text.released)
    }

    @Test
    fun theEntrysMarkerReachesTheSpeechStep() = runBlocking<Unit> {
        var marked = 0
        val speech = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, speech, FakeText(), plenty).process(request(id = "c7", phoneTried = true, markPhoneTried = { marked++ })) {}
        val capture = assertNotNull(speech.capture)
        assertEquals("c7", capture.id)
        assertTrue(capture.phoneTried)
        capture.markPhoneTried()
        assertEquals(1, marked)
    }

    private fun CaptureRequest.copyOrigin(long: Boolean, fromWatch: Boolean) = CaptureRequest(
        audio, capturedAt, zone, folders, folderTags, settings, id, phoneTried, markPhoneTried, long, fromWatch, serverTries, serverTriedAtMillis, markServerTry,
    )

    @Test
    fun theEntrysServerTriesAndOriginReachTheSpeechStep() = runBlocking<Unit> {
        var markedAt: Long? = null
        val speech = FakeSpeech("get milk")
        val request = CaptureRequest(
            byteArrayOf(9), capturedAt, berlin, listOf("unclassified"), emptyMap(), CaptureSettings(), "c8",
            long = true, fromWatch = false, serverTries = 2, serverTriedAtMillis = 40L, markServerTry = { markedAt = it; true },
        )
        OnDeviceCaptureBackend(decoder, speech, FakeText(), plenty).process(request) {}
        val capture = assertNotNull(speech.capture)
        assertTrue(capture.long)
        assertFalse(capture.fromWatch)
        OnDeviceCaptureBackend(decoder, speech, FakeText(), plenty).process(request.copyOrigin(long = false, fromWatch = true)) {}
        assertFalse(assertNotNull(speech.capture).long)
        assertTrue(assertNotNull(speech.capture).fromWatch)
        assertEquals(2, capture.serverTries)
        assertEquals(40L, capture.serverTriedAtMillis)
        assertTrue(capture.markServerTry(77L))
        assertEquals(77L, markedAt)
    }

    @Test
    fun memoryIsShortWhenTheNeedTakesTheAvailableUnderTheThreshold() {
        assertFalse(memoryShortFor(needBytes = 100, availableBytes = 1_000, thresholdBytes = 900))
        assertTrue(memoryShortFor(needBytes = 101, availableBytes = 1_000, thresholdBytes = 900))
        assertTrue(memoryShortFor(needBytes = 0, availableBytes = 899, thresholdBytes = 900))
    }
}
