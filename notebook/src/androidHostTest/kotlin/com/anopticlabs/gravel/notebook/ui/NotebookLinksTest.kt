package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.alarms.ReminderIntents
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookLinksTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val fire: Long = 1_790_089_200_000L
    private val id: Int = 7
    private val withToken = mapOf<String, Any>(ReminderIntents.EXTRA_LINK_TOKEN to token)
    private val occurrence = withToken + mapOf(ReminderIntents.EXTRA_REMINDER_ID to id, ReminderIntents.EXTRA_ORIGINAL_FIRE_MILLIS to fire)

    private fun link(action: String?, extras: Map<String, Any>, fromHistory: Boolean = false) = notebookLinkFor(
        action = action,
        readString = { extras[it] as? String },
        readInt = { extras[it] as? Int },
        readLong = { extras[it] as? Long },
        launchedFromHistory = fromHistory,
        expectedToken = { token },
    )

    @Test
    fun aFireTapOpensItsOccurrence() {
        assertEquals(
            NotebookLink.Occurrence(ReminderEventKey(id, fire), snooze = false),
            link(ReminderIntents.ACTION_OPEN_REMINDERS, occurrence),
        )
    }

    @Test
    fun aTapWithoutAnOccurrenceOpensReminders() {
        assertEquals(NotebookLink.Reminders, link(ReminderIntents.ACTION_OPEN_REMINDERS, withToken))
    }

    @Test
    fun theMissedNoticeTapOpensTheCalendarOnItsDayOrRemindersWithoutOne() {
        val day = withToken + mapOf(ReminderIntents.EXTRA_ORIGINAL_FIRE_MILLIS to fire)
        assertEquals(NotebookLink.Calendar(fire), link(ReminderIntents.ACTION_OPEN_REMINDER_CALENDAR, day))
        assertEquals(NotebookLink.Reminders, link(ReminderIntents.ACTION_OPEN_REMINDER_CALENDAR, withToken))
        assertEquals(NotebookLink.Reminders, link(ReminderIntents.ACTION_OPEN_REMINDER_CALENDAR, withToken + mapOf(ReminderIntents.EXTRA_ORIGINAL_FIRE_MILLIS to 0L)))
        assertNull(link(ReminderIntents.ACTION_OPEN_REMINDER_CALENDAR, mapOf(ReminderIntents.EXTRA_ORIGINAL_FIRE_MILLIS to fire)))
    }

    @Test
    fun aSetNoticeTapOpensTheReminderOrRemindersWithoutAValidId() {
        val action = ReminderIntents.ACTION_OPEN_REMINDER_EDITOR
        assertEquals(NotebookLink.Edit(id), link(action, withToken + mapOf(ReminderIntents.EXTRA_REMINDER_ID to id)))
        assertEquals(NotebookLink.Reminders, link(action, withToken + mapOf(ReminderIntents.EXTRA_REMINDER_ID to 0)))
        assertEquals(NotebookLink.Reminders, link(action, withToken))
        assertNull(link(action, mapOf(ReminderIntents.EXTRA_REMINDER_ID to id)))
    }

    @Test
    fun aBackupNoticeTapOpensBackupSettings() {
        assertEquals(NotebookLink.BackupSettings, link(ReminderIntents.ACTION_OPEN_BACKUP_SETTINGS, withToken))
        assertNull(link(ReminderIntents.ACTION_OPEN_BACKUP_SETTINGS, emptyMap()))
    }

    @Test
    fun aKeyReminderTapOpensTheKeyCheck() {
        assertEquals(NotebookLink.KeyCheck, link(ReminderIntents.ACTION_OPEN_KEY_CHECK, withToken))
        assertNull(link(ReminderIntents.ACTION_OPEN_KEY_CHECK, emptyMap()))
    }

    @Test
    fun aSnoozeTapOpensItsOccurrenceForSnoozing() {
        assertEquals(
            NotebookLink.Occurrence(ReminderEventKey(id, fire), snooze = true),
            link(ReminderIntents.ACTION_OPEN_SNOOZE_PICKER, occurrence + (ReminderIntents.EXTRA_MESSAGE to "shown nowhere")),
        )
    }

    @Test
    fun anOccurrenceOutOfRangeFallsBackToReminders() {
        val badId = withToken + mapOf(ReminderIntents.EXTRA_REMINDER_ID to (id - id), ReminderIntents.EXTRA_ORIGINAL_FIRE_MILLIS to fire)
        val badFire = withToken + mapOf(ReminderIntents.EXTRA_REMINDER_ID to id, ReminderIntents.EXTRA_ORIGINAL_FIRE_MILLIS to (fire - fire))
        assertEquals(NotebookLink.Reminders, link(ReminderIntents.ACTION_OPEN_SNOOZE_PICKER, badId))
        assertEquals(NotebookLink.Reminders, link(ReminderIntents.ACTION_OPEN_REMINDERS, badFire))
    }

    @Test
    fun theCompletionsTapOpensCompletions() {
        assertEquals(NotebookLink.Completions, link(ReminderIntents.ACTION_OPEN_VOICE_COMPLETIONS, withToken))
    }

    @Test
    fun aTapWithoutTheTokenIsNotRouted() {
        val missing = occurrence - ReminderIntents.EXTRA_LINK_TOKEN
        assertNull(link(ReminderIntents.ACTION_OPEN_REMINDERS, missing))
        assertNull(link(ReminderIntents.ACTION_OPEN_REMINDERS, missing + (ReminderIntents.EXTRA_LINK_TOKEN to "")))
        assertNull(link(ReminderIntents.ACTION_OPEN_REMINDERS, missing + (ReminderIntents.EXTRA_LINK_TOKEN to token.reversed())))
    }

    @Test
    fun theTokenIsLookedUpOnlyForAnIntentThatCarriesOne() {
        var lookups = 0
        val result = notebookLinkFor(
            action = ReminderIntents.ACTION_OPEN_REMINDERS,
            readString = { null },
            readInt = { null },
            readLong = { null },
            launchedFromHistory = false,
            expectedToken = { lookups++; token },
        )
        assertNull(result)
        assertEquals(0, lookups)
    }

    @Test
    fun aRelaunchFromRecentsIsNotRouted() {
        assertNull(link(ReminderIntents.ACTION_OPEN_REMINDERS, occurrence, fromHistory = true))
    }

    @Test
    fun extrasThatFailToUnparcelAreNotRouted() {
        val result = notebookLinkFor(
            action = ReminderIntents.ACTION_OPEN_REMINDERS,
            readString = { throw RuntimeException("unparcel") },
            readInt = { null },
            readLong = { null },
            launchedFromHistory = false,
            expectedToken = { token },
        )
        assertNull(result)
    }

    @Test
    fun onlyTheSevenOpenActionsAreNotebookLinks() {
        listOf(
            ReminderIntents.ACTION_OPEN_REMINDERS,
            ReminderIntents.ACTION_OPEN_SNOOZE_PICKER,
            ReminderIntents.ACTION_OPEN_REMINDER_EDITOR,
            ReminderIntents.ACTION_OPEN_VOICE_COMPLETIONS,
            ReminderIntents.ACTION_OPEN_REMINDER_CALENDAR,
            ReminderIntents.ACTION_OPEN_BACKUP_SETTINGS,
            ReminderIntents.ACTION_OPEN_KEY_CHECK,
        ).forEach { assertTrue(isNotebookLinkAction(it), it) }
        listOf(null, "android.intent.action.MAIN", ReminderIntents.ACTION_COMPLETE).forEach {
            assertFalse(isNotebookLinkAction(it), it.toString())
            assertNull(link(it, occurrence))
        }
    }
}

class NotebookLinkTokenTest {

    private val dir = Files.createTempDirectory("token").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun theTokenIsRandomHexAndOutlivesTheInstance() {
        val first = NotebookLinkToken(dir).value
        assertTrue(Regex("[0-9a-f]{32}").matches(first))
        assertEquals(first, NotebookLinkToken(dir).value)
        val other = File(dir, "other")
        assertNotEquals(first, NotebookLinkToken(other).value)
    }

    @Test
    fun aMalformedFileIsReplaced() {
        File(dir, "notebook_link_token").writeText("not a token")
        val token = NotebookLinkToken(dir).value
        assertTrue(Regex("[0-9a-f]{32}").matches(token))
        assertEquals(token, NotebookLinkToken(dir).value)
    }

    @Test
    fun aTokenThatCannotBeSavedStillServesThisInstance() {
        val notADirectory = File(dir, "file").apply { writeText("x") }
        val token = NotebookLinkToken(notADirectory)
        assertTrue(Regex("[0-9a-f]{32}").matches(token.value))
        assertEquals(token.value, token.value)
    }
}
