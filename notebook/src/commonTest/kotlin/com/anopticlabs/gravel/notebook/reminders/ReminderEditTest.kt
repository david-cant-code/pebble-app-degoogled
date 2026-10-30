package com.anopticlabs.gravel.notebook.reminders

import com.anopticlabs.gravel.notebook.storage.MAX_STORED_INSTANT_MILLIS
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReminderEditTest {

    private val utc = TimeZone.UTC
    private val minute = 60_000L
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val now = LocalDateTime(2026, 3, 10, 12, 0).toInstant(utc).toEpochMilliseconds()

    private fun plan(
        message: String = "call",
        at: Long = now + minute,
        frequency: RecurrenceFrequency? = null,
        interval: Int = 1,
        opened: ReminderItem? = null,
    ) = planReminderEdit(message, at, frequency, interval, opened, now, utc)

    private fun refused(problem: ReminderEditProblem) = ReminderEditPlan.Refused(problem)

    @Test
    fun aOneShotIsSavedTrimmedAtItsTime() {
        assertEquals(ReminderEditPlan.Save("call", now + minute, null), plan(message = "  call \n"))
    }

    @Test
    fun whatTheStoreWouldRefuseIsRefusedFirst() {
        assertEquals(refused(ReminderEditProblem.MESSAGE_MISSING), plan(message = " \n "))
        assertEquals(refused(ReminderEditProblem.MESSAGE_TOO_LONG), plan(message = "x".repeat(MAX_REMINDER_MESSAGE_CHARS + 1)))
        assertEquals(ReminderEditPlan.Save("x".repeat(MAX_REMINDER_MESSAGE_CHARS), now + minute, null), plan(message = "x".repeat(MAX_REMINDER_MESSAGE_CHARS)))
        assertEquals(refused(ReminderEditProblem.OUT_OF_RANGE), plan(at = MAX_STORED_INSTANT_MILLIS + 1))
        assertEquals(refused(ReminderEditProblem.INTERVAL_INVALID), plan(frequency = RecurrenceFrequency.DAILY, interval = 0))
        assertEquals(refused(ReminderEditProblem.INTERVAL_INVALID), plan(frequency = RecurrenceFrequency.DAILY, interval = MAX_EDITOR_INTERVAL + 1))
    }

    @Test
    fun aOneShotMustBeInTheFutureWhileARepeatingOneMayStartInThePast() {
        assertEquals(refused(ReminderEditProblem.TIME_PAST), plan(at = now))
        val weekAgo = now - 7 * day
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, 2, weekAgo)
        assertEquals(ReminderEditPlan.Save("call", now + day, rule), plan(at = weekAgo, frequency = RecurrenceFrequency.DAILY, interval = 2))
    }

    @Test
    fun anEditThatKeepsTimeAndRuleKeepsTheAnchor() {
        val jan31 = LocalDateTime(2026, 1, 31, 9, 0).toInstant(utc).toEpochMilliseconds()
        val shownFeb28 = LocalDateTime(2026, 2, 28, 9, 0).toInstant(utc).toEpochMilliseconds()
        val nextMar31 = LocalDateTime(2026, 3, 31, 9, 0).toInstant(utc).toEpochMilliseconds()
        val rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, 1, jan31)
        val item = ReminderItem(7, shownFeb28, "rent", rule)
        // The item's time has passed (fired meanwhile, or late); the edit changes only the text.
        assertEquals(ReminderEditPlan.Save("rent due", shownFeb28, rule, keepsTime = true), plan("rent due", shownFeb28, RecurrenceFrequency.MONTHLY, 1, item))
        // A new time of day on the shown day keeps the anchor's date: the next fire is the 31st.
        val feb28At10 = shownFeb28 + hour
        val moved = assertIs<ReminderEditPlan.Save>(plan("rent", feb28At10, RecurrenceFrequency.MONTHLY, 1, item))
        assertEquals(jan31 + hour, moved.recurrence?.anchorMillis)
        assertEquals(nextMar31 + hour, moved.remindAtMillis)
    }

    @Test
    fun aTextOnlyEditOfALateOneShotIsNotRefusedForItsTime() {
        val late = ReminderItem(3, now - minute, "call")
        assertEquals(ReminderEditPlan.Save("call back", now - minute, null, keepsTime = true), plan("call back", now - minute, null, 1, late))
        assertEquals(refused(ReminderEditProblem.TIME_PAST), plan("call back", now - 2 * minute, null, 1, late))
    }

    @Test
    fun theLargestIntervalTheRuleTakesIsTaken() {
        assertEquals(RecurrenceRule(RecurrenceFrequency.DAILY, 1000, now + minute), (plan(frequency = RecurrenceFrequency.DAILY, interval = 1000) as ReminderEditPlan.Save).recurrence)
    }

    @Test
    fun anEditThatMovesTheDayOrChangesTheRuleAnchorsAtTheChosenTime() {
        val start = now + day
        val item = ReminderItem(7, start, "gym", RecurrenceRule(RecurrenceFrequency.WEEKLY, 1, now - 20 * day))
        assertEquals(
            ReminderEditPlan.Save("gym", start + minute, RecurrenceRule(RecurrenceFrequency.WEEKLY, 1, now - 20 * day + minute)),
            plan("gym", start + minute, RecurrenceFrequency.WEEKLY, 1, item),
        )
        assertEquals(
            ReminderEditPlan.Save("gym", start + day, RecurrenceRule(RecurrenceFrequency.WEEKLY, 1, start + day)),
            plan("gym", start + day, RecurrenceFrequency.WEEKLY, 1, item),
        )
        assertEquals(
            ReminderEditPlan.Save("gym", start, RecurrenceRule(RecurrenceFrequency.DAILY, 1, start)),
            plan("gym", start, RecurrenceFrequency.DAILY, 1, item),
        )
        assertEquals(
            ReminderEditPlan.Save("gym", start, RecurrenceRule(RecurrenceFrequency.WEEKLY, 2, start)),
            plan("gym", start, RecurrenceFrequency.WEEKLY, 2, item),
        )
    }

    @Test
    fun aRepeatingReminderWithNoOccurrenceLeftInTheStoredWindowIsRefused() {
        val lastDay = LocalDateTime(2199, 12, 31, 9, 0).toInstant(utc).toEpochMilliseconds()
        val nowNearTheEnd = lastDay + minute
        assertEquals(
            refused(ReminderEditProblem.OUT_OF_RANGE),
            planReminderEdit("new year", lastDay, RecurrenceFrequency.YEARLY, 1, null, nowNearTheEnd, utc),
        )
    }
}
