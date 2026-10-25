package com.anopticlabs.gravel.battery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class BatteryEstimateTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val hour = 3600L

    /** One sample per (hours before [now], percent) pair. */
    private fun samples(vararg points: Pair<Double, Int>) =
        points.map { (hoursAgo, percent) -> BatterySample(now.epochSeconds - (hoursAgo * hour).toLong(), percent) }

    private fun assertClose(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertEquals(expected, actual, 0.001)
    }

    @Test
    fun steadyDischarge() {
        // 100 -> 90 over 20 hours: half a percent an hour.
        val history = (0..10).map { (40.0 - 2 * it) to 100 - it }.toTypedArray()
        val estimate = BatteryEstimator.estimate(samples(*history), current = 90, now)
        assertClose(12.0, estimate.percentPerDay)
        assertClose(100 / 12.0, estimate.daysPerCharge)
        assertClose(7.5, estimate.daysLeft)
        assertClose(20.0, estimate.hours)
    }

    @Test
    fun chargingEndsARunAndIsNotCountedAsDrain() {
        val history = samples(
            30.0 to 60, 20.0 to 50, // 10 % in 10 hours
            19.0 to 80, 18.0 to 100, // charging
            8.0 to 95, // 5 % in 10 hours
        )
        val estimate = BatteryEstimator.estimate(history, current = 95, now)
        assertClose(15.0 / 20 * 24, estimate.percentPerDay)
        assertClose(20.0, estimate.hours)
    }

    @Test
    fun runsShorterThanThreeHoursDoNotCount() {
        val history = samples(
            30.0 to 100, 18.0 to 94, // 6 % in 12 hours
            17.0 to 100, 15.0 to 90, // a heavy 2 hours after charging
        )
        val estimate = BatteryEstimator.estimate(history, current = 90, now)
        assertClose(6.0 / 12 * 24, estimate.percentPerDay)
        assertClose(12.0, estimate.hours)
    }

    @Test
    fun aLongGapEndsARun() {
        // 30 hours without a reading: the phone did not see whether the watch charged meanwhile.
        val history = samples(70.0 to 80, 40.0 to 60, 28.0 to 54)
        val estimate = BatteryEstimator.estimate(history, current = 54, now)
        assertClose(6.0 / 12 * 24, estimate.percentPerDay)
    }

    @Test
    fun ratesAreTheTotalDropOverTheTotalHours() {
        // Alone each run reads 0.1, 0.3 or 0.2 % an hour, depending on where the whole percents fell.
        val history = samples(
            70.0 to 100, 60.0 to 99,
            59.0 to 100, 49.0 to 97,
            48.0 to 100, 38.0 to 98,
        )
        assertClose(6.0 / 30 * 24, BatteryEstimator.estimate(history, current = 98, now).percentPerDay)
    }

    @Test
    fun tooLittleHistoryGivesNoRates() {
        val estimate = BatteryEstimator.estimate(samples(8.0 to 80, 2.0 to 78), current = 78, now)
        assertNull(estimate.percentPerDay)
        assertNull(estimate.daysPerCharge)
        assertNull(estimate.daysLeft)
        assertClose(6.0, estimate.hours)
    }

    @Test
    fun fewerThanTwelveHoursGiveNoRate() {
        val estimate = BatteryEstimator.estimate(samples(10.0 to 90, 1.0 to 84), current = 84, now)
        assertNull(estimate.percentPerDay)
        assertClose(9.0, estimate.hours)
    }

    @Test
    fun lessThanThreePercentGivesNoRate() {
        val estimate = BatteryEstimator.estimate(samples(20.0 to 90, 2.0 to 88), current = 88, now)
        assertNull(estimate.percentPerDay)
        assertClose(18.0, estimate.hours)
    }

    @Test
    fun onlyTheWindowCounts() {
        val old = (15.days.inWholeHours + 20).toDouble()
        val history = samples(old to 100, old - 12 to 50, 20.0 to 80, 8.0 to 76)
        val estimate = BatteryEstimator.estimate(history, current = 76, now)
        assertClose(4.0 / 12 * 24, estimate.percentPerDay)
    }

    @Test
    fun noCurrentLevelNoTimeLeft() {
        val estimate = BatteryEstimator.estimate(samples(20.0 to 90, 8.0 to 84), current = null, now)
        assertClose(12.0, estimate.percentPerDay)
        assertNull(estimate.daysLeft)
    }

    @Test
    fun orderOfTheSamplesDoesNotMatter() {
        val history = samples(20.0 to 90, 14.0 to 87, 8.0 to 84)
        val estimate = BatteryEstimator.estimate(history.reversed(), current = 84, now)
        assertClose(12.0, estimate.percentPerDay)
    }
}
