package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidateContainsSpeechTest {
    @Test
    fun blankResultThrowsEmptyResult() {
        // Mirrors the cactus "completed but blank" case from HARD-324 that must trigger fallback.
        val e = assertFailsWith<TranscriptionException.NoSpeechDetected> {
            validateContainsSpeech("", modelUsed = "parakeet")
        }
        assertEquals("empty_result", e.type)
        assertEquals("parakeet", e.modelUsed)
    }

    @Test
    fun nullResultThrowsEmptyResult() {
        val e = assertFailsWith<TranscriptionException.NoSpeechDetected> {
            validateContainsSpeech(null, modelUsed = null)
        }
        assertEquals("empty_result", e.type)
    }

    @Test
    fun singleCharThrowsTooShort() {
        val e = assertFailsWith<TranscriptionException.NoSpeechDetected> {
            validateContainsSpeech("a", modelUsed = null)
        }
        assertEquals("too_short", e.type)
    }

    @Test
    fun onlyNonSpeechTokensThrows() {
        val e = assertFailsWith<TranscriptionException.NoSpeechDetected> {
            validateContainsSpeech("[BLANK_AUDIO]", modelUsed = null)
        }
        assertEquals("non_speech_tokens", e.type)
    }

    @Test
    fun noiseWithoutLettersThrowsStuttersOrNoise() {
        val e = assertFailsWith<TranscriptionException.NoSpeechDetected> {
            validateContainsSpeech("...", modelUsed = null)
        }
        assertEquals("stutters_or_noise", e.type)
    }

    @Test
    fun realSpeechPasses() {
        // Should not throw.
        validateContainsSpeech("hello there", modelUsed = "parakeet")
    }

    // Whisper-shaped non-speech outputs: the engine emits bracketed and
    // parenthesized event tokens and musical-note runs on non-speech audio;
    // all of them must read as no-speech, not as dictated text.

    @Test
    fun whisperParenthesizedEventTokensThrow() {
        val e = assertFailsWith<TranscriptionException.NoSpeechDetected> {
            validateContainsSpeech("(wind blowing) (birds chirping)", modelUsed = "whisper-base-en")
        }
        assertEquals("non_speech_tokens", e.type)
    }

    @Test
    fun whisperMusicTokenThrows() {
        val e = assertFailsWith<TranscriptionException.NoSpeechDetected> {
            validateContainsSpeech("[MUSIC]", modelUsed = "whisper-base-en")
        }
        assertEquals("non_speech_tokens", e.type)
    }

    @Test
    fun whisperMusicalNoteRunThrows() {
        val e = assertFailsWith<TranscriptionException.NoSpeechDetected> {
            validateContainsSpeech("\u266a\u266a\u266a", modelUsed = "whisper-base-en")
        }
        assertEquals("stutters_or_noise", e.type)
    }

    @Test
    fun speechSurroundedByEventTokensPasses() {
        // Real dictation with an incidental event token must survive the
        // guard; only pure non-speech output is rejected.
        validateContainsSpeech("(sighs) add milk to the list", modelUsed = "whisper-base-en")
    }
}
