package com.anopticlabs.gravel.notebook.reminders

fun shouldPostVoiceCompletionNotification(appBackgrounded: Boolean, pendingCount: Int): Boolean =
    appBackgrounded && pendingCount > 0

/** The only place a count becomes notification text, so no transcript can reach the lock screen. */
fun voiceCompletionNotificationText(count: Int): String =
    if (count <= 1) "1 completed task to review" else "$count completed tasks to review"

/** The notice for occurrences MISSED before they could be shown: one line per reminder, oldest first. */
data class MissedWhileOffNotice(val title: String, val lines: List<String>)

fun missedWhileOffNotice(missed: List<ReminderEvent>): MissedWhileOffNotice {
    val byReminder = missed.sortedBy { it.originalFireMillis }.groupBy { it.reminderId }.values
    val lines = byReminder.map { times -> if (times.size == 1) times[0].message else "${times.last().message}, ${times.size} times" }
    val title = if (missed.size == 1) "Missed reminder" else "Missed reminders"
    return MissedWhileOffNotice(title, lines)
}
