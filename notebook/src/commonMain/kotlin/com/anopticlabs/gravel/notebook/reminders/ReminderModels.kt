package com.anopticlabs.gravel.notebook.reminders

import kotlinx.serialization.Serializable

/** What a reminder's words said about when it should fire. */
enum class ReminderTimeStatus {
    /** A concrete time that resolved to a future instant. */
    USABLE,

    /** No time or a vague one: the quick-remind default applies. */
    NO_TIME,

    /** Time words with no usable time read from them: the quick-remind default applies and the user is asked to check it. */
    TO_CHECK,
}

/** A reminder extracted from a capture; [remindAtMillis] is set only for [ReminderTimeStatus.USABLE]. */
data class ReminderProposal(
    val message: String,
    val remindAtMillis: Long?,
    val timeStatus: ReminderTimeStatus,
)

@Serializable
enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** [anchorMillis] is the first intended fire; later fires keep its local wall-clock time. */
@Serializable
data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val anchorMillis: Long = 0L,
)

/** A message travels in alarm intents and notifications, so the stores refuse a longer one. */
const val MAX_REMINDER_MESSAGE_CHARS = 4000

/** [text] cut to [MAX_REMINDER_MESSAGE_CHARS], never between the two halves of a surrogate pair. */
fun cutToReminderMessage(text: String): String {
    val max = MAX_REMINDER_MESSAGE_CHARS
    if (text.length <= max) return text
    return text.substring(0, if (text[max - 1].isHighSurrogate()) max - 1 else max)
}

/** An active reminder; [id] is allocated by the store and doubles as its alarm and notification id. */
@Serializable
data class ReminderItem(
    val id: Int,
    val remindAtMillis: Long,
    val message: String,
    val recurrence: RecurrenceRule? = null,
    /** Armed at the quick-remind default because the time in the words could not be used (unsure, or already past); cleared once the user checks it. */
    val timeToCheck: Boolean = false,
)

/**
 * One fired occurrence, keyed by ([reminderId], [originalFireMillis]); the stamps record what the user did with it.
 * [setInCalendar]: the status was switched in the calendar, so it shows on its due day. [lateNightFireAt]: a passed
 * snooze re-fire at or after 22:00 local that a later snooze replaced or a delete cleared, kept for [completionDay].
 */
@Serializable
data class ReminderEvent(
    val reminderId: Int,
    val originalFireMillis: Long,
    val message: String,
    val firedAt: Long,
    val latestSnoozeFireAt: Long? = null,
    val snoozeChainCount: Int = 0,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
    val snoozeDeliveredAt: Long? = null,
    val setInCalendar: Boolean = false,
    val lateNightFireAt: Long? = null,
) {
    val key: ReminderEventKey get() = ReminderEventKey(reminderId, originalFireMillis)
}

/** The identity of an occurrence: its reminder and its scheduled fire instant. */
data class ReminderEventKey(val reminderId: Int, val originalFireMillis: Long)
