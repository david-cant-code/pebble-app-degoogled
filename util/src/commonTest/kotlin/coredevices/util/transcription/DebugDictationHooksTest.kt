package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun substituteClipIsNeverOfferedOutsideDebugBuilds() {
        assertNull(debugSubstituteClip(substituteAudio = true, debugBuild = false))
        assertNull(debugSubstituteClip(substituteAudio = false, debugBuild = true))
    }
}
