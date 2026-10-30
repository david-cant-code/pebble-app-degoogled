package com.anopticlabs.gravel.notebook.capture

import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueuedCaptureJsonTest {

    private val full = QueuedCapture("7c1e", "recording_1.m4a", 1_716_998_400_500L, attempts = 3, flagged = true, zoneId = "Europe/Berlin", phoneTried = true,
        serverTries = 2, serverTriedAtMillis = 1_716_998_500_000L)

    @Test
    fun theOnDiskKeysAreTheFieldNamesAndDefaultsAreOmitted() {
        val keys = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(full)).jsonObject.keys
        assertEquals(setOf("id", "originalFileName", "queuedAtMillis", "attempts", "flagged", "zoneId", "phoneTried", "serverTries", "serverTriedAtMillis"), keys)

        val fresh = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(QueuedCapture("a", "a.m4a", 1L))).jsonObject
        assertEquals(setOf("id", "originalFileName", "queuedAtMillis"), fresh.keys)
    }

    @Test
    fun entriesRoundTripAloneAndAsTheQueueList() {
        assertEquals(full, NotebookJson.decodeFromString<QueuedCapture>(NotebookJson.encodeToString(full)))
        val queue = listOf(full, QueuedCapture("b", "b.m4a", 2L))
        val json = NotebookJson.encodeToString(queue)
        assertEquals(2, NotebookJson.parseToJsonElement(json).jsonArray.size)
        assertEquals(queue, NotebookJson.decodeFromString<List<QueuedCapture>>(json))
    }

    @Test
    fun aMissingCounterReadsAsItsDefaultAndAnUnknownKeyIsIgnored() {
        val e = NotebookJson.decodeFromString<QueuedCapture>("""{"id":"x","originalFileName":"x.m4a","queuedAtMillis":5,"future":1}""")
        assertEquals(QueuedCapture("x", "x.m4a", 5L, 0, false), e)
    }

    @Test
    fun anEntryWrittenBeforeTheZoneWasStoredHasNone() {
        assertEquals(null, NotebookJson.decodeFromString<QueuedCapture>("""{"id":"x","originalFileName":"x.m4a","queuedAtMillis":5}""").zoneId)
    }

    @Test
    fun aZoneIsUsedOnlyWhenTheRuntimeKnowsIt() {
        assertEquals("Europe/Berlin", captureZone("Europe/Berlin")?.id)
        assertEquals(null, captureZone(null))
        assertEquals(null, captureZone("Not/AZone"))
        assertEquals(null, captureZone(""))
        assertEquals(null, captureZone("Europe/" + "x".repeat(MAX_ZONE_ID_CHARS)))
    }

    @Test
    fun anEntryWithoutItsIdNameOrTimeIsAFormatFailure() {
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<QueuedCapture>("""{"originalFileName":"x.m4a","queuedAtMillis":5}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<QueuedCapture>("""{"id":"x","queuedAtMillis":5}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<QueuedCapture>("""{"id":"x","originalFileName":"x.m4a"}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<List<QueuedCapture>>("""[null]""") }
    }
}
