package coredevices.util.transcription

import com.russhwolf.settings.MapSettings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import io.rebble.libpebblecommon.voice.DICTATION_DEADLINE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the feed into the speed record: a successful decode lands its
 * factor under the model that ran, a blank result records nothing, a
 * model switched mid-decode is credited to the model that did the work,
 * not the one configured after it, and a decode cancelled by the next
 * session records its elapsed time only once past the deadline.
 */
class SpeedRecordTest {

    private class Harness(recordCancelledAfter: Duration = DICTATION_DEADLINE) {
        val engine = FakeWhisperEngine()
        val tracker = DictationSpeedTracker(MapSettings())
        val config = MutableStateFlow(CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = "model-a")))
        val service = WhisperTranscriptionService(
            coreConfigFlow = CoreConfigFlow(config),
            modelProvider = FakeModelProvider(),
            analytics = NoopAnalytics,
            inferenceBoost = NoOpInferenceBoost(),
            engine = engine.engine,
            speedTracker = tracker,
            debugBuild = { false },
            clearCaptures = {},
            recordCancelledAfter = recordCancelledAfter,
        )

        suspend fun awaitUntil(what: String, condition: () -> Boolean) {
            try {
                withTimeout(30.seconds) { while (!condition()) delay(10) }
            } catch (e: Exception) {
                throw AssertionError("Timed out waiting for: $what", e)
            }
        }

        fun switchTo(modelName: String) {
            config.value = config.value.copy(sttConfig = config.value.sttConfig.copy(modelName = modelName))
        }
    }

    @Test
    fun aSuccessfulDecodeRecordsItsFactorUnderTheModelThatRan() = runBlocking(Dispatchers.Default) {
        val h = Harness()
        h.engine.decodedSamples = 4 * 16_000
        h.engine.decodeMillis = 30
        h.awaitUntil("model init") { h.service.isModelReady }
        assertEquals("hello world", h.service.transcribeLocal(realPcmBytes(), sampleRate = 16_000))
        assertTrue(assertNotNull(h.tracker.factorFor("model-a")) > 0.0)
        assertNull(h.tracker.nudge.value, "an instant decode never predicts a missed window")
    }

    @Test
    fun aBlankResultRecordsNothing() = runBlocking(Dispatchers.Default) {
        val h = Harness()
        h.engine.decodedSamples = 4 * 16_000
        h.engine.decodeMillis = 30
        h.engine.reply = ""
        h.awaitUntil("model init") { h.service.isModelReady }
        h.service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        assertNull(h.tracker.factorFor("model-a"))
    }

    @Test
    fun aDecodeWithoutAReportedSampleCountRecordsNothing() = runBlocking(Dispatchers.Default) {
        val h = Harness()
        h.engine.decodeMillis = 30
        h.awaitUntil("model init") { h.service.isModelReady }
        h.service.transcribeLocal(realPcmBytes(), sampleRate = 16_000)
        assertNull(h.tracker.factorFor("model-a"))
    }

    @Test
    fun aSwitchDuringADecodeIsRecordedUnderTheModelThatDecoded() = runBlocking(Dispatchers.Default) {
        val h = Harness()
        h.engine.decodedSamples = 4 * 16_000
        h.engine.gate = CountDownLatch(1)
        h.awaitUntil("model init") { h.service.isModelReady }
        val result = async { h.service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) }
        h.awaitUntil("decode inside the engine") { h.engine.inRealTranscribe }

        h.switchTo("model-b")
        delay(300)
        h.engine.gate?.countDown()
        assertEquals("hello world", result.await())
        assertNotNull(h.tracker.factorFor("model-a"), "the decode ran on model-a")
        assertNull(h.tracker.factorFor("model-b"), "model-b has not decoded anything yet")
    }

    @Test
    fun aDecodeCancelledPastTheDeadlineRecordsItsElapsedTimeAsALowerBound() = runBlocking(Dispatchers.Default) {
        // The session coordinator cancels a decode when the watch starts its
        // next session; one that had already run past the deadline records
        // what it cost so far, which is already beyond the window.
        val h = Harness(recordCancelledAfter = 50.milliseconds)
        h.engine.decodedSamples = 4 * 16_000
        h.engine.gate = CountDownLatch(1)
        h.awaitUntil("model init") { h.service.isModelReady }
        val decode = async { h.service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) }
        h.awaitUntil("decode in flight") { h.engine.inRealTranscribe }
        delay(150)
        decode.cancel()
        h.engine.gate!!.countDown()
        runCatching { decode.await() }
        h.awaitUntil("cancelled decode unwound") { !h.engine.inRealTranscribe }
        val factor = assertNotNull(h.tracker.factorFor("model-a"), "the elapsed time is recorded as the sample")
        assertTrue(factor > 0.0)
    }

    @Test
    fun aDecodeCancelledBeforeTheDeadlineRecordsNothing() = runBlocking(Dispatchers.Default) {
        // Superseded early, its cost says nothing about a full window.
        val h = Harness(recordCancelledAfter = 10.seconds)
        h.engine.decodedSamples = 4 * 16_000
        h.engine.gate = CountDownLatch(1)
        h.awaitUntil("model init") { h.service.isModelReady }
        val decode = async { h.service.transcribeLocal(realPcmBytes(), sampleRate = 16_000) }
        h.awaitUntil("decode in flight") { h.engine.inRealTranscribe }
        delay(100)
        decode.cancel()
        h.engine.gate!!.countDown()
        runCatching { decode.await() }
        h.awaitUntil("cancelled decode unwound") { !h.engine.inRealTranscribe }
        assertNull(h.tracker.factorFor("model-a"))
    }
}
