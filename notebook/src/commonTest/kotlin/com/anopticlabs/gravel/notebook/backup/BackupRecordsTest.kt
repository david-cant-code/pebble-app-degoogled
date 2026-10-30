package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.reminders.MAX_REMINDER_MESSAGE_CHARS
import com.anopticlabs.gravel.notebook.reminders.MAX_SNOOZE_CHAIN
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.storage.MAX_STORED_INSTANT_MILLIS
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupRecordsTest {

    @Test
    fun recordsEncodeInMillisWithEveryFieldAndDecodeBack() {
        val note = BackupNote("0123456789abcdef0123456789abcdef", "work", "a.md", "T", null, 1_700_000_000_123L, "body")
        val noteJson = BackupRecords.encode(BackupNote.serializer(), note).decodeToString()
        assertEquals("""{"id":"0123456789abcdef0123456789abcdef","folder":"work","name":"a.md","title":"T","modifiedAtMillis":1700000000123,"content":"body"}""", noteJson)
        assertEquals(note, BackupRecords.decode(BackupNote.serializer(), noteJson.encodeToByteArray()))

        val reminders = listOf(ReminderItem(1, 5L, "m", RecurrenceRule(RecurrenceFrequency.DAILY)).toBackup())
        val reminderJson = BackupRecords.encode(BackupRecords.REMINDERS, reminders).decodeToString()
        for (key in listOf("\"frequency\":\"daily\"", "\"interval\":1", "\"anchorMillis\":0", "\"remindAtMillis\":5")) assertTrue(key in reminderJson, reminderJson)
        val eventJson = BackupRecords.encode(BackupRecords.EVENTS, listOf(ReminderEvent(1, 5L, "m", 6L).toBackup())).decodeToString()
        for (key in listOf("\"reminderId\":1", "\"originalFireMillis\":5", "\"firedAt\":6", "\"snoozeChainCount\":0", "\"setInCalendar\":false")) assertTrue(key in eventJson, eventJson)
        assertTrue("null" !in eventJson, eventJson)
        val toCheck = ReminderItem(2, 5L, "m", timeToCheck = true)
        assertEquals(toCheck, BackupRecords.decode(BackupRecords.REMINDERS, BackupRecords.encode(BackupRecords.REMINDERS, listOf(toCheck.toBackup()))).single().toItem())
        val calendarEvent = ReminderEvent(1, 5L, "m", 6L, setInCalendar = true, lateNightFireAt = 7L)
        assertEquals(calendarEvent, BackupRecords.decode(BackupRecords.EVENTS, BackupRecords.encode(BackupRecords.EVENTS, listOf(calendarEvent.toBackup()))).single().toEvent())
    }

    @Test
    fun aCaptureRecordIsItsEntryLengthTheEntryAndTheRecording() {
        val entry = BackupCapture("c1", "r.m4a", 1_700_000_000_000L, attempts = 2, flagged = true)
        val audio = byteArrayOf(9, 8, 7)
        val body = BackupRecords.capture(entry, audio)
        val json = BackupRecords.encode(BackupCapture.serializer(), entry)

        assertContentEquals(byteArrayOf(0, 0, 0, json.size.toByte()) + json + audio, body)
        val parsed = BackupRecords.parseCapture(body)
        assertEquals(entry, parsed.entry)
        assertContentEquals(audio, parsed.audio)
        assertEquals(0, BackupRecords.parseCapture(BackupRecords.capture(entry, ByteArray(0))).audio.size)
    }

    @Test
    fun aRecordThatDoesNotParseOrACaptureCutShortIsDamage() {
        fun problem(block: () -> Unit) = assertFailsWith<InvalidBackupException> { block() }.problem
        assertEquals(BackupProblem.DAMAGED, problem { BackupRecords.decode(BackupNote.serializer(), "not json".encodeToByteArray()) })
        assertEquals(BackupProblem.DAMAGED, problem { BackupRecords.decode(BackupNote.serializer(), """{"id":"x"}""".encodeToByteArray()) })
        assertEquals(BackupProblem.DAMAGED, problem { BackupRecords.parseCapture(byteArrayOf(0, 0, 0)) })
        assertEquals(BackupProblem.DAMAGED, problem { BackupRecords.parseCapture(byteArrayOf(0, 0, 0, 9, 1, 2)) })
        assertEquals(BackupProblem.DAMAGED, problem { BackupRecords.parseCapture(byteArrayOf(-1, -1, -1, -1)) })
        assertEquals(BackupProblem.DAMAGED, problem { BackupRecords.parseCapture(byteArrayOf(0, 0, 0, 2) + "{}".encodeToByteArray()) })
    }

    @Test
    fun aReminderOrEventTheStoreOrTheRestoreWouldRefuseMapsToNull() {
        assertNull(BackupReminder(1, 5L, "m", BackupRecurrence("hourly")).toItem())
        assertNull(BackupReminder(0, 5L, "m").toItem())
        assertNull(BackupReminder(MAX_RESTORED_REMINDER_ID + 1, 5L, "m").toItem())
        assertEquals(MAX_RESTORED_REMINDER_ID, BackupReminder(MAX_RESTORED_REMINDER_ID, 5L, "m").toItem()!!.id)
        assertNull(BackupReminder(1, 5L, "m".repeat(MAX_REMINDER_MESSAGE_CHARS + 1)).toItem())
        assertNull(BackupReminder(1, 5L, "m", BackupRecurrence("daily", interval = 0, anchorMillis = 5L)).toItem())
        assertNull(BackupReminder(1, 1L, "m", BackupRecurrence("yearly", interval = Int.MAX_VALUE, anchorMillis = 1000L)).toItem())
        assertNull(BackupReminder(1, 5L, "m", BackupRecurrence("daily", anchorMillis = Long.MAX_VALUE)).toItem())
        assertNull(BackupReminder(1, 0L, "m").toItem())
        assertNull(BackupReminder(1, MAX_STORED_INSTANT_MILLIS + 1, "m").toItem())
        assertNull(BackupReminderEvent(1, 5L, "m", 6L, snoozeChainCount = -1).toEvent())
        assertNull(BackupReminderEvent(1, 5L, "m", 6L, snoozeChainCount = MAX_SNOOZE_CHAIN + 1).toEvent())
        assertNull(BackupReminderEvent(0, 5L, "m", 6L).toEvent())
        assertNull(BackupReminderEvent(1, 0L, "m", 6L).toEvent())
        assertNull(BackupReminderEvent(1, 5L, "m", Long.MAX_VALUE).toEvent())
        assertNull(BackupReminderEvent(1, 5L, "m", 6L, latestSnoozeFireAt = -1L).toEvent())
        assertNull(BackupReminderEvent(1, 5L, "m", 6L, completedAt = Long.MAX_VALUE).toEvent())
        assertNull(BackupReminderEvent(1, 5L, "m", 6L, cancelledAt = 0L).toEvent())
        assertNull(BackupReminderEvent(1, 5L, "m", 6L, snoozeDeliveredAt = MAX_STORED_INSTANT_MILLIS + 1).toEvent())
        assertNull(BackupReminderEvent(1, 5L, "m", 6L, lateNightFireAt = 0L).toEvent())
        assertEquals(ReminderItem(1, 5L, "m"), BackupReminder(1, 5L, "m").toItem())
        assertEquals(ReminderEvent(1, 5L, "m", 6L, 7L, MAX_SNOOZE_CHAIN, 8L, 9L, 7L), BackupReminderEvent(1, 5L, "m", 6L, 7L, MAX_SNOOZE_CHAIN, 8L, 9L, 7L).toEvent())
    }
}
