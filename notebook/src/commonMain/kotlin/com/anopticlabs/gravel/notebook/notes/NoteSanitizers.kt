package com.anopticlabs.gravel.notebook.notes

import com.anopticlabs.gravel.notebook.llm.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Cleans the tag and folder fields read out of a model's JSON reply. Each method gates on the
 * [JsonElement] shape it expects and returns an empty result for anything else.
 */
object NoteSanitizers {
    const val MAX_TAGS_PER_NOTE = 5
    const val MAX_TAG_CHARS = 50
    const val MAX_TAGS_PER_FOLDER = 8
    const val MAX_FOLDER_TAGS_ENTRIES = 100
    const val MAX_FOLDER_NAME_CHARS = 200

    /** Control characters removed and the length capped; surrounding whitespace is kept. */
    fun sanitizeFolderName(name: String): String =
        name.stripControlChars().take(MAX_FOLDER_NAME_CHARS)

    /** String entries cleaned, lowercased and capped at [MAX_TAG_CHARS]; empties, repeats and non-strings dropped; at most [cap] kept. */
    fun normalizeTagList(raw: JsonElement?, cap: Int): List<String> {
        if (raw !is JsonArray) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (item in raw) {
            val text = item.stringOrNull() ?: continue
            var cleaned = text.stripControlChars().trim().lowercase()
            if (cleaned.isEmpty()) continue
            cleaned = cleaned.take(MAX_TAG_CHARS)
            if (!seen.add(cleaned)) continue
            out.add(cleaned)
            if (out.size >= cap) break
        }
        return out
    }

    fun sanitizeTags(raw: JsonElement?): List<String> = normalizeTagList(raw, MAX_TAGS_PER_NOTE)

    /** Folder names through [sanitizeFolderName], tag lists through [normalizeTagList]; empty results dropped. */
    fun sanitizeFolderTags(raw: JsonElement?): Map<String, List<String>> {
        if (raw !is JsonObject) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for ((key, value) in raw) {
            val cleanKey = sanitizeFolderName(key)
            if (cleanKey.isEmpty()) continue
            val cleanValue = normalizeTagList(value, MAX_TAGS_PER_FOLDER)
            if (cleanValue.isEmpty()) continue
            out[cleanKey] = cleanValue
            if (out.size >= MAX_FOLDER_TAGS_ENTRIES) break
        }
        return out
    }
}
