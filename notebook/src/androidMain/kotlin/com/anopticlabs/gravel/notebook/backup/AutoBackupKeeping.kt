package com.anopticlabs.gravel.notebook.backup

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/** Every backup this young is kept. */
const val KEEP_ALL_MILLIS = 2 * DAY_MILLIS

/** Up to this age the newest backup of each day is kept; beyond it, of each week, then of each month. */
const val KEEP_DAILY_MILLIS = 14 * DAY_MILLIS
const val KEEP_WEEKLY_MONTHS = 3

/**
 * The backups thinning takes out of [backups] at [now]: all from the last two days stay, then
 * the newest of each day for two weeks, of each week (from Monday) for three months, and of each
 * month after that, counted in [zone]. The newest undamaged backup, the run's own backup
 * [current], a damaged one, one marked [AutoBackupEntry.keep] and one whose last read failed
 * always stay, and none of the last three stands for its day, week or month.
 */
fun backupsToThin(backups: List<AutoBackupEntry>, now: Long, zone: TimeZone, current: String? = null): List<AutoBackupEntry> {
    val newest = backups.filter { !it.damaged }.maxByOrNull { it.createdAtMillis }
    val today = localDate(now, zone)
    val weeklyFrom = today.minus(KEEP_WEEKLY_MONTHS, DateTimeUnit.MONTH)
    val candidates = backups.filter {
        !it.damaged && !it.keep && it.unreadSinceMillis == null && now - it.createdAtMillis > KEEP_ALL_MILLIS
    }
    val picks = candidates.groupBy { e ->
        val date = localDate(e.createdAtMillis, zone)
        when {
            now - e.createdAtMillis <= KEEP_DAILY_MILLIS -> "d$date"
            date >= weeklyFrom -> "w" + date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
            else -> "m${date.year}-${date.month.ordinal}"
        }
    }.values.mapTo(HashSet()) { bucket -> bucket.maxBy { it.createdAtMillis } }
    return candidates.filter { it !in picks && it != newest && it.uri != current }
}

/** Whether [after] items are far fewer than [before]: none left of some, or a quarter gone and at least [FAR_FEWER_MIN]. */
fun isFarFewer(before: Int, after: Int): Boolean {
    val lost = before - after
    return (after == 0 && before > 0) || (lost >= FAR_FEWER_MIN && lost * 4 >= before)
}

const val FAR_FEWER_MIN = 5

private fun localDate(millis: Long, zone: TimeZone): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
