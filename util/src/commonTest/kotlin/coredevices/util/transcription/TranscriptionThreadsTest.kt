package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the thread-count policy: the affinity mask wins over the online
 * count, the bound holds in both directions, and an unreadable mask
 * falls back to the online count instead of to a fixed guess.
 */
class TranscriptionThreadsTest {

    @Test
    fun maskWinsOverOnlineCount() {
        assertEquals(4, engineThreadCount(allowedCpus = 4, onlineCpus = 8))
        assertEquals(2, engineThreadCount(allowedCpus = 2, onlineCpus = 8))
    }

    @Test
    fun boundedAboveAndBelow() {
        assertEquals(MAX_ENGINE_THREADS, engineThreadCount(allowedCpus = 12, onlineCpus = 12))
        assertEquals(MAX_ENGINE_THREADS, engineThreadCount(allowedCpus = null, onlineCpus = 16))
        assertEquals(1, engineThreadCount(allowedCpus = 1, onlineCpus = 8))
        assertEquals(1, engineThreadCount(allowedCpus = null, onlineCpus = 0))
    }

    @Test
    fun unreadableOrEmptyMaskFallsBackToOnlineCount() {
        assertEquals(5, engineThreadCount(allowedCpus = null, onlineCpus = 5))
        assertEquals(5, engineThreadCount(allowedCpus = 0, onlineCpus = 5))
    }

    @Test
    fun singleThreadOverrideActsOnlyInDebugBuilds() {
        assertEquals(1, effectiveThreadCount(singleThreadOverride = true, debugBuild = true, measured = 6))
        assertEquals(6, effectiveThreadCount(singleThreadOverride = true, debugBuild = false, measured = 6))
        assertEquals(6, effectiveThreadCount(singleThreadOverride = false, debugBuild = true, measured = 6))
    }
}
