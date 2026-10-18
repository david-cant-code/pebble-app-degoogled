package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class ReminderEventsTest {

    private fun event(id: Int = 1, original: Long = 100L, firedAt: Long = original, chain: Int = 0, completedAt: Long? = null, cancelledAt: Long? = null, delivered: Long? = null) =
        ReminderEvent(id, original, "m$id", firedAt, snoozeChainCount = chain, completedAt = completedAt, cancelledAt = cancelledAt, snoozeDeliveredAt = delivered)

    private val key = ReminderEventKey(1, 100L)
    private val utc = TimeZone.UTC

    private fun at(d: Int, h: Int, min: Int): Long = LocalDateTime(2026, 5, d, h, min).toInstant(utc).toEpochMilliseconds()

    @Test
    fun appendFireAddsANewOccurrenceAndLeavesAReplayedOneAsItIs() {
        val logged = appendFire(emptyList(), event(chain = 3))
        assertEquals(listOf(event(chain = 3)), logged)
        assertSame(logged, appendFire(logged, event(chain = 0)))
        assertEquals(2, appendFire(logged, event(original = 200L)).size)
    }

    @Test
    fun theFirstStampWinsAndAnUnknownKeyChangesNothing() {
        val once = stampCompleted(listOf(event()), key, 200L)
        assertEquals(200L, stampCompleted(once, key, 999L).single().completedAt)
        assertEquals(listOf(event()), stampCompleted(listOf(event()), ReminderEventKey(9, 100L), 1L))
        assertEquals(300L, stampCancelled(stampCancelled(listOf(event()), key, 300L), key, 1L).single().cancelledAt)
    }

    @Test
    fun completedAndCancelledDoNotGuardEachOther() {
        val both = stampCancelled(stampCompleted(listOf(event()), key, 200L), key, 300L).single()
        assertEquals(200L, both.completedAt)
        assertEquals(300L, both.cancelledAt)
        val fromCancel = completeAhead(cancelAhead(listOf(event()), key, "x", 400L), key, "x", 500L).single()
        assertEquals(400L, fromCancel.cancelledAt)
        assertEquals(500L, fromCancel.completedAt)
    }

    @Test
    fun aheadWritesCreateAnEventFiredNowForAnAbsentKeyAndStampAPresentOne() {
        val future = ReminderEventKey(7, 5_000L)
        assertEquals(listOf(ReminderEvent(7, 5_000L, "meds", firedAt = 900L, completedAt = 900L)), completeAhead(emptyList(), future, "meds", 900L))
        assertEquals(listOf(ReminderEvent(7, 5_000L, "meds", firedAt = 900L, cancelledAt = 900L)), cancelAhead(emptyList(), future, "meds", 900L))

        val present = listOf(event(firedAt = 105L))
        assertEquals(listOf(event(firedAt = 105L, completedAt = 900L)), completeAhead(present, key, "ignored", 900L))
        assertEquals(listOf(event(firedAt = 105L, cancelledAt = 900L)), cancelAhead(present, key, "ignored", 900L))
        assertEquals(listOf(event(completedAt = 1L)), completeAhead(listOf(event(completedAt = 1L)), key, "x", 900L))
    }

    @Test
    fun recordSnoozeBumpsTheChainOnlyWhenAskedAndReturnsNullForAnUnknownKey() {
        val first = recordSnooze(listOf(event(chain = 2)), key, 170L, bumpChain = true, 150L, utc)
        assertEquals(event(chain = 3).copy(latestSnoozeFireAt = 170L), first.updated)
        assertEquals(listOf(first.updated), first.events)

        val picked = recordSnooze(first.events, key, 180L, bumpChain = false, 150L, utc)
        assertEquals(3, picked.updated?.snoozeChainCount)
        assertEquals(180L, picked.updated?.latestSnoozeFireAt)

        val absent = recordSnooze(first.events, ReminderEventKey(2, 100L), 1L, bumpChain = true, 150L, utc)
        assertNull(absent.updated)
        assertEquals(first.events, absent.events)
    }

    @Test
    fun aResolvedOccurrenceIsNotSnoozedAndTheChainNeverOverflows() {
        for (resolved in listOf(event(completedAt = 1L), event(cancelledAt = 1L))) {
            val refused = recordSnooze(listOf(resolved), key, 170L, bumpChain = true, 150L, utc)
            assertNull(refused.updated)
            assertEquals(listOf(resolved), refused.events)
        }
        val maxed = recordSnooze(listOf(event(chain = Int.MAX_VALUE)), key, 170L, bumpChain = true, 150L, utc)
        assertEquals(Int.MAX_VALUE, maxed.updated?.snoozeChainCount)
    }

    @Test
    fun aDeliveryMarkerMovesForwardOnlyAndNeverOntoAResolvedOccurrence() {
        assertEquals(listOf(event(delivered = 170L)), markSnoozeDelivered(listOf(event()), key, 170L))
        assertEquals(listOf(event(delivered = 170L)), markSnoozeDelivered(listOf(event(delivered = 170L)), key, 160L))
        assertEquals(listOf(event(delivered = 170L)), markSnoozeDelivered(listOf(event(delivered = 170L)), key, 170L))
        assertEquals(listOf(event(delivered = 180L)), markSnoozeDelivered(listOf(event(delivered = 170L)), key, 180L))
        assertEquals(listOf(event(completedAt = 1L)), markSnoozeDelivered(listOf(event(completedAt = 1L)), key, 170L))
        assertEquals(listOf(event(cancelledAt = 1L)), markSnoozeDelivered(listOf(event(cancelledAt = 1L)), key, 170L))
    }

    @Test
    fun pruneKeepsAnEventUntilItsScheduledFireActualFireAndPendingSnoozeAllLieBeforeTheCutoff() {
        val log = listOf(
            event(1, original = 100L, firedAt = 100L),
            event(2, original = 100L, firedAt = 250L),
            event(3, original = 300L, firedAt = 100L),
            event(4, original = 100L, firedAt = 100L).copy(latestSnoozeFireAt = 400L),
            event(5, original = 200L, firedAt = 200L),
            event(6, original = 100L, firedAt = 100L, completedAt = 300L),
        )
        assertEquals(listOf(2, 3, 4, 5, 6), pruneEventsBefore(log, 200L).map { it.reminderId })
        assertEquals(400L, event(4, original = 100L).copy(latestSnoozeFireAt = 400L).retentionMillis)
    }

    @Test
    fun clearingPendingSnoozesTouchesOnlyTheOpenSnoozedOccurrencesOfOneReminder() {
        val events = listOf(
            event(1, 100L).copy(latestSnoozeFireAt = 500L, snoozeChainCount = 2),
            event(1, 200L),
            event(1, 300L, completedAt = 1L).copy(latestSnoozeFireAt = 700L),
            event(2, 100L).copy(latestSnoozeFireAt = 900L),
        )
        val (updated, cleared) = clearPendingSnoozes(events, 1, 150L, utc)
        assertEquals(listOf(ReminderEventKey(1, 100L)), cleared)
        assertNull(updated[0].latestSnoozeFireAt)
        assertEquals(2, updated[0].snoozeChainCount)
        assertEquals(events.drop(1), updated.drop(1))
    }

    @Test
    fun aPassedLateNightReFireIsKeptWhenASnoozeReplacesOrClearsIt() {
        val lateRefire = event(original = at(10, 20, 0)).copy(latestSnoozeFireAt = at(10, 22, 30))
        val snoozedAgain = recordSnooze(listOf(lateRefire), lateRefire.key, at(11, 0, 30), bumpChain = true, at(10, 22, 31), utc).updated
        assertEquals(at(10, 22, 30), snoozedAgain?.lateNightFireAt)
        assertEquals(at(11, 0, 30), snoozedAgain?.latestSnoozeFireAt)
        assertEquals(at(10, 22, 30), clearPendingSnoozes(listOf(lateRefire), 1, at(10, 22, 31), utc).first.single().lateNightFireAt)
        // Kept: the later of two late-night re-fires.
        val twice = lateRefire.copy(lateNightFireAt = at(10, 22, 5))
        assertEquals(at(10, 22, 30), recordSnooze(listOf(twice), twice.key, at(11, 0, 30), bumpChain = true, at(10, 22, 31), utc).updated?.lateNightFireAt)
    }

    @Test
    fun aReFireBefore22OrStillToComeIsNotKept() {
        val evening = event(original = at(10, 20, 0)).copy(latestSnoozeFireAt = at(10, 21, 59))
        assertNull(recordSnooze(listOf(evening), evening.key, at(10, 23, 0), bumpChain = true, at(10, 22, 0), utc).updated?.lateNightFireAt)
        val pending = event(original = at(10, 20, 0)).copy(latestSnoozeFireAt = at(10, 22, 30))
        assertNull(recordSnooze(listOf(pending), pending.key, at(10, 23, 0), bumpChain = true, at(10, 22, 29), utc).updated?.lateNightFireAt)
        assertNull(clearPendingSnoozes(listOf(pending), 1, at(10, 22, 29), utc).first.single().lateNightFireAt)
    }

    @Test
    fun aSwitchReplacesTheStampClearsTheOtherAndMarksTheEventSetInTheCalendar() {
        val now = at(12, 9, 0)
        val done = event(original = at(10, 9, 0), completedAt = at(10, 9, 5))
        assertEquals(
            listOf(done.copy(completedAt = null, cancelledAt = now, setInCalendar = true)),
            switchStatus(listOf(done), done.key, completed = false, now, utc),
        )
        val marked = event(original = at(10, 9, 0), cancelledAt = at(10, 9, 5))
        assertEquals(
            listOf(marked.copy(completedAt = now, cancelledAt = null, setInCalendar = true)),
            switchStatus(listOf(marked), marked.key, completed = true, now, utc),
        )
        val lapsed = event(original = at(10, 9, 0))
        assertEquals(
            listOf(lapsed.copy(completedAt = now, setInCalendar = true)),
            switchStatus(listOf(lapsed), lapsed.key, completed = true, now, utc),
        )
    }

    @Test
    fun aSwitchRefusesAnOpenOccurrenceAnUnknownKeyAndTheStatusItAlreadyHas() {
        val now = at(10, 10, 0)
        val due = event(original = at(10, 9, 0))
        assertNull(switchStatus(listOf(due), due.key, completed = true, now, utc))
        assertNull(switchStatus(listOf(due), due.key, completed = false, now, utc))
        assertNull(switchStatus(listOf(due), ReminderEventKey(9, 1L), completed = true, now, utc))
        val done = event(original = at(9, 9, 0), completedAt = at(9, 9, 5))
        assertNull(switchStatus(listOf(done), done.key, completed = true, now, utc))
    }

    @Test
    fun deletingTheOccurrenceThatMadeAnEarlierOneMissedLeavesItMissed() {
        val now = at(11, 9, 30)
        val monday = event(original = at(10, 9, 0)).copy(latestSnoozeFireAt = at(11, 10, 0))
        val tuesday = event(original = at(11, 9, 0))
        val kept = removeOccurrence(listOf(monday, tuesday), tuesday.key, now, utc)
        assertEquals(listOf(monday.copy(cancelledAt = tuesday.originalFireMillis)), kept)
        assertEquals(OccurrenceStatus.MISSED, statusIn(kept, kept.single(), now, utc))
    }

    @Test
    fun deletingAnOccurrenceStampsOnlyThoseItAloneMadeMissed() {
        val now = at(11, 9, 30)
        val lapsed = event(original = at(9, 9, 0))
        val snoozedOther = event(id = 2, original = at(10, 9, 0)).copy(latestSnoozeFireAt = at(11, 10, 0))
        val stillLater = event(original = at(11, 8, 0))
        val tuesday = event(original = at(11, 9, 0))
        val monday = event(original = at(10, 9, 0)).copy(latestSnoozeFireAt = at(11, 10, 0))
        val otherDue = event(id = 2, original = at(11, 8, 30))
        // Monday stays MISSED through the 08:00 occurrence still logged, so only that one is stamped;
        // another reminder's occurrence due between the two is not touched.
        assertEquals(
            listOf(lapsed, snoozedOther, stillLater.copy(cancelledAt = tuesday.originalFireMillis), monday, otherDue),
            removeOccurrence(listOf(lapsed, snoozedOther, stillLater, tuesday, monday, otherDue), tuesday.key, now, utc),
        )
        val onlyLapsed = listOf(lapsed)
        assertSame(onlyLapsed, removeOccurrence(onlyLapsed, tuesday.key, now, utc))
    }
}
