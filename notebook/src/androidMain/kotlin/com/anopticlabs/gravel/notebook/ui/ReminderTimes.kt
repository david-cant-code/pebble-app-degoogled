package com.anopticlabs.gravel.notebook.ui

import android.icu.text.DateFormat as IcuDateFormat
import android.icu.util.TimeZone as IcuTimeZone
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import kotlinx.datetime.toJavaLocalDate

/**
 * Reminder times as the phone shows times: its locale's fields, order and calendar, and its 12- or
 * 24-hour setting, formatted by ICU from skeletons (android.icu.text.DateFormat, API 24). ICU
 * formatters are not thread-safe, so an instance stays on the thread that made it.
 */
internal class ReminderTimes(locale: Locale, val is24Hour: Boolean) {
    private val clock = if (is24Hour) "Hmm" else "hmma"
    private val sameYear = IcuDateFormat.getInstanceForSkeleton("EEEMMMd$clock", locale)
    private val otherYear = IcuDateFormat.getInstanceForSkeleton("yEEEMMMd$clock", locale)
    private val dateOnly = IcuDateFormat.getInstanceForSkeleton("yEEEMMMd", locale)
    private val timeOnly = IcuDateFormat.getInstanceForSkeleton(clock, locale)
    private val monthTitle = IcuDateFormat.getInstanceForSkeleton("yMMMM", locale)
    private val dayTitle = IcuDateFormat.getInstanceForSkeleton("yMMMMEEEEd", locale)

    private fun IcuDateFormat.at(millis: Long): String {
        timeZone = IcuTimeZone.getDefault()
        return format(Date(millis))
    }

    private fun year(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).year

    fun format(millis: Long, nowMillis: Long): String = (if (year(millis) == year(nowMillis)) sameYear else otherYear).at(millis)

    fun date(millis: Long): String = dateOnly.at(millis)

    fun time(millis: Long): String = timeOnly.at(millis)

    fun month(day: kotlinx.datetime.LocalDate): String = monthTitle.at(noon(day))

    fun day(day: kotlinx.datetime.LocalDate): String = dayTitle.at(noon(day))

    private fun noon(day: kotlinx.datetime.LocalDate): Long =
        day.toJavaLocalDate().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        /** A date picker's selection (UTC midnight of the picked day) for the local day of [millis]. */
        fun pickerDate(millis: Long): Long =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        /** [millis] moved to the day a date picker selected, keeping its local time. */
        fun withDate(millis: Long, pickedUtcMillis: Long): Long {
            val day = Instant.ofEpochMilli(pickedUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            return at(day, Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime())
        }

        /** [millis] moved to [hour]:[minute] on its local day. */
        fun withTime(millis: Long, hour: Int, minute: Int): Long =
            at(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate(), LocalTime.of(hour, minute))

        fun localTime(millis: Long): LocalTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()

        private fun at(day: LocalDate, time: LocalTime): Long =
            RemindersViewModel.wholeMinute(LocalDateTime.of(day, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }
}

@Composable
internal fun rememberReminderTimes(): ReminderTimes {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, is24Hour) { ReminderTimes(locale, is24Hour) }
}
