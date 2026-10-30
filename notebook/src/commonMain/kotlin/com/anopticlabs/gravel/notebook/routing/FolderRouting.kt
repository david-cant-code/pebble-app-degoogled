package com.anopticlabs.gravel.notebook.routing

import com.anopticlabs.gravel.notebook.search.BodyTokenizer

/** The folder a note lands in when nothing routes it; always offered as a destination. */
const val UNCLASSIFIED_FOLDER = "unclassified"

/** Weight of a folder tag that matches a note tag, against 1 for a match in the note's prose. */
const val FOLDER_ROUTE_TAG_WEIGHT = 3

/** Folders the router never picks as a default destination. */
internal val NOTE_WRITE_EXCLUDED_FOLDERS = setOf("archive")

/**
 * Ranks [folders] by how well their routing tags match a note. Each folder tag stem counts
 * once, at its strongest source: [FOLDER_ROUTE_TAG_WEIGHT] when it is also a note tag stem, 1
 * when it appears in the title or content. Ties keep the input order, and [UNCLASSIFIED_FOLDER]
 * is appended with score 0 when [folders] omits it. Folder names must match the keys of [folderTags].
 */
fun scoreFoldersForNote(
    noteTags: List<String>,
    noteTitle: String,
    noteContent: String,
    folders: List<String>,
    folderTags: Map<String, List<String>>,
): List<Pair<String, Int>> {
    val noteTagStems = noteTags.flatMapTo(HashSet()) { BodyTokenizer.tokenizeQuery(it) }
    val proseStems = HashSet<String>().apply {
        addAll(BodyTokenizer.tokenizeQuery(noteTitle))
        addAll(BodyTokenizer.tokenizeQuery(noteContent))
    }

    val ranked = folders.map { folder ->
        val folderTagStems = (folderTags[folder] ?: emptyList())
            .flatMapTo(HashSet()) { BodyTokenizer.tokenizeQuery(it) }
        var score = 0
        for (stem in folderTagStems) {
            score += when {
                stem in noteTagStems -> FOLDER_ROUTE_TAG_WEIGHT
                stem in proseStems -> 1
                else -> 0
            }
        }
        folder to score
    }.sortedByDescending { it.second }

    return if (ranked.any { it.first == UNCLASSIFIED_FOLDER }) ranked
    else ranked + (UNCLASSIFIED_FOLDER to 0)
}

/** The first positively scored folder not in [excluded], else [UNCLASSIFIED_FOLDER]. */
internal fun pickRoutedFolder(ranked: List<Pair<String, Int>>, excluded: Set<String>): String =
    ranked.firstOrNull { it.second > 0 && it.first !in excluded }?.first ?: UNCLASSIFIED_FOLDER

/** The default destination for a note: [scoreFoldersForNote] then [pickRoutedFolder] with [NOTE_WRITE_EXCLUDED_FOLDERS]. */
fun routedFolder(
    noteTags: List<String>,
    noteTitle: String,
    noteContent: String,
    folders: List<String>,
    folderTags: Map<String, List<String>>,
): String = pickRoutedFolder(
    scoreFoldersForNote(noteTags, noteTitle, noteContent, folders, folderTags),
    NOTE_WRITE_EXCLUDED_FOLDERS,
)
