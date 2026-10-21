package com.anopticlabs.gravel.notebook.alarms

import android.content.Context
import com.anopticlabs.gravel.notebook.NotebookSettingsStore
import com.anopticlabs.gravel.notebook.pipeline.CaptureSettings
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendarChoice
import com.anopticlabs.gravel.notebook.reminders.DEFAULT_HISTORY_RETENTION_DAYS
import com.anopticlabs.gravel.notebook.reminders.DEFAULT_QUICK_REMIND_MINUTES
import com.anopticlabs.gravel.notebook.reminders.DEFAULT_SWIPE_SNOOZE_MINUTES
import com.anopticlabs.gravel.notebook.reminders.MAX_QUICK_REMIND_MINUTES
import com.anopticlabs.gravel.notebook.reminders.MIN_QUICK_REMIND_MINUTES

/** The Notebook's user settings, in their own preferences file so the receivers read them without the app's config. */
class NotebookPreferences(context: Context) : NotebookSettingsStore {

    private val prefs by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    override val swipeSnoozeMinutes: Int
        get() = prefs.getInt(KEY_SWIPE_SNOOZE_MINUTES, DEFAULT_SWIPE_SNOOZE_MINUTES).takeIf { it > 0 } ?: DEFAULT_SWIPE_SNOOZE_MINUTES

    override val historyRetentionDays: Int
        get() = prefs.getInt(KEY_HISTORY_RETENTION_DAYS, DEFAULT_HISTORY_RETENTION_DAYS)

    override val quickRemindMinutes: Int
        get() = prefs.getInt(KEY_QUICK_REMIND_MINUTES, DEFAULT_QUICK_REMIND_MINUTES).coerceIn(MIN_QUICK_REMIND_MINUTES, MAX_QUICK_REMIND_MINUTES)

    override fun setSwipeSnoozeMinutes(minutes: Int) = prefs.edit().putInt(KEY_SWIPE_SNOOZE_MINUTES, minutes.coerceAtLeast(1)).apply()

    /** Zero keeps history forever. */
    override fun setHistoryRetentionDays(days: Int) = prefs.edit().putInt(KEY_HISTORY_RETENTION_DAYS, days.coerceAtLeast(0)).apply()

    override fun setQuickRemindMinutes(minutes: Int) =
        prefs.edit().putInt(KEY_QUICK_REMIND_MINUTES, minutes.coerceIn(MIN_QUICK_REMIND_MINUTES, MAX_QUICK_REMIND_MINUTES)).apply()

    /** The trigger word is validated again where it is used, so a stored word that no longer validates switches the feature off. */
    override val captureSettings: CaptureSettings
        get() = CaptureSettings(
            triggerEnabled = prefs.getBoolean(KEY_TRIGGER_ENABLED, false),
            triggerWord = prefs.getString(KEY_TRIGGER_WORD, null).orEmpty(),
        )

    override fun setCaptureSettings(settings: CaptureSettings) = prefs.edit()
        .putBoolean(KEY_TRIGGER_ENABLED, settings.triggerEnabled)
        .putString(KEY_TRIGGER_WORD, settings.triggerWord)
        .apply()

    /** Off, with no calendar picked, until the user turns it on in Settings. */
    override val phoneCalendar: PhoneCalendarChoice
        get() = PhoneCalendarChoice(
            shown = prefs.getBoolean(KEY_PHONE_CALENDAR_SHOWN, false),
            calendars = prefs.getStringSet(KEY_PHONE_CALENDARS, null).orEmpty().toSet(),
        )

    override fun setPhoneCalendarShown(shown: Boolean) = prefs.edit().putBoolean(KEY_PHONE_CALENDAR_SHOWN, shown).apply()

    override fun setPhoneCalendars(calendars: Set<String>) = prefs.edit().putStringSet(KEY_PHONE_CALENDARS, calendars).apply()

    override val interruptLongCaptures: Boolean
        get() = prefs.getBoolean(KEY_INTERRUPT_LONG_CAPTURES, true)

    override fun setInterruptLongCaptures(on: Boolean) = prefs.edit().putBoolean(KEY_INTERRUPT_LONG_CAPTURES, on).apply()

    override val longCapturesWaitForPower: Boolean
        get() = prefs.getBoolean(KEY_LONG_CAPTURES_WAIT_FOR_POWER, true)

    override fun setLongCapturesWaitForPower(on: Boolean) = prefs.edit().putBoolean(KEY_LONG_CAPTURES_WAIT_FOR_POWER, on).apply()

    /** The text model chosen for the Notebook; null leaves the pick to the recommendation. */
    val textModelId: String?
        get() = prefs.getString(KEY_TEXT_MODEL_ID, null)

    fun setTextModelId(id: String?) = prefs.edit().putString(KEY_TEXT_MODEL_ID, id).apply()

    /** Whether the Notebook tab is in the bottom bar; shown until the user turns it off. */
    val tabShown: Boolean
        get() = prefs.getBoolean(KEY_TAB_SHOWN, true)

    fun setTabShown(shown: Boolean) = prefs.edit().putBoolean(KEY_TAB_SHOWN, shown).apply()

    companion object {
        const val FILE_NAME = "notebook"
        const val KEY_SWIPE_SNOOZE_MINUTES = "swipe_snooze_minutes"
        const val KEY_HISTORY_RETENTION_DAYS = "history_retention_days"
        const val KEY_QUICK_REMIND_MINUTES = "quick_remind_minutes"
        const val KEY_TRIGGER_ENABLED = "trigger_enabled"
        const val KEY_TRIGGER_WORD = "trigger_word"
        const val KEY_TEXT_MODEL_ID = "text_model_id"
        const val KEY_TAB_SHOWN = "tab_shown"
        const val KEY_PHONE_CALENDAR_SHOWN = "phone_calendar_shown"
        const val KEY_PHONE_CALENDARS = "phone_calendars"
        const val KEY_INTERRUPT_LONG_CAPTURES = "interrupt_long_captures"
        const val KEY_LONG_CAPTURES_WAIT_FOR_POWER = "long_captures_wait_for_power"
    }
}
