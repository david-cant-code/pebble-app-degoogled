package com.anopticlabs.gravel.notebook.backup

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [backupsToThin] and [isFarFewer] at a fixed moment, Monday 2026-06-15 12:00 UTC. */
class AutoBackupKeepingTest {

    private val zone = TimeZone.UTC
    private val now = at(2026, 6, 15, 12)

    private fun at(year: Int, month: Int, day: Int, hour: Int) = LocalDateTime(year, month, day, hour, 0).toInstant(zone).toEpochMilliseconds()

    private fun entry(id: String, createdAt: Long, damaged: Boolean = false, keep: Boolean = false) =
        AutoBackupEntry(id, "$id.gnbk", createdAt, 1L, "h", 0, 0, 0, "f", keyCreatedAtMillis = 1L, damaged = damaged, keep = keep)

    private fun thinned(vararg entries: AutoBackupEntry) = backupsToThin(entries.toList(), now, zone).map { it.uri }.toSet()

    @Test
    fun everyBackupOfTheLastTwoDaysStays() {
        assertEquals(emptySet(), thinned(entry("a", now - 47 * HOUR), entry("b", now - 30 * HOUR), entry("c", now - HOUR)))
    }

    @Test
    fun forTwoWeeksTheNewestOfEachDayStays() {
        val thin = thinned(
            entry("morning", at(2026, 6, 10, 9)), entry("noon", at(2026, 6, 10, 15)), entry("evening", at(2026, 6, 10, 21)),
            entry("otherDay", at(2026, 6, 9, 8)), entry("newest", now - HOUR),
        )
        assertEquals(setOf("morning", "noon"), thin)
    }

    @Test
    fun forThreeMonthsTheNewestOfEachWeekFromMondayStays() {
        val thin = thinned(
            entry("tue", at(2026, 5, 12, 10)), entry("thu", at(2026, 5, 14, 10)), entry("sun", at(2026, 5, 17, 10)),
            entry("nextMon", at(2026, 5, 18, 10)), entry("newest", now - HOUR),
        )
        assertEquals(setOf("tue", "thu"), thin)
    }

    @Test
    fun beyondThreeMonthsTheNewestOfEachMonthStays() {
        val thin = thinned(
            entry("jan5", at(2026, 1, 5, 10)), entry("jan20", at(2026, 1, 20, 10)), entry("feb3", at(2026, 2, 3, 10)),
            entry("newest", now - HOUR),
        )
        assertEquals(setOf("jan5"), thin)
    }

    @Test
    fun pastTwoWeeksTwoDaysOfOneWeekKeepOnlyTheLater() {
        val thin = thinned(entry("thu", at(2026, 5, 28, 10)), entry("sat", at(2026, 5, 30, 10)), entry("newest", now - HOUR))
        assertEquals(setOf("thu"), thin)
    }

    @Test
    fun aDamagedBackupStaysAndNeverStandsForItsDay() {
        val thin = thinned(
            entry("early", at(2026, 6, 10, 8)), entry("good", at(2026, 6, 10, 9)), entry("bad", at(2026, 6, 10, 21), damaged = true),
            entry("newest", now - HOUR),
        )
        assertEquals(setOf("early"), thin)
    }

    @Test
    fun theNewestUndamagedBackupStandsForItsMonth() {
        val thin = thinned(entry("older", at(2026, 1, 20, 10)), entry("newest", at(2026, 1, 20, 11)), entry("bad", now - HOUR, damaged = true))
        assertEquals(setOf("older"), thin)
    }

    @Test
    fun aBackupKeptBeforeADropStaysBesideItsDaysNewest() {
        val thin = thinned(entry("before", at(2026, 6, 10, 9), keep = true), entry("after", at(2026, 6, 10, 13)), entry("newest", now - HOUR))
        assertEquals(emptySet(), thin)
    }

    @Test
    fun theRunsOwnBackupStaysAndAnUnansweredOneNeitherGoesNorStandsForItsDay() {
        val run = entry("run", at(2026, 6, 10, 9))
        val later = entry("later", at(2026, 6, 10, 13))
        assertEquals(emptySet(), backupsToThin(listOf(run, later, entry("newest", now - HOUR)), now, zone, current = "run").map { it.uri }.toSet())
        val unread = entry("unread", at(2026, 6, 10, 21)).copy(unreadSinceMillis = now - DAY)
        assertEquals(emptySet(), thinned(entry("good", at(2026, 6, 10, 9)), unread, entry("newest", now - HOUR)))
    }

    @Test
    fun daysAreThePhonesLocalDaysNotUtcDays() {
        val ny = TimeZone.of("America/New_York")
        val nyNow = LocalDateTime(2026, 11, 10, 12, 0).toInstant(ny).toEpochMilliseconds()
        // 23:30 on Oct 31 and 00:30 on Nov 1 in New York are both Nov 1 in UTC.
        val a = AutoBackupEntry("a", "a.gnbk", LocalDateTime(2026, 10, 31, 23, 30).toInstant(ny).toEpochMilliseconds(), 1L, "h", 0, 0, 0, "f", 1L)
        val b = a.copy(uri = "b", name = "b.gnbk", createdAtMillis = LocalDateTime(2026, 11, 1, 0, 30).toInstant(ny).toEpochMilliseconds())
        val newest = a.copy(uri = "n", name = "n.gnbk", createdAtMillis = nyNow - HOUR)
        assertEquals(emptyList(), backupsToThin(listOf(a, b, newest), nyNow, ny))
        assertEquals(listOf("a"), backupsToThin(listOf(a, b, newest), nyNow, TimeZone.UTC).map { it.uri })
    }

    @Test
    fun theTwoDayWindowIncludesItsEdge() {
        assertEquals(emptySet(), thinned(entry("edge", now - 48 * HOUR), entry("sameDay", now - 48 * HOUR + 1), entry("newest", now - HOUR)))
        assertEquals(setOf("past"), thinned(entry("past", now - 48 * HOUR - 2), entry("sameDayLater", now - 48 * HOUR - 1), entry("newest", now - HOUR)))
    }

    @Test
    fun theTwoWeekAndThreeMonthEdgesBelongToTheFinerTier() {
        // Exactly 14 days old: still by day, so two on that day keep the later; a day older they share a week with a newer one.
        val dayEdge = entry("dayEdge", now - 14 * DAY)
        val dayEdgeLater = entry("dayEdgeLater", now - 14 * DAY + HOUR)
        assertEquals(setOf("dayEdge"), thinned(dayEdge, dayEdgeLater, entry("newest", now - HOUR)))
        // On the date three months back: still by week (Monday 2026-03-09 to Sunday 03-15), not by month.
        val weekEdge = entry("weekEdge", at(2026, 3, 15, 10))
        val earlierInMonth = entry("earlierInMonth", at(2026, 3, 2, 10))
        assertEquals(emptySet(), thinned(weekEdge, earlierInMonth, entry("newest", now - HOUR)))
    }

    @Test
    fun aBackupDatedAfterNowStays() {
        assertEquals(emptySet(), thinned(entry("ahead", now + DAY), entry("ahead2", now + 2 * DAY)))
    }

    @Test
    fun farFewerMeansNoneLeftOrAQuarterGoneAndAtLeastFive() {
        assertTrue(isFarFewer(60, 20))
        assertTrue(isFarFewer(60, 45), "a quarter, and 15")
        assertFalse(isFarFewer(60, 46), "under a quarter")
        assertTrue(isFarFewer(20, 15), "a quarter, and 5")
        assertFalse(isFarFewer(8, 4), "half, but under 5")
        assertTrue(isFarFewer(3, 0), "none left")
        assertFalse(isFarFewer(0, 0))
        assertFalse(isFarFewer(5, 6))
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
