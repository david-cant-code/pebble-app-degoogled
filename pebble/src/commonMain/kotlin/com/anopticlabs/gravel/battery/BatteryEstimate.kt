package com.anopticlabs.gravel.battery

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** What a watch's battery history says about the days ahead; the rates stay null until it says enough. */
data class BatteryEstimate(
    val percentPerDay: Double?,
    val daysPerCharge: Double?,
    val daysLeft: Double?,
    /** Hours of discharge the rates rest on. */
    val hours: Double,
)

/**
 * The drain rate is the sum of all drops over the sum of their hours, across the discharge runs of
 * the last [WINDOW]. The watch reports whole percent, so one run of a few hours reads 0 % or 2 % an
 * hour depending on where the rounding fell; summed over days the rounding evens out, and runs
 * shorter than [MIN_RUN_HOURS] say too little to count. A rise ends a run because the watch charged,
 * and so does a gap longer than [MAX_GAP_HOURS], because the phone did not see what happened in it.
 */
object BatteryEstimator {
    val WINDOW = 14.days
    const val MIN_RUN_HOURS = 3.0
    const val MAX_GAP_HOURS = 24.0

    // With less, one rounding step moves the estimate by days.
    const val MIN_TOTAL_HOURS = 12.0
    const val MIN_TOTAL_DROP = 3

    fun estimate(samples: List<BatterySample>, current: Int?, now: Instant): BatteryEstimate {
        val from = (now - WINDOW).epochSeconds
        val runs = discharges(samples.filter { it.epochSeconds >= from }.sortedBy { it.epochSeconds })
            .filter { it.hours >= MIN_RUN_HOURS }
        val hours = runs.sumOf { it.hours }
        val drop = runs.sumOf { it.drop }
        if (hours < MIN_TOTAL_HOURS || drop < MIN_TOTAL_DROP) {
            return BatteryEstimate(null, null, null, hours)
        }
        val perDay = drop / hours * 24
        return BatteryEstimate(perDay, 100 / perDay, current?.let { it / perDay }, hours)
    }

    private class Run(val drop: Int, val hours: Double)

    private fun discharges(samples: List<BatterySample>): List<Run> {
        val runs = mutableListOf<Run>()
        var start = 0
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val sample = samples[i]
            val gapHours = (sample.epochSeconds - previous.epochSeconds) / 3600.0
            if (sample.percent > previous.percent || gapHours > MAX_GAP_HOURS) {
                if (i - 1 > start) runs += run(samples[start], previous)
                start = i
            }
        }
        if (samples.size - 1 > start) runs += run(samples[start], samples.last())
        return runs
    }

    private fun run(from: BatterySample, to: BatterySample) =
        Run(from.percent - to.percent, (to.epochSeconds - from.epochSeconds) / 3600.0)
}
