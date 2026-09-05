package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Pins that the debug dictation hooks act only when both the flag and the
 * debug build are present: a stored flag in a release install is inert.
 */
class DebugDictationHooksTest {

    @Test
    fun slowDecodeHoldsOnlyInDebugBuilds() {
        assertEquals(DEBUG_SLOW_DECODE_DELAY, debugDecodeDelay(slowDecode = true, debugBuild = true))
        assertEquals(Duration.ZERO, debugDecodeDelay(slowDecode = true, debugBuild = false))
        assertEquals(Duration.ZERO, debugDecodeDelay(slowDecode = false, debugBuild = true))
    }

    @Test
    fun captureDumpWritesOnlyInDebugBuilds() {
        assertTrue(debugCaptureDumpApplies(captureDump = true, debugBuild = true))
        assertFalse(debugCaptureDumpApplies(captureDump = true, debugBuild = false))
        assertFalse(debugCaptureDumpApplies(captureDump = false, debugBuild = true))
    }

    @Test
    fun capturesAreClearedWhenTheHookGoesOffAndOnceAtStartWhenItIsOff() {
        assertTrue(captureDumpShouldClear(wasOn = null, on = false), "first emission with the hook off")
        assertFalse(captureDumpShouldClear(wasOn = null, on = true))
        assertTrue(captureDumpShouldClear(wasOn = true, on = false), "the toggle went off")
        assertFalse(captureDumpShouldClear(wasOn = false, on = false), "already known to be off")
        assertFalse(captureDumpShouldClear(wasOn = true, on = true))
    }

    @Test
    fun substituteClipIsNeverOfferedOutsideDebugBuilds() {
        assertNull(debugSubstituteClip(substituteAudio = true, debugBuild = false))
        assertNull(debugSubstituteClip(substituteAudio = false, debugBuild = true))
    }
}
