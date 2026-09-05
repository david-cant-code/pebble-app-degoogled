package io.rebble.libpebblecommon.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Pins the transcript bound every result passes before it is encoded for the watch. */
class TranscriptionResultBoundsTest {
    private fun words(count: Int, word: String = "ok") = List(count) { TranscriptionWord(word, 0.9f) }

    @Test
    fun resultsInsideTheBoundsPassUnchanged() {
        val atTheLimit = TranscriptionResult.Success(words(MAX_TRANSCRIPT_WORDS, "x".repeat(MAX_TRANSCRIPT_WORD_BYTES)))
        assertEquals(atTheLimit, atTheLimit.boundedForProtocol())
        assertEquals(TranscriptionResult.Failed, TranscriptionResult.Failed.boundedForProtocol())
        val error = TranscriptionResult.Error("engine")
        assertEquals(error, error.boundedForProtocol())
    }

    @Test
    fun tooManyWordsOrATooLongWordBecomeAnError() {
        assertIs<TranscriptionResult.Error>(TranscriptionResult.Success(words(MAX_TRANSCRIPT_WORDS + 1)).boundedForProtocol())
        assertIs<TranscriptionResult.Error>(
            TranscriptionResult.Success(words(1, "x".repeat(MAX_TRANSCRIPT_WORD_BYTES + 1))).boundedForProtocol(),
        )
        // The bound is on bytes, so a multi-byte word counts its encoding.
        assertIs<TranscriptionResult.Error>(
            TranscriptionResult.Success(words(1, "é".repeat(MAX_TRANSCRIPT_WORD_BYTES / 2 + 1))).boundedForProtocol(),
        )
    }
}
