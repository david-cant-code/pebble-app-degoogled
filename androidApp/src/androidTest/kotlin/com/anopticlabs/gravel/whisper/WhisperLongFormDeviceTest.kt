package com.anopticlabs.gravel.whisper

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.coreapp.testsupport.NoopAnalytics
import coredevices.coreapp.testsupport.ReadOnlyModelPathProvider
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.util.transcription.NoOpInferenceBoost
import coredevices.util.transcription.WhisperTranscriptionService
import coredevices.whisper.WhisperEngineClient
import coredevices.whisper.isWhisperSupported
import coredevices.whisper.whisperCancel
import coredevices.whisper.whisperFree
import coredevices.whisper.whisperInit
import coredevices.whisper.whisperTranscribeLongForm
import io.rebble.libpebblecommon.voice.DICTATION_DEADLINE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * On-device guard for long-form transcription on the real engine: a
 * recording of several minutes crosses the process boundary as timestamped
 * segments in one call, a cancel stops the call and a call started again
 * from the beginning gives the uninterrupted call's transcript, a call past
 * its deadline fails as a timeout, and through the service a dictation
 * that arrives mid-recording is answered inside the watch's deadline while
 * the recording, started again, still ends in the uninterrupted transcript.
 * The recording is the bundled evaluation clips joined with silence. Uses
 * the installed base-en model and never downloads. Run on its own, against
 * a persistent install with the model:
 *   adb shell am instrument -w \
 *     -e class com.anopticlabs.gravel.whisper.WhisperLongFormDeviceTest \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class WhisperLongFormDeviceTest {

    private companion object {
        const val TAG = "LongFormDevice"
        const val MODEL = "whisper-base-en"
        const val THREADS = 4
        const val ROUNDS = 3
        const val GAP_SECONDS = 3
        const val KEYWORD = "shrimp"

        /** Words said this many times in every round, and heard the same way in every round on base.en. */
        val PER_ROUND = mapOf(
            "tommy" to 1, "julian" to 1, "spanish" to 1, "argentinian" to 1, "luxurious" to 1,
            "invites" to 1, "shopping" to 1, "danny" to 1, "breaded" to 1, "finger" to 2, "rings" to 2,
        )
        const val DICTATION_ASSET = "eval_shopping_list_shrimp.raw"
        val CLIPS = listOf(
            "eval_note_long_half_sizes.raw",
            "eval_reminder_11am_tomorrow.raw",
            "eval_text_eric_shrimp.raw",
            "eval_note_danny_lacurious.raw",
            "eval_reminder_30min.raw",
            DICTATION_ASSET,
        )
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("[$TAG] $line")
    }

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    /** The clips joined with silence, [ROUNDS] times over: a little over four minutes. */
    private fun recording(): ByteArray {
        val gap = ByteArray(GAP_SECONDS * WhisperLongForm.SAMPLE_RATE * 2)
        val out = ByteArrayOutputStream()
        repeat(ROUNDS) { CLIPS.forEach { out.write(asset(it)); out.write(gap) } }
        return out.toByteArray()
    }

    private fun audioMillis(pcm16: ByteArray): Long = pcm16.size / 2 * 1000L / WhisperLongForm.SAMPLE_RATE

    private fun provider(): ReadOnlyModelPathProvider {
        assertTrue(WhisperEngineClient.isAttached, "the engine client was not attached in the application's onCreate")
        Assume.assumeTrue("engine unsupported on this device", isWhisperSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ReadOnlyModelPathProvider(File(context.filesDir, "models"), MODEL)
        Assume.assumeTrue("$MODEL not installed; tests never download", provider.isModelDownloaded(MODEL))
        return provider
    }

    private fun describe(segments: List<WhisperSegment>): String =
        segments.joinToString("\n") { "  [${it.startMillis}..${it.endMillis}] ${it.text}" }

    /** The keyword counts against [PER_ROUND], logged: whisper's own window boundaries can change them. */
    private fun reportWordCounts(label: String, segments: List<WhisperSegment>) {
        val text = segments.joinToString(" ") { it.text }.lowercase()
        val expected = PER_ROUND.mapValues { it.value * ROUNDS }
        val counts = PER_ROUND.mapValues { (word, _) -> Regex("\\b${word}s?\\b").findAll(text).count() }
        val off = counts.filter { (word, count) -> count != expected[word] }
        log("$label: keyword counts ${if (off.isEmpty()) "as said" else "off: $off of $expected"}")
    }

    /**
     * In order with no stretch transcribed twice, inside the audio, and
     * the keyword heard in the last round, so the tail was decoded.
     */
    private fun assertTranscribesTheRecording(segments: List<WhisperSegment>, audioMillis: Long) {
        assertTrue(segments.isNotEmpty(), "no segments")
        segments.zipWithNext().forEach { (a, b) -> assertTrue(b.startMillis >= a.endMillis, "overlap or disorder: $a then $b") }
        assertTrue(segments.all { it.endMillis <= audioMillis }, "a segment past the audio")
        val lastRound = audioMillis * (ROUNDS - 1) / ROUNDS
        assertTrue(
            segments.any { it.startMillis >= lastRound && it.text.lowercase().contains(KEYWORD) },
            "'$KEYWORD' not heard after $lastRound ms",
        )
    }

    @Test
    fun aRecordingCrossesTheBoundaryAsTimestampedSegments() {
        val path = runBlocking { provider().getModelPath(MODEL) }
        val pcm16 = recording()
        val handle = whisperInit(path)
        try {
            val started = TimeSource.Monotonic.markNow()
            val result = whisperTranscribeLongForm(handle, pcm16, THREADS, "en", 1L)
            log("${audioMillis(pcm16)} ms of audio in ${started.elapsedNow()}, ${result.segments.size} segments\n${describe(result.segments)}")
            assertTrue(result.complete)
            assertTranscribesTheRecording(result.segments, audioMillis(pcm16))
            reportWordCounts("one call", result.segments)
        } finally {
            whisperFree(handle)
        }
    }

    /** A stopped call keeps nothing; the call made again gives exactly what one uninterrupted call gives. */
    @Test
    fun aCanceledCallIsMadeAgainAndMatchesTheUninterruptedCall() {
        val path = runBlocking { provider().getModelPath(MODEL) }
        val pcm16 = recording()
        val handle = whisperInit(path)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val uninterrupted = whisperTranscribeLongForm(handle, pcm16, THREADS, "en", 2L)
            val first = executor.submit<WhisperLongFormResult> { whisperTranscribeLongForm(handle, pcm16, THREADS, "en", 3L) }
            // Past the first windows on the Pixel 6 Pro (about 8 s a window on four threads).
            Thread.sleep(20_000)
            val canceled = TimeSource.Monotonic.markNow()
            whisperCancel(3L)
            val stopped = first.get()
            val stopLatency = canceled.elapsedNow()
            log("stopped $stopLatency after the cancel, complete=${stopped.complete}")
            assertFalse(stopped.complete, "the call ran to the end; lengthen the recording or cancel sooner")
            assertEquals(emptyList(), stopped.segments)
            assertTrue(stopLatency < 5.seconds, "the call took $stopLatency to stop")

            val again = whisperTranscribeLongForm(handle, pcm16, THREADS, "en", 4L)
            assertTrue(again.complete)
            assertEquals(uninterrupted.segments, again.segments, "the call made again differs from the uninterrupted one")
        } finally {
            executor.shutdownNow()
            whisperFree(handle)
        }
    }

    /**
     * How long a call takes to stop when the cancel lands at its start, where whisper checks for none
     * (KNOWN_ISSUES): logged for the configured language and for detection, bounded by the service's
     * long-form unwind bound.
     */
    @Test
    fun aCancelAtACallsStartIsLoggedAndStaysInsideTheUnwindBound() {
        val path = runBlocking { provider().getModelPath(MODEL) }
        val pcm16 = recording()
        val handle = whisperInit(path)
        val executor = Executors.newSingleThreadExecutor()
        try {
            for ((callId, language) in listOf(6L to "en", 7L to null)) {
                val started = TimeSource.Monotonic.markNow()
                val call = executor.submit<WhisperLongFormResult> { whisperTranscribeLongForm(handle, pcm16, THREADS, language, callId) }
                Thread.sleep(50)
                whisperCancel(callId)
                val stopped = call.get()
                val stopTook = started.elapsedNow()
                log("a cancel 50 ms into a call (language ${language ?: "detected"}) stopped it after $stopTook, complete=${stopped.complete}")
                assertFalse(stopped.complete)
                assertTrue(stopTook < 60.seconds, "the call took $stopTook to stop")
            }
        } finally {
            executor.shutdownNow()
            whisperFree(handle)
        }
    }

    /** The engine process a long-form call's deadline ended reads as a timeout, which the service does not retry. */
    @Test
    fun aCallPastItsDeadlineFailsAsATimeout() {
        val path = runBlocking { provider().getModelPath(MODEL) }
        val pcm16 = recording()
        val handle = whisperInit(path)
        WhisperEngineClient.transactionDeadlineCapForTests = 2.seconds
        try {
            val failed = assertFailsWith<WhisperEngineTimeoutException> {
                whisperTranscribeLongForm(handle, pcm16, THREADS, "en", 5L)
            }
            log("timed out: ${failed.message}")
        } finally {
            WhisperEngineClient.transactionDeadlineCapForTests = null
            runCatching { whisperFree(handle) }
        }
    }

    /**
     * Both the session-open preemption and the transcription backstop:
     * the first dictation opens its session a few seconds before it
     * transcribes, as a watch recording does, the second only
     * transcribes.
     */
    @Test
    fun aDictationDuringARecordingMeetsTheWatchDeadline() = runBlocking {
        val provider = provider()
        val pcm16 = recording()
        val service = WhisperTranscriptionService(
            coreConfigFlow = CoreConfigFlow(
                MutableStateFlow(CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = MODEL))),
            ),
            modelProvider = provider,
            analytics = NoopAnalytics,
            inferenceBoost = NoOpInferenceBoost(),
        )
        withTimeout(2.minutes) { while (!service.isModelReady) delay(200) }
        val dictation = asset(DICTATION_ASSET)
        val uninterrupted = withContext(Dispatchers.Default) { service.transcribeLongForm(pcm16) }

        val longForm = async(Dispatchers.Default) { service.transcribeLongForm(pcm16) }
        // Past the first windows, so the stop throws finished work away.
        delay(12_000)
        service.earlyInit()
        delay(3_000)
        val withSession = TimeSource.Monotonic.markNow()
        val first = service.transcribeLocal(dictation, sampleRate = WhisperLongForm.SAMPLE_RATE)
        val withSessionWait = withSession.elapsedNow()

        delay(4_000)
        val backstop = TimeSource.Monotonic.markNow()
        val second = service.transcribeLocal(dictation, sampleRate = WhisperLongForm.SAMPLE_RATE)
        val backstopWait = backstop.elapsedNow()
        log("dictation after a session open: $withSessionWait ('$first'); dictation alone: $backstopWait ('$second')")
        assertTrue(first.lowercase().contains(KEYWORD) && second.lowercase().contains(KEYWORD))
        assertTrue(withSessionWait < DICTATION_DEADLINE, "the dictation after its session opened took $withSessionWait")
        assertTrue(backstopWait < DICTATION_DEADLINE, "the dictation without a session took $backstopWait")

        val segments = withTimeout(10.minutes) { longForm.await() }
        log("recording finished: ${segments.size} segments\n${describe(segments)}")
        assertTranscribesTheRecording(segments, audioMillis(pcm16))
        assertEquals(uninterrupted, segments, "the recording started again differs from the uninterrupted one")
    }
}
