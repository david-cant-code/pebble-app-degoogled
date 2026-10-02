package com.anopticlabs.gravel.notebook.notes

/**
 * Reads the `tags:` field back out of a note's front matter, in display form: the written
 * double-quoted inline array, plus the unquoted, single-quoted, block-list and bare-scalar
 * forms a hand-edited note may carry. Malformed input yields an empty list, never an exception.
 */
object FrontmatterTags {

    /**
     * Null when [content] has no front matter or no `tags:` line; an empty list for `tags: []`
     * or a malformed array; otherwise the tags in order.
     */
    fun parseFrontmatterTags(content: String): List<String>? {
        if (!content.startsWith("---")) return null
        // The close is searched from 4, past the opening's newline, so an empty block cannot
        // produce a substring with end before start.
        val end = content.indexOf("\n---", 4)
        if (end < 0) return null
        val lines = content.substring(4, end).split("\n")
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trimStart()
            if (!trimmed.startsWith("tags:")) {
                i++
                continue
            }
            val afterColon = trimmed.removePrefix("tags:").trimStart()

            if (afterColon.startsWith("[")) {
                val close = findUnquotedClose(afterColon, closeBracket = ']')
                if (close < 0) return emptyList()
                return parseInlineArray(afterColon.substring(1, close))
            }

            if (afterColon.isEmpty()) {
                val items = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val itemLine = lines[j].trimStart()
                    if (!itemLine.startsWith("- ") && itemLine != "-") break
                    val unquoted = unquote(itemLine.removePrefix("-").trim())
                    if (unquoted.isNotEmpty()) items.add(unquoted)
                    j++
                }
                return items
            }

            return listOf(unquote(afterColon))
        }
        return null
    }

    private fun parseInlineArray(inside: String): List<String> {
        if (inside.isBlank()) return emptyList()
        return splitTopLevelCommas(inside)
            .map { unquote(it.trim()) }
            .filter { it.isNotEmpty() }
    }

    /** Splits on commas outside double quotes; a backslash-escaped quote does not end a value. */
    private fun splitTopLevelCommas(s: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var escaped = false
        for (c in s) {
            when {
                escaped -> { cur.append(c); escaped = false }
                c == '\\' -> { cur.append(c); escaped = true }
                c == '"' -> { cur.append(c); inQuotes = !inQuotes }
                c == ',' && !inQuotes -> { parts.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        parts.add(cur.toString())
        return parts
    }

    /** Index of the first [closeBracket] outside double quotes after the opening bracket at 0, or -1. */
    private fun findUnquotedClose(s: String, closeBracket: Char): Int {
        var inQuotes = false
        var escaped = false
        for (i in 1 until s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inQuotes = !inQuotes
                c == closeBracket && !inQuotes -> return i
            }
        }
        return -1
    }

    private fun unquote(s: String): String {
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length - 1)
            }
        }
        return s
    }
}
