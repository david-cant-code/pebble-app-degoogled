package com.anopticlabs.gravel.notebook.alarms

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.alarms.ReminderIntents.putOccurrence
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.ui.NotebookLink
import com.anopticlabs.gravel.notebook.ui.notebookLinkFor
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A notification tap's real Intent, built by the notifier, read back the way MainActivity reads it. */
class NotebookLinkIntentsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = "0123456789abcdef0123456789abcdef"
    private val notifier = AndroidReminderNotifier(context) { token }

    private fun read(intent: Intent, expected: String = token) = notebookLinkFor(
        action = intent.action,
        readString = intent::getStringExtra,
        readInt = { if (intent.hasExtra(it)) intent.getIntExtra(it, 0) else null },
        readLong = { if (intent.hasExtra(it)) intent.getLongExtra(it, 0L) else null },
        launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
        expectedToken = { expected },
    )

    @Test
    fun everyTapIntentCarriesTheTokenAndRoundTrips() {
        val key = ReminderEventKey(7, 1_790_089_200_000L)
        assertEquals(
            NotebookLink.Occurrence(key, snooze = false),
            read(notifier.openIntent(ReminderIntents.ACTION_OPEN_REMINDERS) { putOccurrence(key) }),
        )
        assertEquals(
            NotebookLink.Occurrence(key, snooze = true),
            read(notifier.openIntent(ReminderIntents.ACTION_OPEN_SNOOZE_PICKER) { putOccurrence(key) }),
        )
        assertEquals(NotebookLink.Completions, read(notifier.openIntent(ReminderIntents.ACTION_OPEN_VOICE_COMPLETIONS)))
        assertEquals(NotebookLink.Edit(7), read(notifier.editorIntent(7)))
    }

    @Test
    fun theMissedNoticeOpensTheCalendarOnTheLatestDueDay() {
        val monday = ReminderEvent(7, 1_790_089_200_000L, "meds", 1_790_089_200_000L)
        val wednesday = monday.copy(originalFireMillis = monday.originalFireMillis + 2 * 86_400_000L)
        val tuesday = monday.copy(reminderId = 8, originalFireMillis = monday.originalFireMillis + 86_400_000L)
        assertEquals(NotebookLink.Calendar(wednesday.originalFireMillis), read(notifier.missedNoticeIntent(listOf(monday, wednesday, tuesday))))
    }

    @Test
    fun aTapIntentIsNotRoutedUnderAnotherToken() {
        assertNull(read(notifier.openIntent(ReminderIntents.ACTION_OPEN_VOICE_COMPLETIONS), expected = token.reversed()))
    }
}
