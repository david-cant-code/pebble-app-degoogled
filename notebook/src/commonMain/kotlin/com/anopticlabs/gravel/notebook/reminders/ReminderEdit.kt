package com.anopticlabs.gravel.notebook.reminders

import com.anopticlabs.gravel.notebook.storage.isStoredInstant
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** The largest repeat interval the editor takes: the rule's own. */
const val MAX_EDITOR_INTERVAL = Recurrence.MAX_INTERVAL

enum class ReminderEditProblem { MESSAGE_MISSING, MESSAGE_TOO_LONG, TIME_PAST, INTERVAL_INVALID, OUT_OF_RANGE }

sealed interface ReminderEditPlan {
    /** [keepsTime]: an edit that changed neither the time nor the repeat, so only [message] is to be stored. */
    data class Save(val message: String, val remindAtMillis: Long, val recurrence: RecurrenceRule?, val keepsTime: Boolean = false) : ReminderEditPlan

    data class Refused(val problem: ReminderEditProblem) : ReminderEditPlan
}

/**
 * What the editor stores for [message] at [chosenMillis], repeating by [frequency] every
 * [interval] when [frequency] is set. An edit of [opened] that keeps its time and repeat changes
 * only the message, even when that time has passed. One that keeps the repeat and the shown day
 * keeps the rule's anchor date, so a month-end reminder shown at a clamped day is not re-anchored
 * there. A repeating reminder whose chosen time has passed starts at its next occurrence after
 * [nowMillis].
 */
fun planReminderEdit(
    message: String,
    chosenMillis: Long,
    frequency: RecurrenceFrequency?,
    interval: Int,
    opened: ReminderItem?,
    nowMillis: Long,
    zone: TimeZone,
): ReminderEditPlan {
    val text = message.trim()
    if (text.isEmpty()) return ReminderEditPlan.Refused(ReminderEditProblem.MESSAGE_MISSING)
    if (text.length > MAX_REMINDER_MESSAGE_CHARS) return ReminderEditPlan.Refused(ReminderEditProblem.MESSAGE_TOO_LONG)
    val openedRule = opened?.recurrence
    val sameRule = openedRule?.takeIf { frequency == it.frequency && interval == it.interval && Recurrence.isValid(it) }
    if (opened != null && chosenMillis == opened.remindAtMillis && (sameRule != null || (frequency == null && openedRule == null))) {
        return ReminderEditPlan.Save(text, opened.remindAtMillis, openedRule, keepsTime = true)
    }
    val problem = when {
        !isStoredInstant(chosenMillis) -> ReminderEditProblem.OUT_OF_RANGE
        !reminderTimeSubmittable(chosenMillis, nowMillis, recurring = frequency != null) -> ReminderEditProblem.TIME_PAST
        frequency != null && interval !in 1..MAX_EDITOR_INTERVAL -> ReminderEditProblem.INTERVAL_INVALID
        else -> null
    }
    if (problem != null) return ReminderEditPlan.Refused(problem)
    if (frequency == null) return ReminderEditPlan.Save(text, chosenMillis, null)
    val chosen = Instant.fromEpochMilliseconds(chosenMillis).toLocalDateTime(zone)
    val rule = if (opened != null && sameRule != null && chosen.date == Instant.fromEpochMilliseconds(opened.remindAtMillis).toLocalDateTime(zone).date) {
        val anchorDay = Instant.fromEpochMilliseconds(sameRule.anchorMillis).toLocalDateTime(zone).date
        sameRule.copy(anchorMillis = anchorDay.atTime(chosen.time).toInstant(zone).toEpochMilliseconds())
    } else {
        RecurrenceRule(frequency, interval, chosenMillis)
    }
    if (!Recurrence.isValid(rule)) return ReminderEditPlan.Refused(ReminderEditProblem.OUT_OF_RANGE)
    val first = if (chosenMillis > nowMillis) chosenMillis else Recurrence.nextAfter(rule, nowMillis, zone)
    if (!isStoredInstant(first)) return ReminderEditPlan.Refused(ReminderEditProblem.OUT_OF_RANGE)
    return ReminderEditPlan.Save(text, first, rule)
}
