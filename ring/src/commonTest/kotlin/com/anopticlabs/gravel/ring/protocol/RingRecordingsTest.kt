package com.anopticlabs.gravel.ring.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RingRecordingsTest {

    private fun part(startIndex: Long, final: Boolean, samples: IntArray, rate: Int = 10_000, clock: Long? = null) =
        RingCollection(RingAudio(rate, samples), startIndex, final, clock, if (clock == null) null else U32_MAX)

    private val fiveMinutes = 5 * 60 * 1000L

    private fun assemble(vararg collections: Pair<Int, RingCollection>, maxDurationMillis: Long = fiveMinutes) =
        assembleRingRecordings(collections.toList(), maxDurationMillis)

    @Test
    fun aSingleFinalCollectionIsARecordingCenteredOnZero() {
        val assembly = assemble(7 to part(7, true, intArrayOf(100, 200, 300), clock = 1_800_000_000))

        val (indexes, recording) = assembly.finished.single()
        assertEquals(listOf(7), indexes)
        assertContentEquals(shortArrayOf(-100, 0, 100), recording.samples)
        assertEquals(1_800_000_000, recording.capturedAtUnixSeconds)
        assertTrue(assembly.pending.isEmpty() && assembly.rejected.isEmpty())
    }

    @Test
    fun partsAreJoinedInOrderAndDatedByTheFirst() {
        val assembly = assemble(
            5 to part(5, false, intArrayOf(10, 20), clock = 100),
            6 to part(5, false, intArrayOf(30)),
            7 to part(5, true, intArrayOf(40), clock = 300),
        )

        val (indexes, recording) = assembly.finished.single()
        assertEquals(listOf(5, 6, 7), indexes)
        assertContentEquals(shortArrayOf(-15, -5, 5, 15), recording.samples)
        assertEquals(100, recording.capturedAtUnixSeconds)
    }

    @Test
    fun aRecordingContinuesAcrossTheIndexWrap() {
        val assembly = assemble(65535 to part(65535, false, intArrayOf(1)), 0 to part(65535, true, intArrayOf(3)))

        assertEquals(listOf(65535, 0), assembly.finished.single().first)
    }

    @Test
    fun theGroupStillOpenAtTheEndIsPending() {
        val assembly = assemble(5 to part(5, true, intArrayOf(1)), 6 to part(6, false, intArrayOf(2)), 7 to part(6, false, intArrayOf(3)))

        assertEquals(listOf(5), assembly.finished.single().first)
        assertEquals(listOf(6, 7), assembly.pending)
    }

    @Test
    fun aGroupTheNextCollectionDoesNotContinueIsRejectedAsIncomplete() {
        val gap = assemble(5 to part(5, false, intArrayOf(1)), 7 to part(5, true, intArrayOf(2)))
        val newRecording = assemble(5 to part(5, false, intArrayOf(1)), 6 to part(6, true, intArrayOf(2)))

        assertEquals(listOf(listOf(5) to RingRejection.INCOMPLETE), gap.rejected)
        assertEquals(listOf(7), gap.finished.single().first)
        assertEquals(listOf(listOf(5) to RingRejection.INCOMPLETE), newRecording.rejected)
        assertEquals(listOf(6), newRecording.finished.single().first)
    }

    @Test
    fun partsLeftFromBeforeTheRingRestartedItsIndexesAreNotMergedIntoANewRecording() {
        val assembly = assemble(
            5 to part(5, false, intArrayOf(1)),
            6 to part(5, false, intArrayOf(2)),
            5 to part(5, true, intArrayOf(3)),
        )

        assertEquals(listOf(listOf(5, 6) to RingRejection.INCOMPLETE), assembly.rejected)
        assertEquals(listOf(5), assembly.finished.single().first)
        assertEquals(1, assembly.finished.single().second.samples.size)
    }

    @Test
    fun aStartIndexSeenAgainAfterItsFinalPartStartsANewRecording() {
        val assembly = assemble(5 to part(5, true, intArrayOf(1)), 6 to part(5, true, intArrayOf(2)))

        assertEquals(listOf(listOf(5), listOf(6)), assembly.finished.map { it.first })
    }

    @Test
    fun ratesWithinOnePercentAreJoinedAtTheFirstPartsRate() {
        val assembly = assemble(5 to part(5, false, intArrayOf(1), rate = 10_000), 6 to part(5, true, intArrayOf(2), rate = 10_100))

        assertEquals(10_000, assembly.finished.single().second.sampleRate)
    }

    @Test
    fun ratesFurtherApartAreRejectedTogether() {
        val assembly = assemble(5 to part(5, false, intArrayOf(1), rate = 10_000), 6 to part(5, true, intArrayOf(2), rate = 10_101))

        assertEquals(listOf(listOf(5, 6) to RingRejection.MIXED_SAMPLE_RATES), assembly.rejected)
        assertTrue(assembly.finished.isEmpty())
    }

    @Test
    fun aRecordingLongerThanTheLimitIsRejected() {
        val tenSeconds = IntArray(100_000)

        val over = assemble(5 to part(5, true, tenSeconds), maxDurationMillis = 9_999)
        val exact = assemble(5 to part(5, true, tenSeconds), maxDurationMillis = 10_000)

        assertEquals(listOf(listOf(5) to RingRejection.TOO_LONG), over.rejected)
        assertEquals(10_000, exact.finished.single().second.durationMillis)
    }

    @Test
    fun aTapWithNoSamplesIsAnEmptyRecording() {
        val recording = assemble(5 to part(5, true, intArrayOf())).finished.single().second

        assertEquals(0, recording.samples.size)
        assertEquals(0, recording.durationMillis)
    }

    @Test
    fun samplesFarFromTheMeanAreClampedToSixteenBits() {
        val recording = assemble(5 to part(5, true, intArrayOf(0, 0, 65535))).finished.single().second

        assertContentEquals(shortArrayOf(-21845, -21845, 32767), recording.samples)
    }

    @Test
    fun anIndexOutsideSixteenBitsOrALimitBeyondOneArrayIsRefused() {
        assertFailsWith<IllegalArgumentException> { assemble(65536 to part(5, true, intArrayOf())) }
        assertFailsWith<IllegalArgumentException> { assemble(maxDurationMillis = Long.MAX_VALUE) }
    }
}
