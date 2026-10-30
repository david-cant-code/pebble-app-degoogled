package com.anopticlabs.gravel.notebook.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads the model's reply to the note prompt. */
object NoteReplyParser {

    /** [parsed] is the reply's JSON, or the fallback title and tags when [fallback] is set. */
    data class Result(val parsed: JsonElement, val fallback: Boolean)

    const val FALLBACK_TITLE = "Voice Note"

    /** The reply's JSON, or a fallback object with the fallback title and tags. */
    fun parseNoteReply(raw: String): Result {
        val cleaned = LlmJson.cleanThinkAndFences(raw)
        LlmJson.extract(cleaned)?.let { return Result(it, fallback = false) }
        val fallback = JsonObject(
            mapOf(
                "title" to JsonPrimitive(FALLBACK_TITLE),
                "tags" to JsonArray(listOf(JsonPrimitive("voice"))),
            ),
        )
        return Result(fallback, fallback = true)
    }
}
