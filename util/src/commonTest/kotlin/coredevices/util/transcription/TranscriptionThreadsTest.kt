package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the thread-count policy: the affinity mask wins over the possible
 * count, the bound holds in both directions, an unreadable mask falls
 * back to the possible count instead of to a fixed guess, and the tiered
 * rule reproduces the fastest measured configuration on the two test
 * chips' core sets.
 */
class TranscriptionThreadsTest {

    // Two real topologies (maximum frequency in kHz per CPU): a 4+2+2
    // chip and a 4+3+1 chip.
    private val fourTwoTwo = mapOf(
        0 to 1_803_000L, 1 to 1_803_000L, 2 to 1_803_000L, 3 to 1_803_000L,
        4 to 2_253_000L, 5 to 2_253_000L, 6 to 2_802_000L, 7 to 2_802_000L,
    )
    private val fourThreeOne = mapOf(
        0 to 1_785_600L, 1 to 1_785_600L, 2 to 1_785_600L, 3 to 1_785_600L,
        4 to 2_496_000L, 5 to 2_496_000L, 6 to 2_496_000L, 7 to 2_995_200L,
    )

    @Test
    fun maskWinsOverPossibleCount() {
        assertEquals(4, engineThreadCount(allowedCpus = 4, possibleCpus = 8))
        assertEquals(2, engineThreadCount(allowedCpus = 2, possibleCpus = 8))
    }

    @Test
    fun boundedAboveAndBelow() {
        assertEquals(MAX_ENGINE_THREADS, engineThreadCount(allowedCpus = 12, possibleCpus = 12))
        assertEquals(MAX_ENGINE_THREADS, engineThreadCount(allowedCpus = null, possibleCpus = 16))
        assertEquals(1, engineThreadCount(allowedCpus = 1, possibleCpus = 8))
        assertEquals(1, engineThreadCount(allowedCpus = null, possibleCpus = 0))
    }

    @Test
    fun unreadableOrEmptyMaskFallsBackToPossibleCount() {
        assertEquals(3, engineThreadCount(allowedCpus = null, possibleCpus = 3))
        assertEquals(3, engineThreadCount(allowedCpus = 0, possibleCpus = 3))
    }

    @Test
    fun tieredCountFollowsTheFastestTierOnTheTwoPlusTwoPlusFourChip() {
        // All cores: the two big cores alone measured fastest.
        assertEquals(2, tieredThreadCount((0..7).toList(), fourTwoTwo))
        // OEM foreground set without the big cores: the two mid cores.
        assertEquals(2, tieredThreadCount((0..5).toList(), fourTwoTwo))
        // Little cores only: all four of them.
        assertEquals(4, tieredThreadCount((0..3).toList(), fourTwoTwo))
    }

    @Test
    fun singlePrimeCoreTakesTheNextTierAlong() {
        // A one-core fastest tier is joined by the next tier: prime plus
        // three mid cores measured fastest on this chip.
        assertEquals(4, tieredThreadCount((0..7).toList(), fourThreeOne))
        // Three little cores (the background set): all three.
        assertEquals(3, tieredThreadCount((0..2).toList(), fourThreeOne))
        // Four little plus two mid cores: the two mid cores.
        assertEquals(2, tieredThreadCount((0..5).toList(), fourThreeOne))
    }

    @Test
    fun tieredCountIsBoundedAndFallsBackWithoutTopology() {
        // Six equal cores would exceed the cap.
        assertEquals(MAX_ENGINE_THREADS, tieredThreadCount((0..5).toList(), (0..5).associateWith { 2_000_000L }))
        // No frequency readings: the allowed count under the cap.
        assertEquals(3, tieredThreadCount(listOf(0, 1, 2), emptyMap()))
        assertEquals(MAX_ENGINE_THREADS, tieredThreadCount((0..7).toList(), emptyMap()))
        // An unreadable mask is the caller's fallback, never a silent count.
        assertFailsWith<IllegalArgumentException> { tieredThreadCount(emptyList(), fourTwoTwo) }
    }

    @Test
    fun singleThreadOverrideActsOnlyInDebugBuilds() {
        assertEquals(1, effectiveThreadCount(singleThreadOverride = true, debugBuild = true, measured = 4))
        assertEquals(4, effectiveThreadCount(singleThreadOverride = true, debugBuild = false, measured = 4))
        assertEquals(4, effectiveThreadCount(singleThreadOverride = false, debugBuild = true, measured = 4))
    }
}
