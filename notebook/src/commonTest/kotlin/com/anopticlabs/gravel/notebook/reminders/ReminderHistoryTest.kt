package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class ReminderHistoryTest {

    private val zone = TimeZone.UTC
    private val year = LocalDate(2026, 1, 1)..LocalDate(2026, 12, 31)

    private fun at(y: Int, mo: Int, d: Int, h: Int, min: Int, tz: TimeZone = zone): Long =
        LocalDateTime(y, mo, d, h, min).toInstant(tz).toEpochMilliseconds()

    private fun event(
        id: Int = 1,
        original: Long,
        firedAt: Long = original,
        completedAt: Long? = null,
        latestSnooze: Long? = null,
        chain: Int = 0,
        cancelledAt: Long? = null,
        msg: String = "m",
    ) = ReminderEvent(id, original, msg, firedAt, latestSnooze, chain, completedAt, cancelledAt)

    private fun statusOf(e: ReminderEvent, now: Long): OccurrenceStatus =
        buildCalendarOccurrences(listOf(e), emptyList(), now, zone, year).values.single().single().status

    @Test
    fun activeReminderIsPendingOnItsDay() {
        val r = ReminderItem(1, at(2026, 5, 10, 9, 0), "trash")
        val map = buildCalendarOccurrences(emptyList(), listOf(r), at(2026, 5, 9, 12, 0), zone, year)
        assertEquals(listOf(CalendarOccurrence(1, r.remindAtMillis, "trash", OccurrenceStatus.PENDING, CalendarRowKind.NEXT, onStored = true)), map[LocalDate(2026, 5, 10)])
    }

    @Test
    fun eventsPlotOnTheirOriginalFireDay() {
        val original = at(2026, 5, 10, 9, 0)
        val map = buildCalendarOccurrences(
            listOf(event(original = original, firedAt = at(2026, 5, 12, 7, 0), completedAt = original + 30_000L)),
            emptyList(), at(2026, 5, 12, 8, 0), zone, year,
        )
        assertEquals(setOf(LocalDate(2026, 5, 10)), map.keys)
        assertEquals(OccurrenceStatus.COMPLETED, map.values.single().single().status)
    }

    @Test
    fun statusPrecedence() {
        val original = at(2026, 5, 1, 9, 0)
        val later = at(2026, 5, 10, 8, 0)
        assertEquals(OccurrenceStatus.MISSED, statusOf(event(original = original), later))
        assertEquals(OccurrenceStatus.PENDING, statusOf(event(original = original, latestSnooze = at(2026, 5, 1, 9, 15), chain = 1), at(2026, 5, 1, 9, 5)))
        assertEquals(OccurrenceStatus.MISSED, statusOf(event(original = original, cancelledAt = original + 30_000L), later))
        assertEquals(
            OccurrenceStatus.MISSED,
            statusOf(event(original = original, latestSnooze = at(2026, 5, 1, 9, 30), chain = 1, cancelledAt = at(2026, 5, 1, 9, 6)), at(2026, 5, 1, 9, 5)),
        )
        assertEquals(OccurrenceStatus.COMPLETED, statusOf(event(original = original, completedAt = original + 30_000L, cancelledAt = original + 60_000L), later))
        // A completed event keeps its status even before its original fire.
        assertEquals(OccurrenceStatus.COMPLETED, statusOf(event(original = at(2026, 5, 12, 9, 0), firedAt = later, completedAt = later), later))
    }

    @Test
    fun recurringOccurrencesAreIndependent() {
        val lastWeek = at(2026, 4, 28, 9, 0)
        val today = at(2026, 5, 5, 9, 0)
        val rule = RecurrenceRule(RecurrenceFrequency.WEEKLY, 1, lastWeek)
        val map = buildCalendarOccurrences(
            listOf(event(7, lastWeek, msg = "trash"), event(7, today, completedAt = today + 60_000L, msg = "trash")),
            listOf(ReminderItem(7, at(2026, 5, 12, 9, 0), "trash", rule)),
            at(2026, 5, 5, 9, 30), zone, year,
        )
        assertEquals(OccurrenceStatus.MISSED, map.getValue(LocalDate(2026, 4, 28)).single().status)
        assertEquals(OccurrenceStatus.COMPLETED, map.getValue(LocalDate(2026, 5, 5)).single().status)
        assertEquals(OccurrenceStatus.PENDING, map.getValue(LocalDate(2026, 5, 12)).single().status)
    }

    @Test
    fun retentionCutoffDropsOlderEvents() {
        val map = buildCalendarOccurrences(
            listOf(event(1, at(2026, 1, 1, 9, 0)), event(2, at(2026, 5, 1, 9, 0))),
            emptyList(), at(2026, 5, 6, 9, 0), zone, year, retentionCutoffMillis = at(2026, 4, 1, 0, 0),
        )
        assertEquals(setOf(LocalDate(2026, 5, 1)), map.keys)
    }

    @Test
    fun aDayIsSortedByTime() {
        val map = buildCalendarOccurrences(
            listOf(event(2, at(2026, 5, 10, 21, 0), msg = "evening"), event(1, at(2026, 5, 10, 9, 0), msg = "morning")),
            emptyList(), at(2026, 5, 10, 12, 0), zone, year,
        )
        assertEquals(listOf("morning", "evening"), map.getValue(LocalDate(2026, 5, 10)).map { it.message })
    }

    @Test
    fun firedOccurrenceIsOverdueUntilItLapses() {
        val morning = event(original = at(2026, 5, 10, 9, 0))
        assertEquals(OccurrenceStatus.OVERDUE, statusOf(morning, at(2026, 5, 10, 14, 0)))
        assertEquals(OccurrenceStatus.MISSED, statusOf(morning, at(2026, 5, 11, 8, 0)))
        assertEquals(OccurrenceStatus.PENDING, statusOf(event(original = at(2026, 5, 10, 9, 0), latestSnooze = at(2026, 5, 10, 9, 30), chain = 1), at(2026, 5, 10, 9, 5)))
        val lateNight = event(original = at(2026, 5, 10, 23, 0))
        assertEquals(OccurrenceStatus.OVERDUE, statusOf(lateNight, at(2026, 5, 11, 1, 0)))
        assertEquals(OccurrenceStatus.MISSED, statusOf(lateNight, at(2026, 5, 11, 2, 30)))
        assertEquals(OccurrenceStatus.MISSED, statusOf(event(original = at(2026, 5, 10, 21, 0)), at(2026, 5, 11, 0, 30)))
    }

    @Test
    fun lapseIsMidnightOrTwoAmAfterALateFire() {
        assertEquals(at(2026, 5, 11, 0, 0), overdueLapseMillis(at(2026, 5, 10, 21, 0), zone))
        assertEquals(at(2026, 5, 11, 2, 0), overdueLapseMillis(at(2026, 5, 10, 22, 0), zone))
        assertEquals(at(2026, 5, 11, 2, 0), overdueLapseMillis(at(2026, 5, 10, 23, 0), zone))
        val plus5 = TimeZone.of("UTC+05:00")
        assertEquals(at(2026, 5, 11, 0, 0, plus5), overdueLapseMillis(at(2026, 5, 10, 21, 0, plus5), plus5))
    }

    @Test
    fun lapseBoundaryIsWallClockAcrossDstChanges() {
        // Santiago falls back at midnight after 2026-04-04.
        val santiago = TimeZone.of("America/Santiago")
        assertEquals(at(2026, 4, 5, 2, 0, santiago), overdueLapseMillis(at(2026, 4, 4, 22, 0, santiago), santiago))
        assertEquals(at(2026, 4, 5, 0, 0, santiago), overdueLapseMillis(at(2026, 4, 4, 21, 0, santiago), santiago))
        // New York springs forward at 02:00 on 2026-03-08, so 02:00 resolves to the first instant after the gap.
        val newYork = TimeZone.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T07:00:00Z").toEpochMilliseconds(), overdueLapseMillis(at(2026, 3, 7, 23, 0, newYork), newYork))
    }

    @Test
    fun snoozedLateNightAnchorsTheLapseOnTheSnooze() {
        val e = event(original = at(2026, 5, 10, 9, 0), latestSnooze = at(2026, 5, 10, 23, 30), chain = 1)
        assertEquals(OccurrenceStatus.OVERDUE, statusOf(e, at(2026, 5, 11, 1, 0)))
    }

    private fun rowsOn(day: LocalDate, events: List<ReminderEvent>, now: Long, items: List<ReminderItem> = emptyList(), laterUntil: Long? = null) =
        buildCalendarOccurrences(events, items, now, zone, year, laterUntilMillis = laterUntil)[day].orEmpty()

    @Test
    fun aCompletionShowsOnTheDayItWasDoneWithItsDueTimeKept() {
        val friday = at(2026, 5, 15, 9, 0)
        val doneWednesday = event(original = friday, firedAt = at(2026, 5, 13, 14, 0), completedAt = at(2026, 5, 13, 14, 0), msg = "vet")
        val now = at(2026, 5, 13, 15, 0)
        val row = rowsOn(LocalDate(2026, 5, 13), listOf(doneWednesday), now).single()
        assertEquals(friday, row.occurrenceMillis)
        assertEquals(OccurrenceStatus.COMPLETED, row.status)
        assertTrue(rowsOn(LocalDate(2026, 5, 15), listOf(doneWednesday), now).isEmpty())
    }

    @Test
    fun aMissedOneCompletedLaterFromTheReviewCountsOnTheDayItWasDone() {
        val monday = event(original = at(2026, 5, 11, 9, 0), completedAt = at(2026, 5, 15, 10, 0))
        assertEquals(OccurrenceStatus.COMPLETED, rowsOn(LocalDate(2026, 5, 15), listOf(monday), at(2026, 5, 15, 11, 0)).single().status)
    }

    @Test
    fun missedAndSnoozedOccurrencesShowOnTheirDueDay() {
        val now = at(2026, 5, 12, 0, 10)
        val snoozedPastMidnight = event(original = at(2026, 5, 11, 23, 30), latestSnooze = at(2026, 5, 12, 0, 30))
        val markedLater = event(id = 2, original = at(2026, 5, 11, 9, 0), cancelledAt = at(2026, 5, 12, 0, 5))
        assertEquals(
            listOf(OccurrenceStatus.MISSED, OccurrenceStatus.PENDING),
            rowsOn(LocalDate(2026, 5, 11), listOf(snoozedPastMidnight, markedLater), now).map { it.status },
        )
    }

    @Test
    fun aCompletionBeforeTwoCountsOnTheDayBeforeWhenItWentOffAt22OrLaterThatDay() {
        // Fired Monday 23:00, snoozed to Tuesday 00:30, completed 00:35: Monday.
        val nightMeds = event(original = at(2026, 5, 11, 23, 0), latestSnooze = at(2026, 5, 12, 0, 30), completedAt = at(2026, 5, 12, 0, 35))
        assertEquals(LocalDate(2026, 5, 11), completionDay(nightMeds, nightMeds.completedAt!!, zone))
        // Due Tuesday 00:30, completed 00:40: Tuesday.
        val vet = event(original = at(2026, 5, 12, 0, 30), completedAt = at(2026, 5, 12, 0, 40))
        assertEquals(LocalDate(2026, 5, 12), completionDay(vet, vet.completedAt!!, zone))
        // At 02:00 the grace is over.
        assertEquals(LocalDate(2026, 5, 12), completionDay(nightMeds, at(2026, 5, 12, 2, 0), zone))
        // A going-off before 22:00 gives no grace.
        val evening = event(original = at(2026, 5, 11, 21, 59), completedAt = at(2026, 5, 12, 0, 5))
        assertEquals(LocalDate(2026, 5, 12), completionDay(evening, evening.completedAt!!, zone))
        // Completed early, before a late-night due time that day: the day it was done.
        val early = event(original = at(2026, 5, 12, 23, 0), completedAt = at(2026, 5, 12, 1, 0))
        assertEquals(LocalDate(2026, 5, 12), completionDay(early, early.completedAt!!, zone))
    }

    @Test
    fun aLateNightReFireReplacedByALaterSnoozeStillCounts() {
        val chain = event(original = at(2026, 5, 11, 20, 0), latestSnooze = at(2026, 5, 12, 0, 30), completedAt = at(2026, 5, 12, 0, 35))
        assertEquals(LocalDate(2026, 5, 12), completionDay(chain, chain.completedAt!!, zone))
        val kept = chain.copy(lateNightFireAt = at(2026, 5, 11, 22, 30))
        assertEquals(LocalDate(2026, 5, 11), completionDay(kept, kept.completedAt!!, zone))
        // Counted on Monday, it sorts after Monday's own rows.
        val monday = rowsOn(LocalDate(2026, 5, 11), listOf(kept, event(id = 2, original = at(2026, 5, 11, 23, 0))), at(2026, 5, 12, 1, 0))
        assertEquals(listOf(2, 1), monday.map { it.reminderId })
    }

    @Test
    fun aStatusSwitchedInTheCalendarShowsOnTheDueDayEvenWhenCompleted() {
        val friday = at(2026, 5, 15, 9, 0)
        val backToCompleted = event(original = friday, completedAt = at(2026, 5, 16, 10, 0)).copy(setInCalendar = true)
        assertEquals(OccurrenceStatus.COMPLETED, rowsOn(LocalDate(2026, 5, 15), listOf(backToCompleted), at(2026, 5, 16, 11, 0)).single().status)
        val toMissed = event(original = friday, cancelledAt = at(2026, 5, 13, 15, 0)).copy(setInCalendar = true)
        assertEquals(OccurrenceStatus.MISSED, rowsOn(LocalDate(2026, 5, 15), listOf(toMissed), at(2026, 5, 13, 16, 0)).single().status)
    }

    @Test
    fun laterRepeatsShowAfterNowUpToTheWindowAndTheNextTimeAlwaysShows() {
        val now = at(2026, 5, 10, 12, 0)
        val daily = ReminderItem(1, at(2026, 5, 11, 9, 0), "meds", RecurrenceRule(RecurrenceFrequency.DAILY, 1, at(2026, 5, 1, 9, 0)))
        val yearly = ReminderItem(2, at(2027, 3, 1, 9, 0), "passport", RecurrenceRule(RecurrenceFrequency.YEARLY, 1, at(2026, 3, 1, 9, 0)))
        val until = laterRepeatsUntil(now, zone)
        assertEquals(at(2026, 6, 11, 0, 0) - 1, until)
        val map = buildCalendarOccurrences(emptyList(), listOf(daily, yearly), now, zone, LocalDate(2026, 5, 1)..LocalDate(2027, 12, 31), laterUntilMillis = until)
        val meds = map.values.flatten().filter { it.reminderId == 1 }.sortedBy { it.occurrenceMillis }
        assertEquals(CalendarRowKind.NEXT, meds.first().kind)
        assertEquals(at(2026, 5, 11, 9, 0), meds.first().occurrenceMillis)
        assertEquals(at(2026, 6, 10, 9, 0), meds.last().occurrenceMillis)
        assertEquals(31, meds.size)
        assertTrue(meds.drop(1).all { it.kind == CalendarRowKind.LATER && it.repeats })
        assertEquals(listOf(CalendarRowKind.NEXT), map.values.flatten().filter { it.reminderId == 2 }.map { it.kind })
    }

    @Test
    fun aNextTimeTheLogHoldsIsNotShownTwiceAndALateOneHasNoLaterRepeatsInThePast() {
        val now = at(2026, 5, 13, 12, 0)
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, at(2026, 5, 1, 9, 0))
        val wentOff = ReminderItem(1, at(2026, 5, 13, 9, 0), "meds", rule)
        val logged = event(original = wentOff.remindAtMillis, msg = "meds")
        val rows = buildCalendarOccurrences(listOf(logged), listOf(wentOff), now, zone, year, laterUntilMillis = at(2026, 5, 15, 23, 0))
        val sitsOn = rows.getValue(LocalDate(2026, 5, 13)).single()
        assertEquals(CalendarRowKind.LOGGED, sitsOn.kind)
        assertTrue(sitsOn.onStored && sitsOn.repeats)
        // A later time the log already holds (completed ahead, then the reminder was moved back) shows once, as logged.
        val movedBack = ReminderItem(3, at(2026, 5, 14, 9, 0), "bins", rule)
        val ahead = event(3, at(2026, 5, 15, 9, 0), firedAt = at(2026, 5, 13, 8, 0), completedAt = at(2026, 5, 13, 8, 0), msg = "bins")
        val binRows = buildCalendarOccurrences(listOf(ahead), listOf(movedBack), now, zone, year, laterUntilMillis = at(2026, 5, 16, 23, 0))
            .values.flatten().filter { it.reminderId == 3 }.sortedBy { it.occurrenceMillis }
        assertEquals(listOf(CalendarRowKind.NEXT, CalendarRowKind.LOGGED, CalendarRowKind.LATER), binRows.map { it.kind })
        assertFalse(binRows[1].onStored)
        val late = ReminderItem(2, at(2026, 5, 10, 9, 0), "trash", rule)
        val lateRows = buildCalendarOccurrences(emptyList(), listOf(late), now, zone, year, laterUntilMillis = at(2026, 5, 15, 23, 0))
            .values.flatten().sortedBy { it.occurrenceMillis }
        assertEquals(listOf(at(2026, 5, 10, 9, 0), at(2026, 5, 14, 9, 0), at(2026, 5, 15, 9, 0)), lateRows.map { it.occurrenceMillis })
    }

    @Test
    fun onlyTheDaysAskedForAreBuilt() {
        val now = at(2026, 5, 20, 12, 0)
        val events = listOf(event(1, at(2026, 4, 30, 9, 0)), event(2, at(2026, 5, 1, 9, 0)), event(3, at(2026, 5, 3, 9, 0)))
        val map = buildCalendarOccurrences(events, emptyList(), now, zone, LocalDate(2026, 5, 1)..LocalDate(2026, 5, 2))
        assertEquals(setOf(LocalDate(2026, 5, 1)), map.keys)
        // Completed three days before it was due: found through its completion, outside the range's margin by due time.
        val early = event(4, at(2026, 5, 5, 9, 0), completedAt = at(2026, 5, 2, 9, 0))
        val onTheSecond = buildCalendarOccurrences(listOf(early), emptyList(), now, zone, LocalDate(2026, 5, 2)..LocalDate(2026, 5, 2))
        assertEquals(listOf(4), onTheSecond.values.flatten().map { it.reminderId })
    }

    @Test
    fun aLateCompletionIsKeptForItsCompletionDayPastItsDueTimesRetention() {
        val now = at(2026, 9, 1, 12, 0)
        val cutoff = at(2026, 6, 3, 12, 0)
        val lateDone = event(original = at(2026, 6, 1, 9, 0), completedAt = at(2026, 6, 7, 9, 0))
        val rows = buildCalendarOccurrences(listOf(lateDone), emptyList(), now, zone, year, retentionCutoffMillis = cutoff)
        assertEquals(setOf(LocalDate(2026, 6, 7)), rows.keys)
    }

    @Test
    fun placementFollowsTheLocalDayAcrossDaylightSavingChanges() {
        // Berlin falls back from 03:00 to 02:00 on 2026-10-25.
        val berlin = TimeZone.of("Europe/Berlin")
        val nightMeds = event(original = at(2026, 10, 24, 23, 0, berlin), completedAt = at(2026, 10, 25, 1, 30, berlin))
        val berlinRows = buildCalendarOccurrences(listOf(nightMeds), emptyList(), at(2026, 10, 25, 12, 0, berlin), berlin, year)
        assertEquals(setOf(LocalDate(2026, 10, 24)), berlinRows.keys)
        assertEquals(at(2026, 11, 26, 0, 0, berlin) - 1, laterRepeatsUntil(at(2026, 10, 25, 12, 0, berlin), berlin))
        // Santiago springs forward at midnight on 2026-09-06, so that day starts at 01:00.
        val santiago = TimeZone.of("America/Santiago")
        val late = event(original = at(2026, 9, 5, 23, 0, santiago), completedAt = at(2026, 9, 6, 1, 30, santiago))
        val other = event(id = 2, original = at(2026, 9, 5, 22, 30, santiago))
        val santiagoRows = buildCalendarOccurrences(listOf(late, other), emptyList(), at(2026, 9, 6, 12, 0, santiago), santiago, year)
        assertEquals(listOf(2, 1), santiagoRows[LocalDate(2026, 9, 5)]?.map { it.reminderId })
        assertEquals(setOf(LocalDate(2026, 9, 5)), santiagoRows.keys)
    }

    @Test
    fun theCalendarPagesFromTheRetentionMonthToTheFurthestShownTime() {
        val now = at(2026, 5, 20, 12, 0)
        val until = laterRepeatsUntil(now, zone)
        val item = ReminderItem(1, at(2026, 9, 3, 9, 0), "far")
        assertEquals(
            LocalDate(2026, 2, 1)..LocalDate(2026, 9, 1),
            calendarMonths(emptyList(), listOf(item), now, zone, retentionCutoffMillis = at(2026, 2, 19, 12, 0), laterUntilMillis = until),
        )
        val old = event(original = at(2025, 11, 4, 9, 0))
        assertEquals(LocalDate(2025, 11, 1)..LocalDate(2026, 6, 1), calendarMonths(listOf(old), emptyList(), now, zone, null, until))
        assertEquals(LocalDate(2026, 5, 1)..LocalDate(2026, 6, 1), calendarMonths(emptyList(), emptyList(), now, zone, null, until))
    }

    @Test
    fun overdueOccurrencesFilterAndSortNewestFirst() {
        val result = overdueOccurrences(
            listOf(
                event(1, at(2026, 5, 10, 9, 0)),
                event(2, at(2026, 5, 10, 12, 0)),
                event(3, at(2026, 5, 10, 8, 0), completedAt = at(2026, 5, 10, 8, 1)),
                event(4, at(2026, 5, 10, 7, 0), cancelledAt = at(2026, 5, 10, 7, 1)),
                event(5, at(2026, 5, 8, 9, 0)),
                event(6, at(2026, 5, 10, 6, 0), latestSnooze = at(2026, 5, 10, 20, 0), chain = 1),
            ),
            at(2026, 5, 10, 15, 0), zone,
        )
        assertEquals(listOf(2, 1), result.map { it.reminderId })
    }

    @Test
    fun snoozePendingDelivery() {
        assertFalse(isSnoozePendingDelivery(null, null))
        assertFalse(isSnoozePendingDelivery(null, 5L))
        assertTrue(isSnoozePendingDelivery(100L, null))
        assertTrue(isSnoozePendingDelivery(200L, 100L))
        assertFalse(isSnoozePendingDelivery(100L, 100L))
        assertFalse(isSnoozePendingDelivery(100L, 150L))
    }

    @Test
    fun summarizeDayCountsByStatus() {
        val s = summarizeDay(
            listOf(
                CalendarOccurrence(1, 1L, "a", OccurrenceStatus.PENDING),
                CalendarOccurrence(2, 2L, "b", OccurrenceStatus.PENDING),
                CalendarOccurrence(3, 3L, "c", OccurrenceStatus.COMPLETED),
                CalendarOccurrence(4, 4L, "d", OccurrenceStatus.MISSED),
                CalendarOccurrence(5, 5L, "e", OccurrenceStatus.OVERDUE),
            ),
        )
        assertEquals(DayStatusSummary(2, 1, 1, 1), s)
        assertEquals(5, s.total)
    }

    // planEarlyCompletion

    @Test
    fun earlyCompletionOfAOneShotRemovesIt() {
        val r = ReminderItem(1, at(2026, 5, 12, 9, 0), "m")
        assertEquals(EarlyCompletionOutcome.RemoveOneShot, planEarlyCompletion(r, r, at(2026, 5, 10, 8, 0), zone))
        val bad = r.copy(recurrence = RecurrenceRule(RecurrenceFrequency.DAILY, 1, 0L))
        assertEquals(EarlyCompletionOutcome.RemoveOneShot, planEarlyCompletion(bad, bad, at(2026, 5, 10, 8, 0), zone))
    }

    @Test
    fun earlyCompletionOfARecurringItemAdvancesIt() {
        val fireAt = at(2026, 5, 12, 9, 0)
        val rule = RecurrenceRule(RecurrenceFrequency.WEEKLY, 1, fireAt)
        val r = ReminderItem(7, fireAt, "trash", rule)
        val outcome = planEarlyCompletion(r, r, at(2026, 5, 10, 8, 0), zone)
        assertEquals(EarlyCompletionOutcome.AdvanceRecurring(r.copy(remindAtMillis = at(2026, 5, 19, 9, 0))), outcome)
    }

    @Test
    fun anOccurrenceThatFiredInTheGapIsAlreadyFired() {
        val fireAt = at(2026, 5, 12, 9, 0)
        val now = at(2026, 5, 12, 9, 1)
        assertEquals(EarlyCompletionOutcome.AlreadyFired, planEarlyCompletion(ReminderItem(1, fireAt, "m"), null, now, zone))
        val rule = RecurrenceRule(RecurrenceFrequency.WEEKLY, 1, fireAt)
        val snapshot = ReminderItem(7, fireAt, "m", rule)
        val advanced = snapshot.copy(remindAtMillis = at(2026, 5, 19, 9, 0))
        assertEquals(EarlyCompletionOutcome.AlreadyFired, planEarlyCompletion(snapshot, advanced, now, zone))
    }

    @Test
    fun aLaterOccurrenceOfTheSameReminderThatCameDueMakesAnOpenEarlierOneMissed() {
        val now = at(2026, 5, 11, 9, 30)
        val monday = event(original = at(2026, 5, 10, 9, 0), latestSnooze = at(2026, 5, 11, 11, 0))
        val tuesday = event(original = at(2026, 5, 11, 9, 0))
        val otherReminder = event(id = 2, original = at(2026, 5, 11, 9, 15))
        val latestDue = latestDueByReminder(listOf(monday, tuesday, otherReminder), now)
        assertEquals(mapOf(1 to tuesday.originalFireMillis, 2 to otherReminder.originalFireMillis), latestDue)
        assertEquals(OccurrenceStatus.MISSED, statusFor(monday, now, zone, latestDue[1]))
        assertEquals(OccurrenceStatus.PENDING, statusFor(monday, now, zone, null))
        assertEquals(OccurrenceStatus.OVERDUE, statusFor(tuesday, now, zone, latestDue[1]))
        assertEquals(OccurrenceStatus.COMPLETED, statusFor(monday.copy(completedAt = now), now, zone, latestDue[1]))
        val aheadOnly = latestDueByReminder(listOf(monday, event(original = at(2026, 5, 11, 12, 0), completedAt = now)), now)
        assertEquals(OccurrenceStatus.PENDING, statusFor(monday, now, zone, aheadOnly[1]))
    }

    @Test
    fun theNextLapseIsTheEarliestCutoffAmongDueOccurrencesAndNullWhenNoneIsDue() {
        val now = at(2026, 5, 10, 23, 0)
        val lateNight = event(id = 1, original = at(2026, 5, 10, 22, 30))
        val morning = event(id = 2, original = at(2026, 5, 10, 8, 0))
        val snoozed = event(id = 3, original = at(2026, 5, 10, 8, 0), latestSnooze = at(2026, 5, 11, 0, 30))
        val done = event(id = 4, original = at(2026, 5, 10, 7, 0), completedAt = now)
        assertEquals(at(2026, 5, 11, 0, 0), nextLapseMillis(listOf(lateNight, morning, snoozed, done), now, zone))
        assertEquals(at(2026, 5, 11, 2, 0), nextLapseMillis(listOf(lateNight, snoozed, done), now, zone))
        assertEquals(at(2026, 5, 12, 0, 0), nextLapseMillis(listOf(snoozed, done), now, zone))
        assertNull(nextLapseMillis(listOf(done), now, zone))
        val reFiredLate = event(id = 5, original = at(2026, 5, 10, 20, 0), latestSnooze = at(2026, 5, 10, 22, 30))
        assertEquals(at(2026, 5, 11, 2, 0), nextLapseMillis(listOf(reFiredLate), now, zone))
        val early = event(id = 6, original = now + 60_000L)
        assertEquals(at(2026, 5, 11, 2, 0), nextLapseMillis(listOf(early), now, zone))
    }

    @Test
    fun theNextLapseIncludesALaterLoggedOccurrenceOfTheSameReminderStillToCome() {
        val now = at(2026, 5, 11, 7, 0)
        val monday = event(original = at(2026, 5, 10, 9, 0), latestSnooze = at(2026, 5, 11, 8, 30))
        val tuesdayAhead = event(original = at(2026, 5, 11, 9, 0), completedAt = at(2026, 5, 10, 18, 0))
        assertEquals(at(2026, 5, 11, 9, 0), nextLapseMillis(listOf(monday, tuesdayAhead), now, zone))
        assertEquals(OccurrenceStatus.MISSED, statusIn(listOf(monday, tuesdayAhead), monday, at(2026, 5, 11, 9, 0), zone))
    }

    @Test
    fun theSweepClearsNotificationsOfResolvedAndMissedOccurrencesButNotDueOrUnloggedOnes() {
        val now = at(2026, 5, 11, 9, 30)
        val lapsed = event(id = 1, original = at(2026, 5, 10, 9, 0))
        val due = event(id = 2, original = at(2026, 5, 11, 9, 0))
        val done = event(id = 3, original = at(2026, 5, 11, 9, 0), completedAt = now)
        val superseded = event(id = 2, original = at(2026, 5, 10, 20, 0), latestSnooze = at(2026, 5, 11, 10, 0))
        val unlogged = ReminderEventKey(5, at(2026, 5, 11, 9, 29))
        val posted = listOf(lapsed.key, due.key, done.key, superseded.key, unlogged)
        assertEquals(
            listOf(lapsed.key, done.key, superseded.key),
            notificationsToClear(posted, listOf(lapsed, due, done, superseded), now, zone),
        )
    }
}
