package com.anopticlabs.gravel.notebook.reminders

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReminderListingTest {

    private val utc = TimeZone.UTC
    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = LocalDateTime(2026, 3, 10, 12, 0).toInstant(utc).toEpochMilliseconds()

    private fun event(id: Int, fire: Long, snooze: Long? = null, completed: Long? = null, cancelled: Long? = null) =
        ReminderEvent(id, fire, "m$id", firedAt = fire, latestSnoozeFireAt = snooze, completedAt = completed, cancelledAt = cancelled)

    @Test
    fun firedOccurrencesAreDueUntilTheyLapseAndSnoozedOnesWaitApart() {
        val due = event(1, now - hour)
        val dueLater = event(2, now - 2 * hour, snooze = now - minute)
        val snoozed = event(3, now - hour, snooze = now + 10 * minute)
        val snoozedSooner = event(4, now - hour, snooze = now + 5 * minute)
        val lapsed = event(5, now - 30 * hour)
        val done = event(6, now - hour, completed = now - minute)
        val notDone = event(7, now - hour, cancelled = now - minute)
        val listing = reminderListing(emptyList(), listOf(due, dueLater, snoozed, snoozedSooner, lapsed, done, notDone), now, utc)
        assertEquals(listOf(dueLater, due), listing.dueNow)
        assertEquals(listOf(snoozedSooner, snoozed), listing.snoozed)
    }

    @Test
    fun anOpenOccurrenceLeavesDueNowAndSnoozedOnceALaterOneOfItsReminderIsDue() {
        val earlierDue = event(1, now - 3 * hour)
        val earlierSnoozed = event(2, now - 3 * hour, snooze = now + 10 * minute)
        val later = listOf(event(1, now - hour), event(2, now - hour, completed = now - minute))
        val notYet = event(3, now - hour)
        val completedAhead = event(3, now + hour, completed = now - minute)
        val listing = reminderListing(emptyList(), listOf(earlierDue, earlierSnoozed, notYet, completedAhead) + later, now, utc)
        assertEquals(listOf(notYet, later[0]), listing.dueNow)
        assertEquals(emptyList(), listing.snoozed)
    }

    @Test
    fun anEarlyDeliveredUnsnoozedOccurrenceIsDueNotSnoozed() {
        val early = event(1, now + minute)
        assertEquals(listOf(early), reminderListing(emptyList(), listOf(early), now, utc).dueNow)
    }

    @Test
    fun everyStoredReminderIsUpcomingByTimeALateOneIncluded() {
        val later = ReminderItem(1, now + 2 * hour, "later")
        val soon = ReminderItem(2, now + hour, "soon")
        val late = ReminderItem(3, now - minute, "late")
        assertEquals(listOf(late, soon, later), reminderListing(listOf(later, soon, late), emptyList(), now, utc).upcoming)
    }

    @Test
    fun aReminderStillOnAnOccurrenceListedAsDueIsNotListedTwice() {
        val stuck = ReminderItem(1, now - hour, "stuck")
        val resolvedOnly = ReminderItem(2, now - hour, "resolved")
        val listing = reminderListing(
            listOf(stuck, resolvedOnly),
            listOf(event(1, now - hour), event(2, now - hour, completed = now - minute)),
            now,
            utc,
        )
        assertEquals(listOf(1), listing.dueNow.map { it.reminderId })
        assertEquals(listOf(resolvedOnly), listing.upcoming)
        assertEquals(setOf(2), listing.wentOff)
    }

    @Test
    fun aLateReminderWithNoLoggedOccurrenceHasNotGoneOff() {
        val late = ReminderItem(1, now - minute, "late")
        val movedOn = ReminderItem(2, now + hour, "moved on")
        val listing = reminderListing(listOf(late, movedOn), listOf(event(2, now - hour, completed = now - minute)), now, utc)
        assertEquals(listOf(late, movedOn), listing.upcoming)
        assertEquals(emptySet(), listing.wentOff)
    }
}
