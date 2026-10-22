package com.anopticlabs.gravel.notebook.llm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Pulls the JSON out of a model reply that may wrap it in a reasoning block, fences or prose.
 * Parsing is strict, except that bare `NaN` and `Infinity` literals are read as `null`.
 */
internal object LlmJson {

    /** A reply longer than this is not a classification and is not parsed. */
    const val MAX_REPLY_CHARS = 256 * 1024

    private const val THINK_CLOSE = "</think>"
    private val GEMMA_THOUGHT_CHANNEL = Regex("<\\|channel>thought.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL)
    private val FENCE = Regex("```(?:json)?")
    private val BRACES = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL)

    /** Everything through the first `</think>`, any Gemma thought channel, and fences removed; then trimmed. */
    fun cleanThinkAndFences(raw: String): String {
        var r = raw
        val idx = r.indexOf(THINK_CLOSE)
        if (idx >= 0) r = r.substring(idx + THINK_CLOSE.length)
        r = GEMMA_THOUGHT_CHANNEL.replace(r, "")
        return FENCE.replace(r, "").trim().trim('`').trim()
    }

    /** Literal newlines, carriage returns and tabs inside string values escaped; nothing else touched. */
    fun escapeJsonStrings(s: String): String {
        val sb = StringBuilder(s.length)
        var inString = false
        var escapeNext = false
        for (ch in s) {
            when {
                escapeNext -> { sb.append(ch); escapeNext = false }
                ch == '\\' && inString -> { sb.append(ch); escapeNext = true }
                ch == '"' -> { sb.append(ch); inString = !inString }
                inString && ch == '\n' -> sb.append("\\n")
                inString && ch == '\r' -> sb.append("\\r")
                inString && ch == '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** The whole text, else the first `{` to the last `}`, else that span with its strings escaped; null when nothing parses. */
    fun extract(cleaned: String): JsonElement? {
        if (cleaned.length > MAX_REPLY_CHARS) return null
        strictParse(cleaned)?.let { return it }
        val jsonStr = BRACES.find(cleaned)?.value ?: return null
        strictParse(jsonStr)?.let { return it }
        return strictParse(escapeJsonStrings(jsonStr))
    }

    private fun strictParse(s: String): JsonElement? = try {
        Json.parseToJsonElement(normalizeNonFinite(s))
    } catch (e: SerializationException) {
        null
    }

    /** `NaN`, `Infinity` and `-Infinity` outside string values become `null`. */
    private fun normalizeNonFinite(s: String): String {
        if (!s.contains("NaN") && !s.contains("Infinity")) return s
        val sb = StringBuilder(s.length)
        var inString = false
        var escapeNext = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                escapeNext -> { sb.append(ch); escapeNext = false; i++ }
                inString && ch == '\\' -> { sb.append(ch); escapeNext = true; i++ }
                ch == '"' -> { sb.append(ch); inString = !inString; i++ }
                inString -> { sb.append(ch); i++ }
                else -> {
                    val consumed = matchNonFinite(s, i)
                    if (consumed > 0) { sb.append("null"); i += consumed } else { sb.append(ch); i++ }
                }
            }
        }
        return sb.toString()
    }

    private fun matchNonFinite(s: String, i: Int): Int {
        fun tokenAt(tok: String, start: Int): Boolean {
            if (!s.startsWith(tok, start)) return false
            val end = start + tok.length
            return end >= s.length || !(s[end].isLetterOrDigit() || s[end] == '_')
        }
        return when {
            tokenAt("NaN", i) -> 3
            tokenAt("Infinity", i) -> 8
            s[i] == '-' && tokenAt("Infinity", i + 1) -> 9
            else -> 0
        }
    }
}
