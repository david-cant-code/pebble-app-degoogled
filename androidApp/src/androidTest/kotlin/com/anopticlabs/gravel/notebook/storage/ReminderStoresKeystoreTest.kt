package com.anopticlabs.gravel.notebook.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four reminder stores over the real Keystore codec, in a throwaway directory beside the
 * Notebook root.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.storage.ReminderStoresKeystoreTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class ReminderStoresKeystoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "notebook-reminders-test")
    private val crypto = appKeyCrypto()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun remindersAndTheirEventsRoundTripThroughTheKeystore() = runBlocking {
        val rule = RecurrenceRule(RecurrenceFrequency.WEEKLY, 2, 1_700_000_000_000L)
        val reminders = ReminderStore(root, crypto)
        val weekly = reminders.create { ReminderItem(it, 1_700_604_800_000L, "weekly", rule) }
        val once = reminders.create { ReminderItem(it, 5L, "once") }
        assertTrue(reminders.replace(weekly.copy(remindAtMillis = 1_701_209_600_000L)))
        assertTrue(reminders.remove(once.id))

        val events = ReminderEventStore(root, crypto)
        assertTrue(events.appendFire(ReminderEvent(weekly.id, 1_700_604_800_000L, "weekly", 1_700_604_800_100L)))
        events.markCompleted(ReminderEventKey(weekly.id, 1_700_604_800_000L), 1_700_605_000_000L)

        val fresh = ReminderStore(root, crypto).readAll(strict = true).single()
        assertEquals(weekly.copy(remindAtMillis = 1_701_209_600_000L), fresh)
        assertEquals(1_700_605_000_000L, ReminderEventStore(root, crypto).readAll(strict = true).single().completedAt)
        assertFalse(File(root, EncryptedFiles.QUARANTINE_DIR).exists())
    }

    @Test
    fun completionsRoundTripThroughTheKeystore() = runBlocking {
        val completions = VoiceCompletionStore(root, crypto)
        completions.save(VoiceCompletion("c1", "took the meds", 2L))

        assertEquals("took the meds", VoiceCompletionStore(root, crypto).get("c1")?.transcript)
        assertTrue(completions.remove("c1"))
        assertEquals(emptyList(), completions.readAll(strict = true))
        assertFalse(File(root, EncryptedFiles.QUARANTINE_DIR).exists())
    }
}
