package com.anopticlabs.gravel.notebook.notes

/**
 * The tags a user typed for a folder, comma separated: control characters removed, trimmed,
 * lowercased, capped at [NoteSanitizers.MAX_TAG_CHARS] each, first occurrence kept, at most
 * [NoteSanitizers.MAX_TAGS_PER_FOLDER].
 */
fun parseTagsCsv(csv: String): List<String> {
    if (csv.isBlank()) return emptyList()
    val seen = LinkedHashSet<String>()
    for (raw in csv.split(',')) {
        val cleaned = raw.stripControlChars().trim().lowercase().take(NoteSanitizers.MAX_TAG_CHARS)
        if (cleaned.isEmpty()) continue
        seen.add(cleaned)
        if (seen.size >= NoteSanitizers.MAX_TAGS_PER_FOLDER) break
    }
    return seen.toList()
}

/** A new folder's tags: its own [name] first, so notes tagged with the name route to it, then [userTagsCsv]. */
fun seedFolderTags(name: String, userTagsCsv: String): List<String> =
    parseTagsCsv(listOf(name, userTagsCsv).filter { it.isNotBlank() }.joinToString(","))
