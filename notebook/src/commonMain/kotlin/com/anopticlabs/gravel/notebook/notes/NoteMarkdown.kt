package com.anopticlabs.gravel.notebook.notes

/** Renders a note's on-disk markdown: a front matter block, a blank line, the body. */
object NoteMarkdown {

    /**
     * [value] as a single-line, double-quoted YAML scalar: control characters removed, edges
     * trimmed, backslashes escaped before quotes.
     */
    fun yamlSafe(value: String): String {
        val escaped = value.stripControlChars().trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$escaped\""
    }

    fun buildMarkdown(title: String, tags: List<String>, content: String, date: String): String {
        val tagsStr = tags.joinToString(", ") { yamlSafe(it) }
        return "---\ntitle: ${yamlSafe(title)}\ndate: $date\ntags: [$tagsStr]\ntype: note\n---\n\n$content\n"
    }
}
