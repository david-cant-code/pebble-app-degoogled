package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class ReminderMatchTest {

    private val zone = TimeZone.UTC
    private val day = 86_400_000L

    private fun reminder(id: Int, message: String, remindAtMillis: Long, recurrence: RecurrenceRule? = null) =
        ReminderItem(id, remindAtMillis, message, recurrence)

    private fun event(reminderId: Int, originalFireMillis: Long, message: String, completedAt: Long? = null, cancelledAt: Long? = null) =
        ReminderEvent(reminderId, originalFireMillis, message, originalFireMillis, completedAt = completedAt, cancelledAt = cancelledAt)

    private fun rank(utterance: String, active: List<ReminderItem>, events: List<ReminderEvent> = emptyList(), now: Long = 0L) =
        ReminderMatch.rankReminderCandidates(utterance, active, events, now, zone)

    // stripTriggerPrefix

    @Test
    fun stripsALeadingTriggerWithItsSeparators() {
        assertEquals("i gave the dog meds", ReminderMatch.stripTriggerPrefix("completion I gave the dog meds", "completion"))
        assertEquals("i took out the trash", ReminderMatch.stripTriggerPrefix("Completion, I took out the trash", "completion"))
        assertEquals("all done", ReminderMatch.stripTriggerPrefix("completion. all done", "completion"))
        assertEquals("i did it", ReminderMatch.stripTriggerPrefix("completion i did it", "Completion"))
        assertEquals("i did it", ReminderMatch.stripTriggerPrefix("COMPLETION i did it", "completion"))
        assertEquals("i did it", ReminderMatch.stripTriggerPrefix("completion\u00A0i did it", "completion"))
    }

    @Test
    fun punctuationOnTheTriggerIsTrimmed() {
        assertEquals("the dishes", ReminderMatch.stripTriggerPrefix("done the dishes", "done!"))
        assertEquals("i did it", ReminderMatch.stripTriggerPrefix("completion: i did it", "completion:"))
        assertNull(ReminderMatch.stripTriggerPrefix("anything here", "!!!"))
    }

    @Test
    fun aBareTriggerYieldsAnEmptyUtterance() {
        assertEquals("", ReminderMatch.stripTriggerPrefix("completion", "completion"))
    }

    @Test
    fun noMatchWithoutTheTriggerAtTheStart() {
        assertNull(ReminderMatch.stripTriggerPrefix("i did the completion thing", "completion"))
        assertNull(ReminderMatch.stripTriggerPrefix("completionist notes here", "completion"))
        assertNull(ReminderMatch.stripTriggerPrefix("anything at all here", ""))
        assertNull(ReminderMatch.stripTriggerPrefix("anything", "   "))
        assertNull(ReminderMatch.stripTriggerPrefix("", "completion"))
    }

    // rankReminderCandidates

    @Test
    fun noContentTokensOrNoOverlapYieldsNothing() {
        val active = listOf(reminder(1, "give dog meds", 1_000L))
        assertTrue(rank("the and of", active).isEmpty())
        assertTrue(rank("", active).isEmpty())
        assertTrue(rank("buy milk", listOf(reminder(1, "walk the dog", 1_000L))).isEmpty())
    }

    @Test
    fun aFullMatchIsPendingWithFullCoverage() {
        val out = rank("i gave the dog meds", listOf(reminder(1, "give dog meds", 5_000L)))
        assertEquals(listOf(ReminderMatch.ScoredCandidate(1, 5_000L, "give dog meds", OccurrenceStatus.PENDING, 1.0)), out)
    }

    @Test
    fun irregularPastFormsFoldToTheBase() {
        val out = rank("i fed the dogs", listOf(reminder(1, "feed the dogs", 1_000L), reminder(2, "feed the cats", 2_000L), reminder(3, "water the plants", 3_000L)))
        assertEquals(listOf(1, 2), out.map { it.reminderId })
        assertEquals(1.0, out[0].score, 1e-9)
        assertEquals(0.5, out[1].score, 1e-9)
        assertEquals(1.0, rank("done", listOf(reminder(1, "do dishes", 1_000L))).single().score, 1e-9)
        assertEquals(1.0, rank("i took vitamin b12", listOf(reminder(1, "take vitamin b12", 1_000L))).single().score, 1e-9)
    }

    @Test
    fun representativeFoldFamilies() {
        val pairs = listOf(
            "took" to "take", "gave" to "give", "drove" to "drive", "ran" to "run",
            "bought" to "buy", "brought" to "bring", "caught" to "catch", "taught" to "teach",
            "swept" to "sweep", "kept" to "keep", "slept" to "sleep",
            "built" to "build", "lost" to "lose", "sent" to "send", "spent" to "spend",
            "taken" to "take", "given" to "give", "written" to "write", "eaten" to "eat",
            "fed" to "feed", "paid" to "pay", "made" to "make", "found" to "find",
        )
        for ((past, base) in pairs) {
            val out = rank("i $past the thing", listOf(reminder(1, "$base the thing", 1_000L)))
            assertEquals(1.0, out.single().score, 1e-9, past)
        }
    }

    @Test
    fun coverageTiesBreakByJaccardThenTimeDistance() {
        val byJaccard = rank("meds", listOf(reminder(1, "meds", 1_000L), reminder(2, "give dog meds water plants", 1_000L)))
        assertEquals(listOf(1, 2), byJaccard.map { it.reminderId })
        val byTime = rank(
            "medicine",
            listOf(reminder(1, "give dogs medicine", 100_000L), reminder(2, "give dogs medicine", 5_000L), reminder(3, "give dogs medicine", -10_000L)),
            now = 1_000L,
        )
        assertEquals(listOf(2, 3, 1), byTime.map { it.reminderId })
    }

    @Test
    fun aRecurringReminderRanksByItsNextFire() {
        val now = 1_700_000_000_000L
        val anchor = now - 365L * day
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, anchor)
        val out = rank("feed cats", listOf(reminder(1, "feed cats", anchor, rule), reminder(2, "feed cats", now + 180L * day)), now = now)
        assertEquals(1, out.first().reminderId)
    }

    @Test
    fun candidatesAreCapped() {
        val active = (1..15).map { reminder(it, "vet appointment", it * 1_000L) }
        assertEquals(5, ReminderMatch.rankReminderCandidates("vet appointment", active, emptyList(), 0L, zone, maxCandidates = 5).size)
        assertEquals(ReminderMatch.MAX_CANDIDATES, rank("vet appointment", active).size)
    }

    @Test
    fun firedEventsCarryTheirStatusAndKey() {
        val fire = LocalDateTime(2024, 1, 15, 12, 0).toInstant(zone).toEpochMilliseconds()
        fun statusOf(e: ReminderEvent, now: Long) = rank("i gave the dog meds", emptyList(), listOf(e), now).single().status
        val completed = rank("i gave the dog meds", emptyList(), listOf(event(7, 5_000L, "give dog meds", completedAt = 6_000L)), 10_000L).single()
        assertEquals(7, completed.reminderId)
        assertEquals(5_000L, completed.originalFireMillis)
        assertEquals(OccurrenceStatus.COMPLETED, completed.status)
        assertEquals(OccurrenceStatus.MISSED, statusOf(event(8, 5_000L, "give dog meds", cancelledAt = 6_000L), 10_000L))
        assertEquals(OccurrenceStatus.OVERDUE, statusOf(event(9, fire, "give dog meds"), fire + 3_600_000L))
        assertEquals(OccurrenceStatus.MISSED, statusOf(event(10, fire, "give dog meds"), fire + 2 * day))
    }

    @Test
    fun aRecurringReminderSurfacesItsNextFireAndItsPastOccurrence() {
        val now = 1_700_000_000_000L
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now - 365L * day)
        val out = rank(
            "i gave the dog meds",
            listOf(reminder(5, "give dog meds", now + day, rule)),
            listOf(event(5, now - day, "give dog meds", completedAt = now - day + 60_000L)),
            now,
        )
        assertEquals(setOf(OccurrenceStatus.PENDING, OccurrenceStatus.COMPLETED), out.map { it.status }.toSet())
        assertEquals(2, out.size)
    }
}
