package com.anopticlabs.gravel.notebook.notes

/** How much of a note the screens lay out at once, and how long a note the editor takes. */
object NoteDisplay {
    /** The longest body the editor holds; a longer note opens read-only. */
    const val MAX_EDIT_CHARS = 32 * 1024

    const val CHUNK_CHARS = 4 * 1024

    /**
     * [text] in pieces of at most [CHUNK_CHARS], for a list to lay out only those on screen: each
     * ends after its last line break when it has one, else after its last space, never between the
     * halves of a surrogate pair. The pieces joined are [text].
     */
    fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (text.length - start > CHUNK_CHARS) {
            val limit = start + CHUNK_CHARS
            var end = text.lastIndexOf('\n', limit - 1).takeIf { it >= start }?.plus(1)
                ?: text.lastIndexOf(' ', limit - 1).takeIf { it >= start }?.plus(1)
                ?: limit
            if (end == limit && text[end - 1].isHighSurrogate()) end--
            out += text.substring(start, end)
            start = end
        }
        if (start < text.length || out.isEmpty()) out += text.substring(start)
        return out
    }
}
