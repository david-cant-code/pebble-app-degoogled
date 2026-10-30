package com.anopticlabs.gravel.notebook.search

import com.anopticlabs.gravel.notebook.notes.NoteSanitizers

/** Ceiling on one note's stemmed tag set; far above the five tags a note can carry, so only tampered input reaches it. */
const val MAX_STEMMED_TAGS_PER_NOTE = 64

/** How many raw entries [computeStemmedTags] looks at; with the per-tag length cap this bounds the stemming work. */
const val MAX_SCANNED_TAG_ENTRIES = 256

/** The search-index stems of display-form [tags]; null in, null out. */
fun computeStemmedTags(tags: List<String>?): Set<String>? {
    if (tags == null) return null
    val out = LinkedHashSet<String>()
    for (tag in tags.take(MAX_SCANNED_TAG_ENTRIES)) {
        for (stem in BodyTokenizer.tokenizeQuery(tag.take(NoteSanitizers.MAX_TAG_CHARS))) {
            if (out.size >= MAX_STEMMED_TAGS_PER_NOTE) return out
            out.add(stem)
        }
    }
    return out
}
