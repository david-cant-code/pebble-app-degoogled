package com.anopticlabs.gravel.notebook.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Golden vectors for the prompts and the reply parser, in `resources/golden`. */
class LlmGoldenTest {

    private fun golden(name: String): JsonArray {
        val stream = checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "missing golden fixture $name" }
        val text = stream.reader(Charsets.UTF_8).use { it.readText() }
        return Json.parseToJsonElement(text).jsonArray
    }

    @Test
    fun promptsMatchTheirGoldenRows() {
        val sentinels = mapOf("transcript" to "TRANSCRIPT_S")
        val constants = mapOf(
            "NOTE_SYSTEM" to Prompts.NOTE_SYSTEM,
            "NOTE_PROMPT_VERBATIM" to Prompts.NOTE_PROMPT_VERBATIM,
            "REMINDER_MESSAGE_SYSTEM" to Prompts.REMINDER_MESSAGE_SYSTEM,
            "REMINDER_MESSAGE_PROMPT" to Prompts.REMINDER_MESSAGE_PROMPT,
        )
        val rows = golden("prompts.json")
        assertEquals(constants.keys, rows.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        for (row in rows) {
            val o = row.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val template = constants.getValue(name)
            val fields = o.getValue("fields").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(o.getValue("template").jsonPrimitive.content, template, "$name template")
            assertEquals(
                o.getValue("rendered").jsonPrimitive.content,
                Prompts.render(template, sentinels.filterKeys { it in fields }),
                "$name rendered",
            )
        }
    }

    @Test
    fun noteReplyParserMatchesItsGoldenRows() {
        val rows = golden("parse_note_reply.json")
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val o = row.jsonObject
            val input = o.getValue("in").jsonPrimitive.content
            val result = NoteReplyParser.parseNoteReply(input)
            assertEquals(o.getValue("fallback").jsonPrimitive.content.toBoolean(), result.fallback, "fallback for $input")
            assertEquals(o.getValue("parsed"), result.parsed, "parsed for $input")
        }
    }

    @Test
    fun escapeJsonStringsMatchesItsGoldenRows() {
        for (row in golden("escape_json_strings.json")) {
            val o = row.jsonObject
            val input = o.getValue("in").jsonPrimitive.content
            assertEquals(o.getValue("out").jsonPrimitive.content, LlmJson.escapeJsonStrings(input), "escapeJsonStrings($input)")
        }
    }
}
