package com.anopticlabs.gravel.notebook.search

/**
 * Turns note text and search terms into one vocabulary of lowercase Porter stems, so a stored
 * note and a later query, tag or folder tag compare by set membership.
 */
object BodyTokenizer {

    /** Body stems of a whole note, front matter excluded, at most [MAX_TOKENS_PER_NOTE]. */
    fun tokenizeNote(rawContent: String): Set<String> =
        tokenizeText(stripFrontmatter(rawContent), capTokens = MAX_TOKENS_PER_NOTE)

    /** Stems of a short text (a query, title, tag or reminder message); no cap. */
    fun tokenizeQuery(query: String): Set<String> =
        tokenizeText(query, capTokens = Int.MAX_VALUE)

    private const val MAX_TOKENS_PER_NOTE = 500

    // Checked before stemming to bound its per-token work; oversized tokens are skipped, not truncated.
    private const val MAX_STEM_INPUT_CHARS = 64

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "of", "in", "on", "at", "to",
        "for", "is", "are", "was", "were", "be", "been", "with", "by",
        "from", "as", "that", "this", "it", "its", "i", "you", "we", "they",
        "my", "our",
    )

    /** Anything but a letter, digit or apostrophe separates tokens. */
    private val NON_TOKEN_RE = Regex("[^\\p{L}\\p{N}']+")

    // Lenient on purpose: any later "---" closes the block. Stored note tokens were computed
    // this way, so the strict Frontmatter parser must not replace it.
    private fun stripFrontmatter(content: String): String {
        if (!content.startsWith("---")) return content
        val end = content.indexOf("---", 3)
        return if (end >= 0) content.substring(end + 3).trimStart() else content
    }

    private fun tokenizeText(text: String, capTokens: Int): Set<String> {
        if (text.isBlank()) return emptySet()
        val cleaned = NON_TOKEN_RE.replace(text.lowercase(), " ")
        val out = LinkedHashSet<String>()
        for (raw in cleaned.split(' ')) {
            if (out.size >= capTokens) break
            val trimmed = raw.trim('\'')
            if (trimmed.length < 2) continue
            if (trimmed.length > MAX_STEM_INPUT_CHARS) continue
            if (trimmed in STOPWORDS) continue
            val stemmed = PorterStemmer.stem(trimmed)
            if (stemmed.length < 2) continue
            out.add(stemmed)
        }
        return out
    }
}
