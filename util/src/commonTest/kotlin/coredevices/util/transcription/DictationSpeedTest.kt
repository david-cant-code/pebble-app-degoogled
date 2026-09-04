package coredevices.util.transcription

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the nudge decision, the smoothing, the tracker's memory across
 * dictations, and the facts the dialog copy must state.
 */
class DictationSpeedTest {

    @Test
    fun smoothingStartsAtTheFirstSampleAndMovesByTheNewestWeight() {
        assertEquals(2.0, DictationSpeedPolicy.smoothedFactor(null, 2.0))
        val next = DictationSpeedPolicy.smoothedFactor(2.0, 4.0)
        assertEquals(2.0 + DictationSpeedPolicy.NEWEST_WEIGHT * 2.0, next, 1e-9)
    }

    @Test
    fun nudgeOnlyWhenAFullWindowMissesTheDeadlineAndACheaperTierExists() {
        // 0.9 s per second of speech: 13.5 s for a full window, inside 14.
        assertNull(DictationSpeedPolicy.nudgeFor("whisper-small-en", 0.9, declined = false))
        // 1.0 s per second: 15 s, past the deadline; the next tier is offered.
        assertEquals(
            SpeedNudge("whisper-small-en", "whisper-base-en", 1.0),
            DictationSpeedPolicy.nudgeFor("whisper-small-en", 1.0, declined = false),
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
        // Too little speech to say anything: ignored.
        tracker.recordDecode("whisper-small-en", speechSeconds = 0.5, decodeMillis = 2000)
        assertNull(tracker.factorFor("whisper-small-en"))
        assertNull(tracker.nudge.value)
        // 4 s of speech in 2 s: a factor of 0.5, well inside the window.
        tracker.recordDecode("whisper-small-en", speechSeconds = 4.0, decodeMillis = 2000)
        assertEquals(0.5, assertNotNull(tracker.factorFor("whisper-small-en")), 1e-9)
        assertNull(tracker.nudge.value)
        // 4 s in 8 s: sample 2.0, smoothed to 0.95, which predicts 14.25 s.
        tracker.recordDecode("whisper-small-en", speechSeconds = 4.0, decodeMillis = 8000)
        assertEquals(0.95, assertNotNull(tracker.factorFor("whisper-small-en")), 1e-9)
        assertEquals("whisper-base-en", assertNotNull(tracker.nudge.value).targetModelId)
    }

    @Test
    fun decliningIsRememberedForThatModelOnly() {
        val settings = MapSettings()
        val tracker = DictationSpeedTracker(settings)
        tracker.recordDecode("whisper-small-en", speechSeconds = 4.0, decodeMillis = 8000)
        assertNotNull(tracker.nudge.value)
        tracker.decline("whisper-small-en")
        assertNull(tracker.nudge.value)
        assertTrue(tracker.isDeclined("whisper-small-en"))
        tracker.recordDecode("whisper-small-en", speechSeconds = 4.0, decodeMillis = 9000)
        assertNull(tracker.nudge.value)
        // A different model on the same phone gets its own nudge.
        assertFalse(tracker.isDeclined("whisper-base-en"))
        tracker.recordDecode("whisper-base-en", speechSeconds = 4.0, decodeMillis = 8000)
        assertEquals("whisper-tiny-en", assertNotNull(tracker.nudge.value).targetModelId)
        // The decision survives a new tracker over the same settings.
        assertTrue(DictationSpeedTracker(settings).isDeclined("whisper-small-en"))
    }

    @Test
    fun clearDropsThePendingNudgeWithoutRecordingADecision() {
        val tracker = DictationSpeedTracker(MapSettings())
        tracker.recordDecode("whisper-small-en", speechSeconds = 4.0, decodeMillis = 8000)
        tracker.clear()
        assertNull(tracker.nudge.value)
        assertFalse(tracker.isDeclined("whisper-small-en"))
    }

    @Test
    fun copyStatesTheConsequenceTheTargetAndTheWayBack() {
        val copy = speedNudgeCopy(SpeedNudge("whisper-small-en", "whisper-base-en", 1.2))
        assertEquals("Dictation is too slow for the watch", copy.title)
        assertTrue(copy.body.contains("Whisper Small (English only) needs about 18 seconds"))
        assertTrue(copy.body.contains("\"Error occurred. Try again.\""))
        assertTrue(copy.body.contains("Whisper Base (English only) is faster"))
        assertTrue(copy.body.contains("Manage Offline Models"))
        assertEquals("Switch to Whisper Base (English only)", copy.switchLabel)
        assertEquals("Keep Whisper Small (English only)", copy.keepLabel)
    }
}
