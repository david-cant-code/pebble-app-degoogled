package com.anopticlabs.gravel.notebook.reminders

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.alarms.NotebookPreferences
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [AndroidPhoneCalendar] against the phone's calendar provider, on local calendars the test adds and removes. It
 * grants Gravel calendar access, which outlasts the run (revoking it here would end the test's own process).
 */
class AndroidPhoneCalendarDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val account = "gravel-test-${System.nanoTime()}"
    private val zone = TimeZone.of("America/Los_Angeles")
    private val phone = AndroidPhoneCalendar(context)
    private var chosen = -1L
    private var other = -1L

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    @Before
    fun addCalendars() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        chosen = insertCalendar("Gravel test")
        other = insertCalendar("Gravel other")
    }

    @After
    fun removeCalendars() {
        resolver.delete(
            asSyncAdapter(Calendars.CONTENT_URI),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(account, CalendarContract.ACCOUNT_TYPE_LOCAL),
        )
    }

    private fun keyOf(id: Long): String = assertNotNull(phone.calendars()).single { it.key == listedKey(id) }.key

    private fun listedKey(id: Long): String {
        resolver.query(asSyncAdapter(Calendars.CONTENT_URI), arrayOf(Calendars.ACCOUNT_TYPE, Calendars.ACCOUNT_NAME, Calendars.NAME, Calendars._SYNC_ID), "${Calendars._ID} = ?", arrayOf(id.toString()), null)!!.use {
            it.moveToFirst()
            return calendarKey(it.getString(0), it.getString(1), it.getString(2), it.getString(3), id)
        }
    }

    private fun insertCalendar(name: String): Long = ContentUris.parseId(
        assertNotNull(
            resolver.insert(
                asSyncAdapter(Calendars.CONTENT_URI),
                ContentValues().apply {
                    put(Calendars.ACCOUNT_NAME, account)
                    put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(Calendars.NAME, name)
                    put(Calendars.CALENDAR_DISPLAY_NAME, name)
                    put(Calendars.CALENDAR_COLOR, 0xFF336699.toInt())
                    put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                    put(Calendars.OWNER_ACCOUNT, account)
                    put(Calendars.VISIBLE, 1)
                    put(Calendars.SYNC_EVENTS, 1)
                },
            ),
        ),
    )

    private fun insertEvent(calendar: Long, title: String, begin: Long, allDay: Boolean = false, more: ContentValues.() -> Unit = {}): Long =
        ContentUris.parseId(
            assertNotNull(
                resolver.insert(
                    asSyncAdapter(Events.CONTENT_URI),
                    ContentValues().apply {
                        put(Events.CALENDAR_ID, calendar)
                        put(Events.TITLE, title)
                        put(Events.DTSTART, begin)
                        put(Events.ALL_DAY, if (allDay) 1 else 0)
                        put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
                        more()
                        if (!containsKey(Events.DURATION)) put(Events.DTEND, begin + if (allDay) DAY else HOUR)
                    },
                ),
            ),
        )

    private fun at(day: LocalDate, hour: Int) = LocalDateTime(day.year, day.month, day.day, hour, 0).toInstant(zone).toEpochMilliseconds()

    @Test
    fun theChosenCalendarsInstancesAreReadWithoutCanceledOrDeclinedOnes() {
        val day = LocalDate(2031, 3, 12)
        val days = day..LocalDate(2031, 3, 18)
        insertEvent(chosen, "Meeting", at(day, 10))
        insertEvent(chosen, "Holiday", day.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(), allDay = true)
        insertEvent(chosen, "Standup", at(day, 9)) {
            put(Events.RRULE, "FREQ=DAILY;COUNT=3")
            put(Events.DURATION, "PT15M")
        }
        insertEvent(chosen, "Canceled", at(day, 11)) { put(Events.STATUS, Events.STATUS_CANCELED) }
        insertEvent(chosen, "Declined", at(day, 12)) { put(Events.SELF_ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_DECLINED) }
        insertEvent(other, "Other calendar", at(day, 13))
        insertEvent(chosen, "Too late", at(LocalDate(2031, 4, 20), 10))

        val read = assertNotNull(phone.events(setOf(keyOf(chosen)), phoneQueryWindow(days, zone)))

        assertEquals(
            listOf("Holiday", "Meeting", "Standup", "Standup", "Standup"),
            read.map { it.title }.sorted(),
        )
        assertEquals(PhoneEvent("Meeting", at(day, 10), at(day, 10) + HOUR, allDay = false, calendarName = "Gravel test"), read.single { it.title == "Meeting" })
        val allDay = read.single { it.title == "Holiday" }
        assertTrue(allDay.allDay)
        assertEquals(day.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(), allDay.beginMillis)
        val byDay = phoneEventsByDay(read, days, zone)
        assertEquals(listOf("Holiday", "Standup", "Meeting"), byDay[day]?.map { it.title })
        assertEquals(listOf(day, LocalDate(2031, 3, 13), LocalDate(2031, 3, 14)), byDay.filterValues { list -> list.any { it.title == "Standup" } }.keys.sorted())
    }

    @Test
    fun calendarsAreListedWithTheirAccounts() {
        val listed = assertNotNull(phone.calendars()).filter { it.account == account }.sortedBy { it.name }
        assertEquals(listOf(PhoneCalendarInfo(listedKey(other), "Gravel other", account), PhoneCalendarInfo(listedKey(chosen), "Gravel test", account)), listed)
        assertEquals(emptyList(), phone.events(emptySet(), 0L..Long.MAX_VALUE))
    }

    @Test
    fun aPickFollowsItsCalendarRatherThanItsRowId() {
        val day = LocalDate(2031, 3, 12)
        val window = phoneQueryWindow(day..day, zone)
        val picked = keyOf(chosen)
        deleteCalendar(chosen)
        val replacement = insertCalendar("Gravel replacement")
        insertEvent(replacement, "Not picked", at(day, 10))
        assertEquals(emptyList(), phone.events(setOf(picked), window))
        val recreated = insertCalendar("Gravel test")
        insertEvent(recreated, "Picked again", at(day, 11))
        assertEquals(listOf("Picked again"), phone.events(setOf(picked), window)?.map { it.title })
    }

    @Test
    fun thePhoneCalendarChoiceRoundTripsThroughThePreferences() {
        val prefs = NotebookPreferences(context)
        val before = prefs.phoneCalendar
        try {
            prefs.setPhoneCalendarShown(true)
            prefs.setPhoneCalendars(setOf(listedKey(chosen), listedKey(other)))
            assertEquals(PhoneCalendarChoice(shown = true, calendars = setOf(listedKey(chosen), listedKey(other))), NotebookPreferences(context).phoneCalendar)
        } finally {
            prefs.setPhoneCalendarShown(before.shown)
            prefs.setPhoneCalendars(before.calendars)
        }
    }

    private fun deleteCalendar(id: Long) {
        resolver.delete(ContentUris.withAppendedId(asSyncAdapter(Calendars.CONTENT_URI), id), null, null)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
