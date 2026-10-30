package com.anopticlabs.gravel.notebook.alarms

import android.content.Intent
import android.net.Uri
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey

/** The actions and extras the alarm stack's intents carry; the OPEN_* actions reach the main activity for the UI to route. */
object ReminderIntents {
    private const val PREFIX = "com.anopticlabs.gravel.notebook.action."
    const val ACTION_FIRE = PREFIX + "REMINDER_FIRE"
    const val ACTION_COMPLETE = PREFIX + "REMINDER_COMPLETE"
    const val ACTION_CANCEL = PREFIX + "REMINDER_CANCEL"
    const val ACTION_SOFT_SNOOZE = PREFIX + "REMINDER_SOFT_SNOOZE"
    const val ACTION_SNOOZE_CONFIRMED = PREFIX + "REMINDER_SNOOZE_CONFIRMED"
    const val ACTION_SNOOZED_FIRE = PREFIX + "REMINDER_SNOOZED_FIRE"
    const val ACTION_LAPSE_SWEEP = PREFIX + "REMINDER_LAPSE_SWEEP"
    const val ACTION_OPEN_REMINDERS = PREFIX + "OPEN_REMINDERS"
    const val ACTION_OPEN_SNOOZE_PICKER = PREFIX + "OPEN_SNOOZE_PICKER"
    const val ACTION_OPEN_REMINDER_EDITOR = PREFIX + "OPEN_REMINDER_EDITOR"
    const val ACTION_OPEN_VOICE_COMPLETIONS = PREFIX + "OPEN_VOICE_COMPLETIONS"
    const val ACTION_OPEN_REMINDER_CALENDAR = PREFIX + "OPEN_REMINDER_CALENDAR"
    const val ACTION_OPEN_BACKUP_SETTINGS = PREFIX + "OPEN_BACKUP_SETTINGS"
    const val ACTION_OPEN_KEY_CHECK = PREFIX + "OPEN_KEY_CHECK"

    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_ORIGINAL_FIRE_MILLIS = "original_fire_millis"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_SNOOZE_MILLIS = "snooze_millis"

    /** The secret the OPEN_* intents carry; see [com.anopticlabs.gravel.notebook.ui.NotebookLinkToken]. */
    const val EXTRA_LINK_TOKEN = "com.anopticlabs.gravel.notebook.extra.LINK_TOKEN"

    /**
     * The occurrence as extras and as the data URI, which is part of pending-intent identity
     * (android-37.0 android.jar, `Intent.filterEquals`), so each occurrence gets its own intents.
     */
    fun Intent.putOccurrence(key: ReminderEventKey): Intent =
        setData(Uri.parse("notebook://reminder/${key.reminderId}/${key.originalFireMillis}"))
            .putExtra(EXTRA_REMINDER_ID, key.reminderId)
            .putExtra(EXTRA_ORIGINAL_FIRE_MILLIS, key.originalFireMillis)

    /** The instant whose day the calendar opens on, as an extra and as the data URI, so a notice for another instant keeps its own intent. */
    fun Intent.putCalendarDay(atMillis: Long): Intent =
        setData(Uri.parse("notebook://calendar/$atMillis")).putExtra(EXTRA_ORIGINAL_FIRE_MILLIS, atMillis)

    /** Null unless the intent names a reminder id and a scheduled fire instant. */
    fun occurrenceOf(intent: Intent): ReminderEventKey? {
        val id = intent.getIntExtra(EXTRA_REMINDER_ID, -1)
        val original = intent.getLongExtra(EXTRA_ORIGINAL_FIRE_MILLIS, -1L)
        if (id < 1 || original <= 0L) return null
        return ReminderEventKey(id, original)
    }
}
