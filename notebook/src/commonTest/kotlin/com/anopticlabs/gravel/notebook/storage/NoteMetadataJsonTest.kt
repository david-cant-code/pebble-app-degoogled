package com.anopticlabs.gravel.notebook.storage

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NoteMetadataJsonTest {

    private val full = NoteMetadata(
        folder = "work",
        name = "note.md",
        hash = "abc123",
        modifiedAtMillis = 1_716_998_400_500L,
        title = "Some title",
        date = "2026-05-29",
    )

    @Test
    fun theOnDiskKeysAreTheFieldNamesAndNullsAreOmitted() {
        val keys = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(full)).jsonObject.keys
        assertEquals(setOf("folder", "name", "hash", "modifiedAtMillis", "title", "date"), keys)

        val sparse = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(NoteMetadata("work", "a.md", "h", 1L))).jsonObject
        assertEquals(setOf("folder", "name", "hash", "modifiedAtMillis"), sparse.keys)
    }

    @Test
    fun theIndexFileAndTheNoteRecordCarryTheirVersionAndTheirFields() {
        val index = NoteIndexFile(NoteIndexFile.VERSION, mapOf("0123456789abcdef0123456789abcdef" to full))
        val indexJson = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(index)).jsonObject
        assertEquals(setOf("v", "notes"), indexJson.keys)
        assertEquals("1", indexJson.getValue("v").toString())

        val record = NoteRecord(NoteRecord.VERSION, "work", "a.md", "Title", "2026-05-31", 5L, "body")
        val recordJson = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(record)).jsonObject
        assertEquals(setOf("v", "folder", "name", "title", "date", "modifiedAtMillis", "content"), recordJson.keys)
        assertEquals(record, NotebookJson.decodeFromString<NoteRecord>(NotebookJson.encodeToString(record)))
        val bare = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(record.copy(title = null, date = null))).jsonObject
        assertEquals(setOf("v", "folder", "name", "modifiedAtMillis", "content"), bare.keys)

        val stems = NoteStemsFile(NoteStemsFile.VERSION, mapOf("a123" to NoteStems("h", setOf("word"), setOf("tag"))))
        val stemsJson = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(stems)).jsonObject
        assertEquals(setOf("v", "notes"), stemsJson.keys)
        assertEquals(setOf("hash", "tokens", "tags"), stemsJson.getValue("notes").jsonObject.getValue("a123").jsonObject.keys)
    }

    @Test
    fun theIndexAndStemsFilesDecodeAndTheirCheckRefusesWhatTheReaderRefuses() {
        val id = "0123456789abcdef0123456789abcdef"
        val index = NotebookJson.decodeFromString<NoteIndexFile>("""{"v":1,"notes":{"$id":{"folder":"work","name":"a.md","hash":"h","modifiedAtMillis":1}}}""")
        index.requireWellFormed()
        assertEquals(mapOf(id to NoteMetadata("work", "a.md", "h", 1L)), index.notes)
        assertFailsWith<IllegalArgumentException> { NoteIndexFile(2, emptyMap()).requireWellFormed() }

        val stems = NotebookJson.decodeFromString<NoteStemsFile>("""{"v":1,"notes":{"$id":{"hash":"h","tokens":["word"]}}}""")
        stems.requireWellFormed('0')
        assertEquals(NoteStems("h", setOf("word")), stems.notes.getValue(id))
        assertFailsWith<IllegalArgumentException> { stems.requireWellFormed('1') }
        assertFailsWith<IllegalArgumentException> { NoteStemsFile(2, emptyMap()).requireWellFormed('0') }
    }

    @Test
    fun entriesRoundTripAloneAndInsideTheIndexMap() {
        assertEquals(full, NotebookJson.decodeFromString<NoteMetadata>(NotebookJson.encodeToString(full)))
        val index = mapOf("a" to full, "b" to NoteMetadata("home", "note.md", "b", 2L))
        assertEquals(index, NotebookJson.decodeFromString<Map<String, NoteMetadata>>(NotebookJson.encodeToString(index)))
    }

    @Test
    fun aMissingOptionalFieldReadsAsNullAndAnUnknownKeyIsIgnored() {
        val m = NotebookJson.decodeFromString<NoteMetadata>("""{"folder":"w","name":"a.md","hash":"h9","modifiedAtMillis":1,"future":"x"}""")
        assertEquals("h9", m.hash)
        assertNull(m.title)
        assertNull(m.date)
    }

    @Test
    fun anEntryWithoutItsFolderNameHashOrTimestampIsAFormatFailure() {
        val where = """"folder":"w","name":"a.md""""
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<NoteMetadata>("""{$where,"modifiedAtMillis":1}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<NoteMetadata>("""{$where,"hash":"h"}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<NoteMetadata>("""{$where,"hash":null,"modifiedAtMillis":1}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<NoteMetadata>("""{"name":"a.md","hash":"h","modifiedAtMillis":1}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<NoteMetadata>("""{"folder":"w","hash":"h","modifiedAtMillis":1}""") }
    }

    @Test
    fun listsWithNullEntriesAreFormatFailures() {
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<List<String>>("""["a",null]""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<Map<String, List<String>>>("""{"work":["a",null]}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<Map<String, List<String>>>("""{"work":null}""") }
    }
}
