package com.anopticlabs.gravel.notebook.notes

/** Unicode compatibility decomposition (NFKD) of [text]; the platform supplies the tables. */
internal expect fun nfkd(text: String): String

/**
 * Derives a note's filename stem from its title: NFKD-folded to ASCII, punctuation dropped,
 * lowercased, the first [maxWords] whitespace-separated words joined with "-". Underscores and
 * dashes count as word characters; a title with no ASCII word characters slugs to "".
 */
object NoteSlug {

    fun slugify(text: String, maxWords: Int = 4): String {
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (ch in nfkd(text)) {
            when {
                ch.code >= 0x80 -> Unit
                ch.isWhitespace() -> {
                    if (word.isNotEmpty()) words.add(word.toString())
                    word.clear()
                }
                ch.isLetterOrDigit() || ch == '_' || ch == '-' -> word.append(ch.lowercaseChar())
                else -> Unit
            }
        }
        if (word.isNotEmpty()) words.add(word.toString())
        return words.take(maxWords).joinToString("-")
    }
}
