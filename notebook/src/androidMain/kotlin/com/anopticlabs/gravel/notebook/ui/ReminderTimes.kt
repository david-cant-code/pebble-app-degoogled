package com.anopticlabs.gravel.notebook.ui

import android.icu.text.DateFormat as IcuDateFormat
import android.icu.util.TimeZone as IcuTimeZone
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Reminder times as the phone shows times: its locale's fields, order and calendar, and its 12- or
 * 24-hour setting, formatted by ICU from skeletons (android.icu.text.DateFormat, API 24). ICU
 * formatters are not thread-safe, so an instance stays on the thread that made it.
 */
internal class ReminderTimes(locale: Locale, val is24Hour: Boolean) {
    private val clock = if (is24Hour) "Hmm" else "hmma"
    private val sameYear = IcuDateFormat.getInstanceForSkeleton("EEEMMMd$clock", locale)
    private val otherYear = IcuDateFormat.getInstanceForSkeleton("yEEEMMMd$clock", locale)

    private fun IcuDateFormat.at(millis: Long): String {
        timeZone = IcuTimeZone.getDefault()
        return format(Date(millis))
    }

    private fun year(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).year

    fun format(millis: Long, nowMillis: Long): String = (if (year(millis) == year(nowMillis)) sameYear else otherYear).at(millis)
}

