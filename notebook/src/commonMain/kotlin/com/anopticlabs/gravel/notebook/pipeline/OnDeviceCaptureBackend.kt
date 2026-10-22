package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.capture.captureStageForClassify
import com.anopticlabs.gravel.notebook.llm.PROMPT_CONTENT_BUDGET
import com.anopticlabs.gravel.notebook.llm.Prompts
import com.anopticlabs.gravel.notebook.llm.capToBudget
import com.anopticlabs.gravel.notebook.notes.NoteBuilder
import com.anopticlabs.gravel.notebook.reminders.ReminderExtractor
import com.anopticlabs.gravel.notebook.reminders.ReminderIntent
import com.anopticlabs.gravel.notebook.reminders.SpokenTimeReader
import com.anopticlabs.gravel.notebook.reminders.effectiveTriggerEnabled
import com.anopticlabs.gravel.notebook.reminders.shouldParkTriggerCompletion
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.toLocalDateTime

/** The drain's held reason when memory stays short after the text model is freed. */
const val SHORT_OF_MEMORY = "too little free memory to transcribe; a smaller speech model needs less"

/** Reply tokens for the note and reminder JSON, whose answers run under a hundred tokens. */
const val REPLY_TOKENS = 512

/** The segments' texts as one transcript; whisper starts each segment's text with its own space. */
fun joinTranscript(segmentTexts: List<String>): String = segmentTexts.joinToString("").trim()

private val NON_SPEECH_TOKENS = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")

/** False for a transcript with fewer than two letters or digits outside bracketed non-speech tokens. */
fun hasSpeech(transcript: String): Boolean =
    transcript.replace(NON_SPEECH_TOKENS, "").count { it.isLetterOrDigit() } >= 2

/**
 * The capture pipeline: decode, transcribe, then a completion claim is parked without the text
 * model; otherwise the cue detector picks the reminder or note prompt for the text model. Before a
 * transcription on the phone, the text model is freed when memory is short ([SpeechCapture.phoneFits]).
 */
class OnDeviceCaptureBackend(
    private val decoder: CaptureAudioDecoder,
    private val speech: CaptureSpeech,
    private val text: CaptureText,
    private val memory: CaptureMemory,
) : CaptureBackend {

    override suspend fun checkReady() {
        speech.checkReady()
        text.checkReady()
    }

    override suspend fun process(request: CaptureRequest, onStage: (CaptureStage) -> Unit): BuiltCapture {
        onStage(CaptureStage.TRANSCRIBING)
        val pcm16 = decoder.decode(request.audio)
        val capture = SpeechCapture(
            request.id, request.phoneTried, ::phoneFits, request.markPhoneTried,
            request.long, request.fromWatch, request.serverTries, request.serverTriedAtMillis, request.markServerTry,
        )
        val transcript = speech.transcribe(pcm16, capture)
        if (!hasSpeech(transcript)) throw CaptureProcessingException(CaptureFailureKind.FLAG, "no speech was heard", reason = CaptureFailureReason.NO_SPEECH)
        val settings = request.settings
        val triggerOn = effectiveTriggerEnabled(settings.triggerEnabled, settings.triggerWord)
        shouldParkTriggerCompletion(triggerOn, settings.triggerWord, transcript)?.let { utterance ->
            onStage(CaptureStage.COMPLETING_TASK)
            return BuiltCapture.Completion(utterance)
        }
        val isReminder = ReminderIntent.looksLikeReminder(transcript)
        onStage(CaptureStage.THINKING)
        text.prepare()
        onStage(captureStageForClassify(isCompletion = false, isReminder = isReminder))
        val capped = capToBudget(transcript, PROMPT_CONTENT_BUDGET)
        return if (isReminder) {
            // The model writes the message only; the rules read the time from the whole transcript.
            val raw = text.generate(Prompts.REMINDER_MESSAGE_SYSTEM, Prompts.reminderMessagePrompt(capped), REPLY_TOKENS)
            val spoken = SpokenTimeReader.read(transcript, request.capturedAt, request.zone)
            BuiltCapture.Reminder(ReminderExtractor.proposal(raw, transcript, spoken))
        } else {
            val raw = text.generate(Prompts.NOTE_SYSTEM, Prompts.notePromptVerbatim(capped), REPLY_TOKENS)
            val note = NoteBuilder.buildNote(
                rawReply = raw,
                transcript = transcript,
                folders = request.folders,
                folderTags = request.folderTags,
                now = request.capturedAt.toLocalDateTime(request.zone),
            )
            BuiltCapture.Note(note)
        }
    }

    private suspend fun phoneFits(): Boolean {
        if (!memory.shortForSpeech()) return true
        try {
            text.release()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A model left loaded keeps memory short, which the check below reads.
            logger.w { "text model not freed: ${e::class.simpleName}" }
        }
        return !memory.shortForSpeech()
    }

    private companion object {
        val logger = Logger.withTag("OnDeviceCaptureBackend")
    }
}
