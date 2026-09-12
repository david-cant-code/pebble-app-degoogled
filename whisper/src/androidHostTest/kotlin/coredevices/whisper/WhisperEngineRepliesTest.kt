package coredevices.whisper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

    /**
     * The service's reload policy keys on the exception type: only an
     * unavailable engine drops the handle and reloads, a busy handle is a
     * bug in this process, an engine error stays an engine error.
     */
    @Test
    fun eachFailureStatusMapsToTheExceptionItsCallersActOn() {
        val stale = exceptionForStatus("transcription", WhisperEngineProtocol.STATUS_STALE_HANDLE, "handle 7 was not issued by this engine process")
        assertIs<WhisperEngineUnavailableException>(stale)
        assertEquals("whisper transcription failed: handle 7 was not issued by this engine process", stale.message)
        val busy = exceptionForStatus("free", WhisperEngineProtocol.STATUS_BUSY, "handle 7 is inside another call")
        assertIs<IllegalStateException>(busy)
        assertTrue(busy !is WhisperEngineUnavailableException)
        val engine = exceptionForStatus("init", WhisperEngineProtocol.STATUS_ENGINE_ERROR, "fdopen failed")
        assertEquals(RuntimeException::class, engine::class, "an engine error is the plain exception")
        assertEquals("whisper init failed: fdopen failed", engine.message)
        assertIs<WhisperEngineUnavailableException>(exceptionForStatus("benchmark", 99, "x"), "an unknown status is not a reply to act on")
        assertIs<WhisperEngineUnavailableException>(exceptionForStatus("benchmark", WhisperEngineProtocol.STATUS_OK, "x"), "a success status is never a failure to map")
    }

    @Test
    fun aReplyPastItsBoundIsRefusedAsAnUnavailableEngine() {
        checkReplyBound("bytes", 0, WhisperEngineProtocol.MAX_TEXT_BYTES)
        checkReplyBound("bytes", WhisperEngineProtocol.MAX_TEXT_BYTES, WhisperEngineProtocol.MAX_TEXT_BYTES)
        val refused = assertFailsWith<WhisperEngineUnavailableException> {
            checkReplyBound("bytes", WhisperEngineProtocol.MAX_TEXT_BYTES + 1, WhisperEngineProtocol.MAX_TEXT_BYTES)
        }
        assertEquals("engine reply bytes of 8193 exceeds the 8192 bound", refused.message)
        assertFailsWith<WhisperEngineUnavailableException> {
            checkReplyBound("chars", Int.MAX_VALUE, WhisperEngineProtocol.MAX_MESSAGE_BYTES)
        }
    }

    @Test
    fun theAudioRegionIsSizedForTheSamplesAndNeverEmpty() {
        assertEquals(4, audioRegionBytes(0), "an empty clip still needs a region")
        assertEquals(4, audioRegionBytes(1))
        assertEquals(960_000, audioRegionBytes(240_000))
        assertEquals(Int.MAX_VALUE - 3, audioRegionBytes((Int.MAX_VALUE - 3) / 4))
        assertFailsWith<IllegalArgumentException> { audioRegionBytes(Int.MAX_VALUE / 4 + 1) }
        assertFailsWith<IllegalArgumentException> { audioRegionBytes(-1) }
    }

    /**
     * Every transaction has a bound, none is shorter than a stalled but
     * healthy phone needs, the decode's is the longest since it is the
     * slowest legitimate call, and a code the table does not know gets
     * the shortest.
     */
    @Test
    fun everyTransactionHasADeadlineAndTheDecodeHasTheLongest() {
        val codes = listOf(
            WhisperEngineProtocol.TRANSACTION_INIT, WhisperEngineProtocol.TRANSACTION_TRANSCRIBE,
            WhisperEngineProtocol.TRANSACTION_CANCEL, WhisperEngineProtocol.TRANSACTION_FREE,
            WhisperEngineProtocol.TRANSACTION_BENCHMARK, WhisperEngineProtocol.TRANSACTION_RUNTIME,
        )
        val shortest = codes.minOf(::transactionDeadline)
        assertTrue(shortest >= 15.seconds, "the shortest bound is $shortest")
        val longest = codes.maxOf(::transactionDeadline)
        assertEquals(transactionDeadline(WhisperEngineProtocol.TRANSACTION_TRANSCRIBE), longest)
        assertTrue(longest <= 5.minutes, "a hung engine is held for $longest")
        assertTrue(transactionDeadline(WhisperEngineProtocol.TRANSACTION_INIT) >= 1.minutes, "a model load reads up to a gigabyte")
        assertEquals(shortest, transactionDeadline(WhisperEngineProtocol.TRANSACTION_RUNTIME + 1), "an unknown code gets the shortest bound")
    }

    @Test
    fun cleanTextPassesThroughUntouched() {
        val text = "whisper_init_with_params failed for model fd 42 (see whisper.cpp logcat lines)"
        assertSame(text, sanitizeEngineText(text))
        assertEquals("", sanitizeEngineText(""))
        assertEquals("caf\u00e9 \u65e5\u672c", sanitizeEngineText("caf\u00e9 \u65e5\u672c"), "non-ASCII text is not a control character")
    }
}
