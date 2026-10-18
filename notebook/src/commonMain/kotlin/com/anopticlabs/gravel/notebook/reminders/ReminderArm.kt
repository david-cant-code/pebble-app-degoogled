package com.anopticlabs.gravel.notebook.reminders

/** Minutes ahead a reminder with no stated time is armed; bounded so it is always in the future. */
const val DEFAULT_QUICK_REMIND_MINUTES = 30
const val MIN_QUICK_REMIND_MINUTES = 1
const val MAX_QUICK_REMIND_MINUTES = 24 * 60

/** [toCheck]: the words had a time that could not be used, so the user is asked to check it. */
data class ReminderArmPlan(val millis: Long, val defaulted: Boolean, val toCheck: Boolean = false)

/**
 * When to arm a proposal: a usable future time arms as given; no time arms at now plus the
 * quick-remind default; a time to check, or a usable time that went stale, arms at that default
 * too and asks the user to check it.
 */
fun planReminderArm(proposal: ReminderProposal, quickRemindMinutes: Int, nowMillis: Long): ReminderArmPlan {
    val mins = quickRemindMinutes.coerceIn(MIN_QUICK_REMIND_MINUTES, MAX_QUICK_REMIND_MINUTES)
    val default = nowMillis + mins.toLong() * 60_000L
    return when (proposal.timeStatus) {
        ReminderTimeStatus.USABLE -> {
            val millis = proposal.remindAtMillis
            if (millis != null && millis > nowMillis) ReminderArmPlan(millis, defaulted = false)
            else ReminderArmPlan(default, defaulted = true, toCheck = true)
        }
        ReminderTimeStatus.NO_TIME -> ReminderArmPlan(default, defaulted = true)
        ReminderTimeStatus.TO_CHECK -> ReminderArmPlan(default, defaulted = true, toCheck = true)
    }
}
