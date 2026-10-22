package com.anopticlabs.gravel.notebook.reminders

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderFiringTest {

    private val zone = TimeZone.UTC
    private val day = 86_400_000L
    private val anchor = 1_700_000_000_000L
    private val daily = RecurrenceRule(RecurrenceFrequency.DAILY, 1, anchor)

    private fun item(id: Int = 1, at: Long = anchor, rule: RecurrenceRule? = null) = ReminderItem(id, at, "m$id", rule)

    private fun event(id: Int = 1, snooze: Long? = null, delivered: Long? = null, chain: Int = 0, completedAt: Long? = null, cancelledAt: Long? = null) =
        ReminderEvent(id, 100L, "m", 100L, latestSnoozeFireAt = snooze, snoozeDeliveredAt = delivered, snoozeChainCount = chain, completedAt = completedAt, cancelledAt = cancelledAt)

    @Test
    fun aFiredOneShotOrUnusableRuleIsRemovedAndAMissingOrMovedItemIsLeftAlone() {
        assertEquals(FiredReminderPlan.Stale, planFiredReminder(null, anchor, anchor, zone))
        assertEquals(FiredReminderPlan.Stale, planFiredReminder(item(at = anchor + day), anchor, anchor, zone))
        assertEquals(FiredReminderPlan.Stale, planFiredReminder(item(at = anchor + day, rule = daily), anchor, anchor + 2 * day, zone))
        assertEquals(FiredReminderPlan.Remove, planFiredReminder(item(), anchor, anchor, zone))
        assertEquals(FiredReminderPlan.Remove, planFiredReminder(item(rule = daily.copy(anchorMillis = 0L)), anchor, anchor, zone))
        assertEquals(FiredReminderPlan.Remove, planFiredReminder(item(rule = daily.copy(interval = 0)), anchor, anchor, zone))
    }

    @Test
    fun aFiredRecurringItemAdvancesFromTheLaterOfNowAndItsFire() {
        val early = planFiredReminder(item(rule = daily), anchor, anchor - 5_000L, zone)
        assertEquals(FiredReminderPlan.Advance(item(at = anchor + day, rule = daily)), early)
        val late = planFiredReminder(item(rule = daily), anchor, anchor + 3 * day + 1, zone)
        assertEquals(FiredReminderPlan.Advance(item(at = anchor + 4 * day, rule = daily)), late)
    }

    @Test
    fun successiveFiresOfAMonthlyItemKeepItsAnchorDay() {
        val jan31 = LocalDateTime(2025, 1, 31, 9, 0).toInstant(zone).toEpochMilliseconds()
        var current = item(at = jan31, rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, 1, jan31))
        val fires = List(3) {
            current = (planFiredReminder(current, current.remindAtMillis, current.remindAtMillis, zone) as FiredReminderPlan.Advance).updated
            Instant.fromEpochMilliseconds(current.remindAtMillis).toLocalDateTime(zone).date
        }
        assertEquals(listOf(LocalDate(2025, 2, 28), LocalDate(2025, 3, 31), LocalDate(2025, 4, 30)), fires)
    }

    @Test
    fun aSeriesWithNoOccurrenceLeftInTheStoredWindowEndsInsteadOfAdvancing() {
        val june2199 = LocalDateTime(2199, 6, 1, 9, 0).toInstant(zone).toEpochMilliseconds()
        val yearly = item(at = june2199, rule = RecurrenceRule(RecurrenceFrequency.YEARLY, 1, june2199))
        assertEquals(FiredReminderPlan.Remove, planFiredReminder(yearly, june2199, june2199, zone))
        assertEquals(listOf(BootRearm.Drop(yearly, listOf(june2199))), planBootRearm(listOf(yearly), emptySet(), june2199 + day, zone))
    }

    @Test
    fun bootArmsFutureItemsAndDropsPastOnesByWhetherTheirFireWasLogged() {
        val now = anchor + day
        val plans = planBootRearm(
            listOf(
                item(1, at = now + 1),
                item(2, at = anchor),
                item(3, at = anchor),
                item(4, at = 0L),
                item(5, at = anchor, rule = daily.copy(anchorMillis = 0L)),
            ),
            firedKeys = setOf(ReminderEventKey(3, anchor)),
            nowMillis = now,
            zone = zone,
        )
        assertEquals(
            listOf(
                BootRearm.Arm(item(1, at = now + 1)),
                BootRearm.Drop(item(2, at = anchor), listOf(anchor)),
                BootRearm.Drop(item(3, at = anchor), emptyList()),
                BootRearm.Drop(item(4, at = 0L), emptyList()),
                BootRearm.Drop(item(5, at = anchor, rule = daily.copy(anchorMillis = 0L)), listOf(anchor)),
            ),
            plans,
        )
    }

    @Test
    fun bootListsEveryPassedCycleOfARecurringItemExceptLoggedOnesAndAdvancesFromNow() {
        val now = anchor + 3 * day + 1
        val cycles = listOf(anchor, anchor + day, anchor + 2 * day, anchor + 3 * day)
        val plan = planBootRearm(listOf(item(rule = daily)), emptySet(), now, zone).single()
        assertEquals(BootRearm.Advance(item(rule = daily), item(at = anchor + 4 * day, rule = daily), cycles), plan)
        val logged = planBootRearm(listOf(item(rule = daily)), setOf(ReminderEventKey(1, anchor)), now, zone).single()
        assertEquals(cycles.drop(1), logged.missedFires)
    }

    @Test
    fun bootLeavesOutFiresBeforeTheHistoryKeptAndAllButTheNewestCappedCycles() {
        val now = anchor + 3 * day + 1
        val recent = planBootRearm(listOf(item(rule = daily)), emptySet(), now, zone, oldestMillis = anchor + day + 1).single()
        assertEquals(listOf(anchor + 2 * day, anchor + 3 * day), recent.missedFires)
        assertEquals(emptyList(), planBootRearm(listOf(item()), emptySet(), now, zone, oldestMillis = anchor + 1).single().missedFires)
        val longOff = anchor + (MAX_MISSED_CYCLES_LOGGED + 10) * day
        val capped = planBootRearm(listOf(item(rule = daily)), emptySet(), longOff, zone).single().missedFires
        assertEquals(MAX_MISSED_CYCLES_LOGGED, capped.size)
        assertEquals(longOff, capped.last())
        assertEquals(longOff - (MAX_MISSED_CYCLES_LOGGED - 1) * day, capped.first())
    }

    @Test
    fun snoozeRearmSkipsResolvedAndDeliveredAndRepostsOnlyAnUnseenPastReFire() {
        assertEquals(SnoozeRearm.Skip, planSnoozeRearm(event(), 500L))
        assertEquals(SnoozeRearm.Skip, planSnoozeRearm(event(snooze = 900L, completedAt = 1L), 500L))
        assertEquals(SnoozeRearm.Skip, planSnoozeRearm(event(snooze = 900L, cancelledAt = 1L), 500L))
        assertEquals(SnoozeRearm.Reschedule(900L), planSnoozeRearm(event(snooze = 900L), 500L))
        assertEquals(SnoozeRearm.RetroPost(400L), planSnoozeRearm(event(snooze = 400L), 500L))
        assertEquals(SnoozeRearm.RetroPost(400L), planSnoozeRearm(event(snooze = 400L, delivered = 300L), 500L))
        assertEquals(SnoozeRearm.Skip, planSnoozeRearm(event(snooze = 400L, delivered = 400L), 500L))
        assertEquals(SnoozeRearm.Skip, planSnoozeRearm(event(snooze = 900L), 500L, missed = true))
        assertEquals(SnoozeRearm.Skip, planSnoozeRearm(event(snooze = 400L), 500L, missed = true))
    }

    @Test
    fun aSwipeSnoozesAnOpenOccurrenceUntilTheChainCapAndUsesTheDefaultForABadSetting() {
        assertEquals(SoftSnoozePlan.NoOccurrence, planSoftSnooze(null, 0L, 10))
        assertEquals(SoftSnoozePlan.Resolved, planSoftSnooze(event(completedAt = 1L), 0L, 10))
        assertEquals(SoftSnoozePlan.Resolved, planSoftSnooze(event(cancelledAt = 1L), 0L, 10))
        assertEquals(SoftSnoozePlan.ChainCapped, planSoftSnooze(event(chain = MAX_SNOOZE_CHAIN), 0L, 10))
        assertEquals(SoftSnoozePlan.Snooze(1_000L + 15 * 60_000L), planSoftSnooze(event(chain = MAX_SNOOZE_CHAIN - 1), 1_000L, 15))
        assertEquals(SoftSnoozePlan.Snooze(DEFAULT_SWIPE_SNOOZE_MINUTES * 60_000L), planSoftSnooze(event(), 0L, 0))
        assertEquals(SoftSnoozePlan.Missed, planSoftSnooze(event(), 0L, 10, missed = true))
        assertEquals(SoftSnoozePlan.Resolved, planSoftSnooze(event(completedAt = 1L), 0L, 10, missed = true))
    }

    @Test
    fun retentionCutoffIsNullForKeepForever() {
        assertNull(retentionCutoffMillis(1_000L, 0))
        assertNull(retentionCutoffMillis(1_000L, -3))
        assertEquals(1_000L - 90 * day, retentionCutoffMillis(1_000L, 90))
        assertTrue(DEFAULT_HISTORY_RETENTION_DAYS > 0)
    }
}
