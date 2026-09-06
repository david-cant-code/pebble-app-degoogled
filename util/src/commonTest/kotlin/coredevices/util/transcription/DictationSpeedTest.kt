package coredevices.util.transcription

import com.russhwolf.settings.MapSettings
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the nudge decision (with the encoder floor counted as input), the
 * smoothing, the tracker's memory across dictations, the hold during a
 * switch, and the facts the dialog copy must state.
 */
class DictationSpeedTest {

    @Test
    fun smoothingStartsAtTheFirstSampleAndMovesByTheNewestWeight() {
        assertEquals(2.0, DictationSpeedPolicy.smoothedFactor(null, 2.0))
        val next = DictationSpeedPolicy.smoothedFactor(2.0, 4.0)
        assertEquals(2.0 + DictationSpeedPolicy.NEWEST_WEIGHT * 2.0, next, 1e-9)
    }

    @Test
    fun predictionCountsTheEncoderFloorAsInput() {
        // A full window is 15 s of audio plus the 1.28 s floor of encoder work.
        assertEquals(16.28, DictationSpeedPolicy.effectiveSeconds(WhisperSpeedCalibration.WINDOW_SECONDS), 1e-9)
        assertEquals(16.28, DictationSpeedPolicy.predictedWindowSeconds(1.0), 1e-9)
    }

    @Test
    fun nudgeOnlyWhenAFullWindowMissesTheDeadlineAndACheaperTierExists() {
        // 0.8 s per second of engine input: 13.0 s for a full window, inside 14.
        assertNull(DictationSpeedPolicy.nudgeFor("whisper-small-en", 0.8, declined = false))
        // 0.9 s per second: 14.7 s, past the deadline; the next tier is offered.
        assertEquals(
            SpeedNudge("whisper-small-en", "whisper-base-en", 0.9),
            DictationSpeedPolicy.nudgeFor("whisper-small-en", 0.9, declined = false),
        )
        assertEquals(
            SpeedNudge("whisper-base", "whisper-tiny", 1.2),
            DictationSpeedPolicy.nudgeFor("whisper-base", 1.2, declined = false),
        )
        // The tiny floor has nothing cheaper to offer.
        assertNull(DictationSpeedPolicy.nudgeFor("whisper-tiny-en", 5.0, declined = false))
        assertNull(DictationSpeedPolicy.nudgeFor("whisper-small-en", 1.0, declined = true))
        assertNull(DictationSpeedPolicy.nudgeFor("whisper-small-en", null, declined = false))
        assertNull(DictationSpeedPolicy.nudgeFor("not-a-model", 5.0, declined = false))
    }

    @Test
    fun trackerSmoothsAcrossDictationsAndRaisesTheNudge() {
        val tracker = DictationSpeedTracker(MapSettings())
        // Too little audio to say anything: ignored, a quick reply included.
        tracker.recordDecode("whisper-small-en", inputSeconds = 0.5, decodeMillis = 2000)
        tracker.recordDecode("whisper-small-en", inputSeconds = 1.5, decodeMillis = 2000)
        assertNull(tracker.factorFor("whisper-small-en"))
        assertNull(tracker.nudge.value)
        // 4 s of audio in 2 s: 2 s over 5.28 s of engine input, well inside the window.
        tracker.recordDecode("whisper-small-en", inputSeconds = 4.0, decodeMillis = 2000)
        assertEquals(2.0 / 5.28, assertNotNull(tracker.factorFor("whisper-small-en")), 1e-9)
        assertNull(tracker.nudge.value)
        // 4 s in 12 s: sample 2.27, smoothed to 0.947, which predicts 15.4 s.
        tracker.recordDecode("whisper-small-en", inputSeconds = 4.0, decodeMillis = 12000)
        assertEquals(0.94697, assertNotNull(tracker.factorFor("whisper-small-en")), 1e-4)
        assertEquals("whisper-base-en", assertNotNull(tracker.nudge.value).targetModelId)
    }

    @Test
    fun aPendingNudgeIsHeldWhileTheDialogSwitches() {
        val tracker = DictationSpeedTracker(MapSettings())
        tracker.recordDecode("whisper-small-en", inputSeconds = 4.0, decodeMillis = 12000)
        val offered = assertNotNull(tracker.nudge.value)

        tracker.beginSwitch()
        // A fast dictation lands mid-download: the factor moves, the offer does not.
        tracker.recordDecode("whisper-small-en", inputSeconds = 4.0, decodeMillis = 1000)
        assertTrue(assertNotNull(tracker.factorFor("whisper-small-en")) < offered.factor)
        assertEquals(offered, tracker.nudge.value)

        tracker.endSwitch()
        tracker.recordDecode("whisper-small-en", inputSeconds = 4.0, decodeMillis = 1000)
        val reevaluated = assertNotNull(tracker.nudge.value, "still over the deadline after one fast dictation")
        assertEquals(assertNotNull(tracker.factorFor("whisper-small-en")), reevaluated.factor, 1e-9)
        // Enough fast dictations bring the smoothed factor under the deadline and withdraw the offer.
        repeat(6) { tracker.recordDecode("whisper-small-en", inputSeconds = 4.0, decodeMillis = 1000) }
        assertNull(tracker.nudge.value)
    }

    @Test
    fun decliningIsRememberedForThatModelOnly() {
        val settings = MapSettings()
        val tracker = DictationSpeedTracker(settings)
        tracker.recordDecode("whisper-small-en", inputSeconds = 4.0, decodeMillis = 8000)
        assertNotNull(tracker.nudge.value)
        tracker.decline("whisper-small-en")
        assertNull(tracker.nudge.value)
        assertTrue(tracker.isDeclined("whisper-small-en"))
        tracker.recordDecode("whisper-small-en", inputSeconds = 4.0, decodeMillis = 9000)
        assertNull(tracker.nudge.value)
        // A different model on the same phone gets its own nudge.
        assertFalse(tracker.isDeclined("whisper-base-en"))
        tracker.recordDecode("whisper-base-en", inputSeconds = 4.0, decodeMillis = 8000)
        assertEquals("whisper-tiny-en", assertNotNull(tracker.nudge.value).targetModelId)
        // The decision survives a new tracker over the same settings.
        assertTrue(DictationSpeedTracker(settings).isDeclined("whisper-small-en"))
    }

    @Test
    fun clearDropsThePendingNudgeWithoutRecordingADecision() {
        val tracker = DictationSpeedTracker(MapSettings())
        tracker.recordDecode("whisper-small-en", inputSeconds = 4.0, decodeMillis = 8000)
        tracker.clear()
        assertNull(tracker.nudge.value)
        assertFalse(tracker.isDeclined("whisper-small-en"))
    }

    @Test
    fun copyStatesTheConsequenceTheTargetAndTheWayBack() {
        val copy = speedNudgeCopy(SpeedNudge("whisper-small-en", "whisper-base-en", 1.2))
        assertEquals("Dictation is too slow for the watch", copy.title)
        assertTrue(copy.body.contains("Whisper Small (English only) needs about 20 seconds"))
        // The window and the deadline in the copy are the firmware constants the code runs on.
        val window = WhisperSpeedCalibration.WINDOW_SECONDS.roundToInt()
        val deadline = DictationSpeedPolicy.DEADLINE_SECONDS.roundToInt()
        assertTrue(copy.body.contains("for a full $window second dictation"), copy.body)
        assertTrue(copy.body.contains("later than $deadline seconds"), copy.body)
        assertTrue(copy.body.contains("\"Error occurred. Try again.\""))
        assertTrue(copy.body.contains("Whisper Base (English only) is faster"))
        assertTrue(copy.body.contains("Manage Offline Models"))
        assertEquals("Switch to Whisper Base (English only)", copy.switchLabel)
        assertEquals("Keep Whisper Small (English only)", copy.keepLabel)
    }
}
