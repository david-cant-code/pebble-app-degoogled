package coredevices.whisper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Pins the checks the engine client applies to values read from an
 * engine reply. The engine process is untrusted by design, so each case
 * feeds the value a hostile process would send and asserts the app-side
 * outcome; the Parcel plumbing around these decisions is device-only.
 */
class WhisperEngineRepliesTest {

    @Test
    fun sampleEchoIsKeptOnlyWhenItMatchesWhatWasSent() {
        assertEquals(960_000, boundedSampleEcho(reported = 960_000, sent = 960_000))
        assertEquals(-1, boundedSampleEcho(reported = -1, sent = 960_000), "the engine never reached its input")
        assertEquals(-1, boundedSampleEcho(reported = 46, sent = 960_000), "a forged count reads as unknown")
        assertEquals(-1, boundedSampleEcho(reported = Int.MAX_VALUE, sent = 960_000))
        assertEquals(-1, boundedSampleEcho(reported = -2, sent = 960_000))
        assertEquals(0, boundedSampleEcho(reported = 0, sent = 0), "an empty clip echoes as empty")
    }

    @Test
    fun controlCharactersNeverSurviveSanitising() {
        assertEquals("a?b?c", sanitizeEngineText("a\nb\rc"))
        assertEquals("/top-app?dictation engine: forged=1", sanitizeEngineText("/top-app\ndictation engine: forged=1"))
        assertEquals("???", sanitizeEngineText("\u0000\u001b\u007f"))
        assertEquals("tab?here", sanitizeEngineText("tab\there"))
    }

    /**
     * The client reads a transcribe reply as status, sample count, payload
     * on every status, and every other reply as status, payload; the
     * service's catch-all writes by this table, so it must single out
     * exactly the transcribe code.
     */
    @Test
    fun onlyTheTranscribeReplyCarriesASampleCount() {
        val codes = listOf(
            WhisperEngineProtocol.TRANSACTION_INIT, WhisperEngineProtocol.TRANSACTION_TRANSCRIBE,
            WhisperEngineProtocol.TRANSACTION_CANCEL, WhisperEngineProtocol.TRANSACTION_FREE,
            WhisperEngineProtocol.TRANSACTION_BENCHMARK, WhisperEngineProtocol.TRANSACTION_RUNTIME,
        )
        assertEquals(
            listOf(WhisperEngineProtocol.TRANSACTION_TRANSCRIBE),
            codes.filter(WhisperEngineProtocol::replyCarriesSampleCount),
        )
    }

    @Test
    fun cleanTextPassesThroughUntouched() {
        val text = "whisper_init_with_params failed for model fd 42 (see whisper.cpp logcat lines)"
        assertSame(text, sanitizeEngineText(text))
        assertEquals("", sanitizeEngineText(""))
        assertEquals("caf\u00e9 \u65e5\u672c", sanitizeEngineText("caf\u00e9 \u65e5\u672c"), "non-ASCII text is not a control character")
    }
}
