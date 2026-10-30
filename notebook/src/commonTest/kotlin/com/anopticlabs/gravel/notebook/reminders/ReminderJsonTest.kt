package com.anopticlabs.gravel.notebook.reminders

import com.anopticlabs.gravel.notebook.storage.NotebookJson
import com.anopticlabs.gravel.notebook.storage.ReminderFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReminderJsonTest {

    private val rule = RecurrenceRule(RecurrenceFrequency.WEEKLY, interval = 2, anchorMillis = 1_700_000_000_000L)
    private val item = ReminderItem(42, 1_700_604_800_000L, "weekly test", rule)
    private val event = ReminderEvent(7, 100L, "m", firedAt = 105L, latestSnoozeFireAt = 170L, snoozeChainCount = 2, completedAt = 200L, cancelledAt = 300L, snoozeDeliveredAt = 175L)

    private fun keys(json: String) = NotebookJson.parseToJsonElement(json).jsonObject.keys

    @Test
    fun theOnDiskKeysAreTheFieldNamesAndDefaultsAreOmitted() {
        val json = NotebookJson.encodeToString(item)
        assertEquals(setOf("id", "remindAtMillis", "message", "recurrence"), keys(json))
        val recurrence = NotebookJson.parseToJsonElement(json).jsonObject.getValue("recurrence").jsonObject
        assertEquals(setOf("frequency", "interval", "anchorMillis"), recurrence.keys)
        assertEquals("WEEKLY", recurrence.getValue("frequency").jsonPrimitive.content)

        assertEquals(setOf("id", "remindAtMillis", "message"), keys(NotebookJson.encodeToString(ReminderItem(1, 2L, "one-shot"))))
        assertEquals(setOf("frequency"), keys(NotebookJson.encodeToString(RecurrenceRule(RecurrenceFrequency.DAILY))))
        assertEquals(
            setOf("reminderId", "originalFireMillis", "message", "firedAt", "latestSnoozeFireAt", "snoozeChainCount", "completedAt", "cancelledAt", "snoozeDeliveredAt"),
            keys(NotebookJson.encodeToString(event)),
        )
        assertEquals(setOf("reminderId", "originalFireMillis", "message", "firedAt"), keys(NotebookJson.encodeToString(ReminderEvent(1, 2L, "m", 3L))))
    }

    @Test
    fun theRemindersFileCarriesTheNextIdAndReadsEmptyFromAnEmptyObject() {
        val file = ReminderFile(43, listOf(item))
        assertEquals(setOf("nextId", "reminders"), keys(NotebookJson.encodeToString(file)))
        assertEquals(file, NotebookJson.decodeFromString<ReminderFile>(NotebookJson.encodeToString(file)))
        assertEquals(ReminderFile(), NotebookJson.decodeFromString<ReminderFile>("{}"))
        assertEquals(setOf<String>(), keys(NotebookJson.encodeToString(ReminderFile())))
    }

    @Test
    fun everyStoredTypeRoundTripsAloneAndInAList() {
        assertEquals(item, NotebookJson.decodeFromString<ReminderItem>(NotebookJson.encodeToString(item)))
        assertEquals(listOf(item, ReminderItem(1, 2L, "x")), NotebookJson.decodeFromString<List<ReminderItem>>(NotebookJson.encodeToString(listOf(item, ReminderItem(1, 2L, "x")))))
        assertEquals(event, NotebookJson.decodeFromString<ReminderEvent>(NotebookJson.encodeToString(event)))
        val completion = VoiceCompletion("9f", "took the meds", 6L)
        assertEquals(completion, NotebookJson.decodeFromString<VoiceCompletion>(NotebookJson.encodeToString(completion)))
    }

    @Test
    fun anEntryWithoutOptionalFieldsReadsWithDefaultsAndUnknownKeysAreIgnored() {
        val item = NotebookJson.decodeFromString<List<ReminderItem>>("""[{"id":7,"remindAtMillis":1710000000000,"message":"m","future":true}]""").single()
        assertEquals(ReminderItem(7, 1_710_000_000_000L, "m"), item)
        assertNull(item.recurrence)
        val bare = NotebookJson.decodeFromString<ReminderEvent>("""{"reminderId":1,"originalFireMillis":2,"message":"m","firedAt":3}""")
        assertEquals(ReminderEvent(1, 2L, "m", 3L), bare)
        assertEquals(RecurrenceRule(RecurrenceFrequency.MONTHLY, 1, 0L), NotebookJson.decodeFromString<RecurrenceRule>("""{"frequency":"MONTHLY"}"""))
    }

    @Test
    fun aMissingRequiredFieldANullInItOrAnUnknownFrequencyFailsToParse() {
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<ReminderItem>("""{"id":1,"message":"m"}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<ReminderItem>("""{"id":1,"remindAtMillis":null,"message":"m"}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<RecurrenceRule>("""{"frequency":"daily"}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<ReminderEvent>("""{"reminderId":1,"originalFireMillis":2,"message":"m"}""") }
    }
}
