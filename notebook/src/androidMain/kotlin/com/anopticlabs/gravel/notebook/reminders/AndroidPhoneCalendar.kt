package com.anopticlabs.gravel.notebook.reminders

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances

/**
 * [PhoneCalendar] over the calendar provider. Of each calendar it reads its identity, name and account; of each event
 * instance its title, times, all-day flag and calendar name. The provider keeps all-day instances at UTC midnights
 * (`android16-release`, CalendarProvider `CalendarInstancesHelper.performInstanceExpansion`), which [PhoneEvent]
 * passes on.
 */
class AndroidPhoneCalendar(private val context: Context) : PhoneCalendar {

    private fun allowed() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private class Listed(val id: Long, val info: PhoneCalendarInfo)

    private fun listed(): List<Listed>? = try {
        context.contentResolver.query(Calendars.CONTENT_URI, CALENDAR_COLUMNS, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val key = calendarKey(cursor.text(3), cursor.text(2), cursor.text(4), cursor.text(5), id)
                    add(Listed(id, PhoneCalendarInfo(key, cursor.text(1).orEmpty(), cursor.text(2).orEmpty())))
                }
            }
        }.orEmpty()
    } catch (_: SecurityException) {
        null
    }

    override fun calendars(): List<PhoneCalendarInfo>? = if (allowed()) listed()?.map { it.info } else null

    override fun events(calendars: Set<String>, window: LongRange): List<PhoneEvent>? {
        if (!allowed()) return null
        if (calendars.isEmpty()) return emptyList()
        val ids = (listed() ?: return null).filter { it.info.key in calendars }.map { it.id }
        if (ids.isEmpty()) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, window.first) }
            .also { ContentUris.appendId(it, window.last) }
            .build()
        val selection = "${Instances.CALENDAR_ID} IN (${ids.joinToString { "?" }})" +
            " AND IFNULL(${Instances.STATUS}, ${Instances.STATUS_TENTATIVE}) != ${Instances.STATUS_CANCELED}" +
            " AND IFNULL(${Instances.SELF_ATTENDEE_STATUS}, ${Attendees.ATTENDEE_STATUS_NONE}) != ${Attendees.ATTENDEE_STATUS_DECLINED}"
        return try {
            context.contentResolver.query(uri, EVENT_COLUMNS, selection, ids.map(Long::toString).toTypedArray(), null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            PhoneEvent(
                                title = cursor.text(0).orEmpty(),
                                beginMillis = cursor.getLong(1),
                                endMillis = cursor.getLong(2),
                                allDay = cursor.getInt(3) != 0,
                                calendarName = cursor.text(4).orEmpty(),
                            ),
                        )
                    }
                }
            }.orEmpty()
        } catch (_: SecurityException) {
            null
        }
    }

    private fun Cursor.text(column: Int): String? = if (isNull(column)) null else getString(column)

    private companion object {
        val CALENDAR_COLUMNS = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.NAME,
            Calendars._SYNC_ID,
        )
        val EVENT_COLUMNS = arrayOf(
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.CALENDAR_DISPLAY_NAME,
        )
    }
}
