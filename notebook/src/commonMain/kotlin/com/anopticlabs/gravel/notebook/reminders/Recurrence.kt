package com.anopticlabs.gravel.notebook.reminders

import com.anopticlabs.gravel.notebook.storage.isStoredInstant
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Advances a [RecurrenceRule] in local calendar units, so a noon reminder stays at noon across a
 * DST change and a day-of-month past the end of a shorter month clamps to its last day. Every
 * occurrence is counted from the anchor, so a clamped or DST-shifted one does not move the next.
 */
object Recurrence {

    const val MAX_INTERVAL = 1000

    /** A rule with an anchor outside the stored window or an interval outside 1..[MAX_INTERVAL] is unusable. */
    fun isValid(rule: RecurrenceRule): Boolean = isStoredInstant(rule.anchorMillis) && rule.interval in 1..MAX_INTERVAL

    /** The first occurrence strictly after [afterMillis], reached by a coarse jump then single steps. */
    fun nextAfter(rule: RecurrenceRule, afterMillis: Long, zone: TimeZone): Long {
        val anchor = Instant.fromEpochMilliseconds(rule.anchorMillis)
        val after = Instant.fromEpochMilliseconds(afterMillis)
        val step = rule.interval.coerceAtLeast(1).toLong()
        val anchorLocal = anchor.toLocalDateTime(zone)

        var cycles = estimateCyclesAhead(anchor, after, rule.frequency, step)
        fun occurrence(): Instant = if (cycles == 0L) anchor else addCycles(anchorLocal, rule.frequency, cycles * step).toInstant(zone)
        var candidate = occurrence()
        while (candidate <= after) {
            cycles++
            candidate = occurrence()
        }
        return candidate.toEpochMilliseconds()
    }

    private fun addCycles(local: LocalDateTime, f: RecurrenceFrequency, n: Long): LocalDateTime {
        val unit = when (f) {
            RecurrenceFrequency.DAILY -> DateTimeUnit.DAY
            RecurrenceFrequency.WEEKLY -> DateTimeUnit.WEEK
            RecurrenceFrequency.MONTHLY -> DateTimeUnit.MONTH
            RecurrenceFrequency.YEARLY -> DateTimeUnit.YEAR
        }
        return local.date.plus(n, unit).atTime(local.time)
    }

    // Per-cycle seconds assume no UTC offset change, so the jump counts no more cycles than have passed
    // unless the offset dropped since the anchor; a drop under a cycle lands it on the next occurrence.
    private fun estimateCyclesAhead(anchor: Instant, after: Instant, f: RecurrenceFrequency, step: Long): Long {
        if (after <= anchor) return 0
        val deltaSecs = after.epochSeconds - anchor.epochSeconds
        val perCycleSecsOver = when (f) {
            RecurrenceFrequency.DAILY -> 86_400L
            RecurrenceFrequency.WEEKLY -> 86_400L * 7
            RecurrenceFrequency.MONTHLY -> 86_400L * 31
            RecurrenceFrequency.YEARLY -> 86_400L * 366
        }
        return deltaSecs / (perCycleSecsOver * step)
    }
}
