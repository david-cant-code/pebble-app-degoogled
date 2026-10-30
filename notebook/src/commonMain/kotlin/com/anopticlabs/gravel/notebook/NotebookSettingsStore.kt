package com.anopticlabs.gravel.notebook

import com.anopticlabs.gravel.notebook.pipeline.CaptureSettings
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendarChoice
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings

/** The Notebook's user settings, as Settings > Notebook reads and writes them. */
interface NotebookSettingsStore : ReminderSettings {
    val quickRemindMinutes: Int
    val captureSettings: CaptureSettings

    fun setSwipeSnoozeMinutes(minutes: Int)

    fun setHistoryRetentionDays(days: Int)

    fun setQuickRemindMinutes(minutes: Int)

    fun setCaptureSettings(settings: CaptureSettings)

    val phoneCalendar: PhoneCalendarChoice

    fun setPhoneCalendarShown(shown: Boolean)

    fun setPhoneCalendars(calendars: Set<String>)

    /** Whether a short capture stops a long one mid-transcription; the long one starts over after it. */
    val interruptLongCaptures: Boolean

    fun setInterruptLongCaptures(on: Boolean)

    /** Whether long captures wait until the phone is on power. */
    val longCapturesWaitForPower: Boolean

    fun setLongCapturesWaitForPower(on: Boolean)
}
