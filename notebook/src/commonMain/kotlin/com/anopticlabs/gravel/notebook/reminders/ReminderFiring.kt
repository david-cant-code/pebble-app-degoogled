package com.anopticlabs.gravel.notebook.reminders

import com.anopticlabs.gravel.notebook.storage.isStoredInstant
import kotlinx.datetime.TimeZone

/** Passive snoozes (a swiped-away notification) an occurrence may chain before a swipe stops re-arming it. */
const val MAX_SNOOZE_CHAIN = 8

const val DEFAULT_SWIPE_SNOOZE_MINUTES = 10
const val DEFAULT_HISTORY_RETENTION_DAYS = 90

/** The settings the alarm stack reads; a retention of zero days or fewer keeps history forever. */
interface ReminderSettings {
    val swipeSnoozeMinutes: Int
    val historyRetentionDays: Int
}

/** Null when history is kept forever. */
fun retentionCutoffMillis(nowMillis: Long, retentionDays: Int): Long? =
    if (retentionDays <= 0) null else nowMillis - retentionDays * 86_400_000L

/** What the fire path does with the active item once the occurrence is logged. */
sealed class FiredReminderPlan {
    /** The item is gone or has moved to another occurrence: leave it as it is. */
    data object Stale : FiredReminderPlan()

    /** A one-shot, a rule the store cannot advance, or a series with no occurrence left in the stored window: nothing stays armed. */
    data object Remove : FiredReminderPlan()

    /** A recurring item moved to its next fire, to persist and then arm. */
    data class Advance(val updated: ReminderItem) : FiredReminderPlan()
}

/**
 * Only an item still sitting on the fired occurrence is settled, so a late fire of an occurrence
 * the user has edited away leaves the edit alone. A recurring item advances from the later of now
 * and the scheduled fire, so an early delivery never re-arms the instant just fired.
 */
fun planFiredReminder(current: ReminderItem?, firedAtMillis: Long, nowMillis: Long, zone: TimeZone): FiredReminderPlan {
    if (current == null || current.remindAtMillis != firedAtMillis) return FiredReminderPlan.Stale
    val rule = current.recurrence
    if (rule == null || !Recurrence.isValid(rule)) return FiredReminderPlan.Remove
    val next = Recurrence.nextAfter(rule, maxOf(nowMillis, current.remindAtMillis), zone)
    if (!isStoredInstant(next)) return FiredReminderPlan.Remove
    return FiredReminderPlan.Advance(current.copy(remindAtMillis = next))
}

/** Most missed cycles of one recurring reminder a re-arm logs: a year of a daily one. */
const val MAX_MISSED_CYCLES_LOGGED = 366

/** What a boot does with one persisted reminder. */
sealed class BootRearm {
    abstract val item: ReminderItem

    data class Arm(override val item: ReminderItem) : BootRearm()

    /** Past due with nothing to advance to. */
    data class Drop(override val item: ReminderItem, override val missedFires: List<Long>) : BootRearm()

    /** Past due and recurring: [updated] is the next fire to arm. */
    data class Advance(override val item: ReminderItem, val updated: ReminderItem, override val missedFires: List<Long>) : BootRearm()

    /** Fires of [item] that passed without being logged, oldest first, to log as occurrences. */
    open val missedFires: List<Long> get() = emptyList()
}

/**
 * A past-due item's passed fires are listed from its stored fire up to now, one per cycle of a
 * recurring item, leaving out those in [firedKeys] (they fired live and only the item's record is
 * stale), those before [oldestMillis] (the history kept) and all but the newest
 * [MAX_MISSED_CYCLES_LOGGED]. A recurring item advances from now.
 */
fun planBootRearm(
    reminders: List<ReminderItem>,
    firedKeys: Set<ReminderEventKey>,
    nowMillis: Long,
    zone: TimeZone,
    oldestMillis: Long? = null,
): List<BootRearm> = reminders.map { item ->
    val rule = item.recurrence?.takeIf { Recurrence.isValid(it) }
    val next = if (item.remindAtMillis <= nowMillis && rule != null) Recurrence.nextAfter(rule, nowMillis, zone) else null
    when {
        item.remindAtMillis > nowMillis -> BootRearm.Arm(item)
        else -> {
            val passed = passedFires(item, rule, nowMillis, zone, oldestMillis)
                .filter { ReminderEventKey(item.id, it) !in firedKeys }
            if (next == null || !isStoredInstant(next)) BootRearm.Drop(item, passed)
            else BootRearm.Advance(item, item.copy(remindAtMillis = next), passed)
        }
    }
}

private fun passedFires(item: ReminderItem, rule: RecurrenceRule?, nowMillis: Long, zone: TimeZone, oldestMillis: Long?): List<Long> {
    if (item.remindAtMillis <= 0L) return emptyList()
    if (rule == null) return listOf(item.remindAtMillis).filter { oldestMillis == null || it >= oldestMillis }
    val fires = ArrayDeque<Long>()
    var fire = item.remindAtMillis
    if (oldestMillis != null && fire < oldestMillis) fire = Recurrence.nextAfter(rule, oldestMillis - 1, zone)
    while (fire <= nowMillis) {
        fires.addLast(fire)
        if (fires.size > MAX_MISSED_CYCLES_LOGGED) fires.removeFirst()
        fire = Recurrence.nextAfter(rule, fire, zone)
    }
    return fires.toList()
}

/** What a boot does with one occurrence's pending snooze. */
sealed class SnoozeRearm {
    data object Skip : SnoozeRearm()
    data class Reschedule(val fireAtMillis: Long) : SnoozeRearm()

    /** The re-fire time passed while the device was off and the user has not seen it. */
    data class RetroPost(val fireAtMillis: Long) : SnoozeRearm()
}

/** [missed]: the occurrence is MISSED (lapsed or superseded), so its snooze alarm is not set again. */
fun planSnoozeRearm(event: ReminderEvent, nowMillis: Long, missed: Boolean = false): SnoozeRearm {
    val latest = event.latestSnoozeFireAt ?: return SnoozeRearm.Skip
    if (event.isResolved || missed) return SnoozeRearm.Skip
    if (latest > nowMillis) return SnoozeRearm.Reschedule(latest)
    return if (isSnoozePendingDelivery(latest, event.snoozeDeliveredAt)) SnoozeRearm.RetroPost(latest) else SnoozeRearm.Skip
}

/** What a swiped-away notification does with its occurrence. */
sealed class SoftSnoozePlan {
    data object NoOccurrence : SoftSnoozePlan()
    data object Resolved : SoftSnoozePlan()

    /** The occurrence is MISSED: it lapsed or a later occurrence of its reminder came due. */
    data object Missed : SoftSnoozePlan()
    data object ChainCapped : SoftSnoozePlan()
    data class Snooze(val fireAtMillis: Long) : SoftSnoozePlan()
}

fun planSoftSnooze(event: ReminderEvent?, nowMillis: Long, swipeSnoozeMinutes: Int, missed: Boolean = false): SoftSnoozePlan {
    if (event == null) return SoftSnoozePlan.NoOccurrence
    if (event.isResolved) return SoftSnoozePlan.Resolved
    if (missed) return SoftSnoozePlan.Missed
    if (event.snoozeChainCount >= MAX_SNOOZE_CHAIN) return SoftSnoozePlan.ChainCapped
    val minutes = if (swipeSnoozeMinutes <= 0) DEFAULT_SWIPE_SNOOZE_MINUTES else swipeSnoozeMinutes
    return SoftSnoozePlan.Snooze(nowMillis + minutes * 60_000L)
}
