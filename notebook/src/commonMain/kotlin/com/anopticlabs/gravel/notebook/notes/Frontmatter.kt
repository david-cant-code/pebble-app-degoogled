package com.anopticlabs.gravel.notebook.notes

/**
 * The strict parser for a note's leading YAML front matter block: the opening is a line that
 * is exactly `---`, the close the next `---` that is itself a whole line, so a `---` inside a
 * quoted value never ends the block. The editor splits and rejoins notes through it on save.
 */
object Frontmatter {

    private const val CLOSE = "\n---"

    /** The block including both delimiters, or "" when [content] has no well-formed block. */
    fun extract(content: String): String {
        val close = closeIndex(content) ?: return ""
        return content.substring(0, close + CLOSE.length)
    }

    /** The body after the block with leading whitespace removed, or [content] itself without a block. */
    fun strip(content: String): String {
        val close = closeIndex(content) ?: return content
        return content.substring(close + CLOSE.length).trimStart()
    }

    /**
     * The body as the editor shows it: the text after the block less the blank line [recombine]
     * writes there (or a single line break), [content] itself without a block. [recombine] of it
     * gives back a note the Notebook wrote unchanged.
     */
    fun editableBody(content: String): String {
        val block = extract(content)
        if (block.isEmpty()) return content
        val rest = content.substring(block.length)
        val separator = listOf("\r\n\r\n", "\n\n", "\r\n", "\n").firstOrNull { rest.startsWith(it) }.orEmpty()
        return rest.substring(separator.length)
    }

    /** [newBody] under [content]'s block, or [newBody] alone when there is no block. */
    fun recombine(content: String, newBody: String): String {
        val fm = extract(content)
        return if (fm.isEmpty()) newBody else "$fm\n\n$newBody"
    }

    /**
     * [content] with the block's top-level `title:` entry, and the indented lines continuing it
     * (blank lines between them included), replaced by [title] as [NoteMarkdown.yamlSafe] writes
     * it; added as the block's first entry when there is none. [content] as it is without a block.
     */
    fun withTitle(content: String, title: String): String {
        val block = extract(content)
        if (block.isEmpty()) return content
        val lines = block.split('\n').toMutableList()
        val entry = "title: ${NoteMarkdown.yamlSafe(title)}"
        val last = lines.lastIndex
        val at = (1 until last).firstOrNull { lines[it].startsWith("title:") }
        if (at == null) {
            lines.add(1, entry + if (lines[0].endsWith('\r')) "\r" else "")
        } else {
            val cr = if (lines[at].endsWith('\r')) "\r" else ""
            fun indented(line: String) = line.startsWith(' ') || line.startsWith('\t')
            var end = at + 1
            while (end < last) {
                if (indented(lines[end])) {
                    end++
                    continue
                }
                var next = end
                while (next < last && lines[next].trimEnd('\r').isEmpty()) next++
                if (next > end && next < last && indented(lines[next])) end = next else break
            }
            lines.subList(at, end).clear()
            lines.add(at, entry + cr)
        }
        return lines.joinToString("\n") + content.substring(block.length)
    }

    /** Index of the closing delimiter's leading newline, or null without a well-formed block. */
    private fun closeIndex(content: String): Int? {
        if (!content.startsWith("---") || content.length < 4) return null
        val afterOpen = content[3]
        if (afterOpen != '\n' && afterOpen != '\r') return null
        // Starts at the opening's own newline so an empty block ("---\n---") closes at once.
        var from = 3
        while (true) {
            val nl = content.indexOf(CLOSE, from)
            if (nl < 0) return null
            val afterDashes = nl + CLOSE.length
            if (afterDashes >= content.length) return nl
            val next = content[afterDashes]
            if (next == '\n' || next == '\r') return nl
            from = nl + 1
        }
    }
}
