package com.anopticlabs.gravel.notebook.reminders

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceCompletionTest {

    @Test
    fun writeTargetsPerStatusAndAction() {
        assertEquals(VoiceCompletionWriteTarget.UpcomingComplete, voiceCompletionWriteTarget(OccurrenceStatus.PENDING, VoiceCompletionAction.COMPLETE))
        assertEquals(VoiceCompletionWriteTarget.UpcomingNotDone, voiceCompletionWriteTarget(OccurrenceStatus.PENDING, VoiceCompletionAction.NOT_DONE))
        assertEquals(VoiceCompletionWriteTarget.OverdueComplete, voiceCompletionWriteTarget(OccurrenceStatus.OVERDUE, VoiceCompletionAction.COMPLETE))
        assertEquals(VoiceCompletionWriteTarget.OverdueMissed, voiceCompletionWriteTarget(OccurrenceStatus.OVERDUE, VoiceCompletionAction.NOT_DONE))
        for (action in VoiceCompletionAction.entries) {
            assertNull(voiceCompletionWriteTarget(OccurrenceStatus.COMPLETED, action))
            assertNull(voiceCompletionWriteTarget(OccurrenceStatus.MISSED, action))
        }
    }

    @Test
    fun offeredActionsFollowTheWriteMap() {
        assertEquals(listOf(VoiceCompletionAction.COMPLETE, VoiceCompletionAction.NOT_DONE), resolutionActionsFor(OccurrenceStatus.PENDING))
        assertEquals(VoiceCompletionAction.entries.toList(), resolutionActionsFor(OccurrenceStatus.OVERDUE))
        assertTrue(resolutionActionsFor(OccurrenceStatus.COMPLETED).isEmpty())
        assertTrue(resolutionActionsFor(OccurrenceStatus.MISSED).isEmpty())
        for (status in OccurrenceStatus.entries) {
            for (action in VoiceCompletionAction.entries) {
                assertEquals(voiceCompletionWriteTarget(status, action) != null, action in resolutionActionsFor(status))
            }
        }
    }

    @Test
    fun noteTitleFromAParkedTranscript() {
        assertEquals("Voice note", deriveVoiceCompletionNoteTitle(""))
        assertEquals("Voice note", deriveVoiceCompletionNoteTitle("   \n  "))
        assertEquals("call the pharmacy", deriveVoiceCompletionNoteTitle("call the pharmacy"))
        assertEquals("buy milk", deriveVoiceCompletionNoteTitle("\n  buy   milk \nand eggs"))
        assertEquals("a".repeat(60), deriveVoiceCompletionNoteTitle("a".repeat(200), maxLen = 60))
    }

    private fun candidate(status: OccurrenceStatus) = ReminderMatch.ScoredCandidate(1, 0L, "m", status, 1.0)

    @Test
    fun alreadyCompletedNeedsACompletedCandidate() {
        assertFalse(hasAlreadyCompletedCandidate(emptyList()))
        assertFalse(hasAlreadyCompletedCandidate(listOf(candidate(OccurrenceStatus.PENDING), candidate(OccurrenceStatus.OVERDUE), candidate(OccurrenceStatus.MISSED))))
        assertTrue(hasAlreadyCompletedCandidate(listOf(candidate(OccurrenceStatus.PENDING), candidate(OccurrenceStatus.COMPLETED))))
    }

    private fun vc(id: String) = VoiceCompletion(id, "t", 0L)

    @Test
    fun mergeParkedForDisplayPrefersTheStoreAndNeverBlanksTheList() {
        val just = vc("c")
        val onDisk = listOf(vc("a"), vc("b"), vc("c"))
        assertEquals(onDisk, mergeParkedForDisplay(onDisk, current = listOf(vc("a")), justParked = just))
        assertEquals(listOf("a", "b", "c"), mergeParkedForDisplay(emptyList(), listOf(vc("a"), vc("b")), just).map { it.completionId })
        assertEquals(listOf("c"), mergeParkedForDisplay(emptyList(), emptyList(), just).map { it.completionId })
        assertEquals(listOf("a", "c"), mergeParkedForDisplay(emptyList(), listOf(vc("a"), vc("c")), just).map { it.completionId })
        assertEquals(listOf("a", "b", "c"), mergeParkedForDisplay(listOf(vc("a"), vc("b")), emptyList(), just).map { it.completionId })
    }

    private val now = 1_790_089_200_000L
    private val hour = 3_600_000L

    @Test
    fun candidatesCoverEveryStoredReminderAndTheLogWithOneEntryPerOccurrence() {
        val late = ReminderItem(1, now - hour, "feed the dog")
        val next = ReminderItem(2, now + hour, "walk the dog", RecurrenceRule(RecurrenceFrequency.DAILY, 1, now + hour))
        val stuck = ReminderItem(3, now - 2 * hour, "wash the dog")
        val stuckFire = ReminderEvent(3, now - 2 * hour, "wash the dog", firedAt = now - 2 * hour)
        val snoozed = ReminderEvent(4, now - 3 * hour, "brush the dog", firedAt = now - 3 * hour, latestSnoozeFireAt = now + hour)
        val found = completionCandidates("did the dog", listOf(late, next, stuck), listOf(stuckFire, snoozed), now, TimeZone.UTC)
        assertEquals(setOf(1, 2, 3, 4), found.map { it.match.reminderId }.toSet())
        assertEquals(found.size, found.map { it.key }.toSet().size)
        val byId = found.associateBy { it.match.reminderId }
        assertEquals(OccurrenceStatus.PENDING, byId.getValue(1).match.status)
        assertEquals(stuckFire, byId.getValue(3).event)
        assertEquals(OccurrenceStatus.OVERDUE, byId.getValue(3).match.status)
        assertEquals(snoozed, byId.getValue(4).event)
        assertEquals(OccurrenceStatus.PENDING, byId.getValue(4).match.status)
        assertEquals(listOf(false, true, false, false), (1..4).map { byId.getValue(it).repeats })
    }

    @Test
    fun candidatesAreTheFourBest() {
        val items = (1..6).map { ReminderItem(it, now + it * hour, "feed the dog") }
        val found = completionCandidates("fed the dog", items, emptyList(), now, TimeZone.UTC)
        assertEquals(VOICE_COMPLETION_MAX_CANDIDATES, found.size)
        assertEquals(listOf(1, 2, 3, 4), found.map { it.match.reminderId })
    }

    @Test
    fun aRepeatingRemindersLoggedOccurrenceIsNotItsNextTime() {
        val next = ReminderItem(2, now + hour, "walk the dog", RecurrenceRule(RecurrenceFrequency.DAILY, 1, now - 23 * hour))
        val fired = ReminderEvent(2, now - 23 * hour, "walk the dog", firedAt = now - 23 * hour)
        val found = completionCandidates("walked the dog", listOf(next), listOf(fired), now, TimeZone.UTC).associateBy { it.key }
        assertEquals(true, found.getValue(ReminderEventKey(2, now + hour)).repeats)
        assertEquals(false, found.getValue(fired.key).repeats)
    }

    @Test
    fun anOccurrenceNobodyActedOnStaysOpenHoweverLongAgoItLapsed() {
        val lapsedBefore = ReminderEvent(1, now - 72 * hour, "take out the bins", firedAt = now - 72 * hour)
        val firedBefore = ReminderEvent(2, now - hour, "bring in the bins", firedAt = now - hour)
        val laterFire = ReminderEvent(3, now + hour, "wash the bins", firedAt = now + hour)
        val done = ReminderEvent(4, now - 2 * hour, "buy bins", firedAt = now - 2 * hour, completedAt = now - hour)
        val skipped = ReminderEvent(5, now - 3 * hour, "sell bins", firedAt = now - 3 * hour, cancelledAt = now - 2 * hour)
        val found = completionCandidates("did the bins", emptyList(), listOf(lapsedBefore, firedBefore, laterFire, done, skipped), now, TimeZone.UTC)
            .associate { it.match.reminderId to it.match.status }
        assertEquals(OccurrenceStatus.OVERDUE, found[2])
        assertEquals(OccurrenceStatus.PENDING, found[3])
        assertEquals(OccurrenceStatus.COMPLETED, found[4])
        assertEquals(OccurrenceStatus.MISSED, found[5])
        val alone = completionCandidates("did the bins", emptyList(), listOf(lapsedBefore), now, TimeZone.UTC).single()
        assertEquals(OccurrenceStatus.OVERDUE, alone.match.status)
    }

    @Test
    fun candidatesRankByDistanceFromWhenTheClaimWasSpoken() {
        val day = 24 * hour
        val captured = now + 5 * 60_000L
        val daily = RecurrenceRule(RecurrenceFrequency.DAILY, 1, now - day)
        val item = ReminderItem(1, now + 2 * day, "take the pills", daily)
        val fired = listOf(now - day, now, now + day).map { ReminderEvent(1, it, "take the pills", firedAt = it) }
        val found = completionCandidates("took the pills", listOf(item), fired, captured, TimeZone.UTC)
        assertEquals(ReminderEventKey(1, now), found.first().key)
        assertEquals(OccurrenceStatus.OVERDUE, found.first().match.status)
    }
}
