package com.anopticlabs.gravel.notebook.llm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmJsonTest {

    private fun parse(s: String): JsonObject? = LlmJson.extract(s) as? JsonObject

    // cleanThinkAndFences

    @Test
    fun gemmaThoughtChannelIsStripped() {
        val raw = "<|channel>thought\nI should answer with JSON.\n<channel|>{\"title\": \"Ship it\"}"
        assertEquals("""{"title": "Ship it"}""", LlmJson.cleanThinkAndFences(raw))
        assertEquals("beforeafter", LlmJson.cleanThinkAndFences("before<|channel>thought\nline one\nline two\n<channel|>after"))
    }

    @Test
    fun leadingThinkBlockIsStrippedThroughItsClose() {
        assertEquals("""{"a": 1}""", LlmJson.cleanThinkAndFences("<think>reasoning</think>{\"a\": 1}"))
    }

    @Test
    fun fencesAndStrayBackticksAreStripped() {
        assertEquals("""{"a": 1}""", LlmJson.cleanThinkAndFences("```json\n{\"a\": 1}\n```"))
        assertEquals("""{"a": 1}""", LlmJson.cleanThinkAndFences("```\n{\"a\": 1}\n```"))
        assertEquals("""{"a": 1}""", LlmJson.cleanThinkAndFences("""{"a": 1}"""))
    }

    // strictness

    @Test
    fun trailingCommaSingleQuotesAndNonJsonFail() {
        assertNull(parse("""{"a":1,}"""), "trailing comma")
        assertNull(LlmJson.extract("""{'a': 1}"""), "single quotes")
        assertNull(LlmJson.extract(""), "empty")
        assertNull(LlmJson.extract("plain prose"), "prose")
    }

    @Test
    fun proseAroundTheObjectIsTolerated() {
        assertEquals(JsonPrimitive("T"), parse("Sure! {\"title\": \"T\"} hope this helps")?.get("title"))
    }

    @Test
    fun rawNewlineInsideAStringIsRepairedOnRetry() {
        assertEquals(JsonPrimitive("line1\nline2"), parse("{\"content\": \"line1\nline2\"}")?.get("content"))
    }

    // non-finite literals

    @Test
    fun nanFieldValueParsesAsNull() {
        val map = parse("""{"title":"T","content":"C","score":NaN}""")
        assertNotNull(map)
        assertEquals(JsonPrimitive("T"), map["title"])
        assertTrue(map.containsKey("score"))
        assertEquals(JsonNull, map["score"])
    }

    @Test
    fun infinityLiteralsParse() {
        assertNotNull(parse("""{"a":Infinity}"""))
        assertNotNull(parse("""{"a":-Infinity}"""))
    }

    @Test
    fun nanInsideAStringIsPreserved() {
        assertEquals(JsonPrimitive("the value is NaN here"), parse("""{"note":"the value is NaN here"}""")?.get("note"))
        assertEquals(JsonPrimitive("NaNa"), parse("""{"a":"NaNa"}""")?.get("a"))
    }

    @Test
    fun bareNonFiniteAtTopLevelIsANonObjectSuccess() {
        assertEquals(JsonNull, LlmJson.extract("NaN"))
    }

    // size cap

    @Test
    fun aReplyOverTheCapIsNotParsed() {
        val huge = "{\"title\": \"" + "x".repeat(LlmJson.MAX_REPLY_CHARS) + "\"}"
        assertNull(LlmJson.extract(huge))
        assertTrue(NoteReplyParser.parseNoteReply(huge).fallback)
    }
}
