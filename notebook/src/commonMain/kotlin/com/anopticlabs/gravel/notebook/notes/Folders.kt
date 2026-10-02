package com.anopticlabs.gravel.notebook.notes

import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER

const val ARCHIVE_FOLDER = "archive"

/** The folder list a fresh Notebook starts with; the archive is not in it. */
val DEFAULT_FOLDERS: List<String> = listOf(UNCLASSIFIED_FOLDER)

/** Folders whose notes stay out of the search index. */
val INDEXED_FOLDER_BLOCKLIST: Set<String> = setOf(ARCHIVE_FOLDER)

/** Folders the user cannot create, delete or tag. */
val RESERVED_FOLDERS: Set<String> = setOf(UNCLASSIFIED_FOLDER, ARCHIVE_FOLDER)

/** User-created folder names: lowercase ASCII letters, digits, `_` and `-`, starting with a letter or digit. */
object FolderNames {
    const val MAX_CHARS = 64

    private val VALID = Regex("^[a-z0-9][a-z0-9_-]*$")

    fun normalize(raw: String): String = raw.trim().lowercase().replace(' ', '-')

    fun isValid(name: String): Boolean = name.length <= MAX_CHARS && VALID.matches(name)
}

/** The folders a capture's note may be routed to: every listed folder but the unindexed ones. */
fun captureRouteFolders(folders: List<String>): List<String> = folders.filter { it !in INDEXED_FOLDER_BLOCKLIST }

/** Where a routed note is written: [routed] when it was offered and is a valid name, else [UNCLASSIFIED_FOLDER]. */
fun noteWriteFolder(routed: String, offered: List<String>): String =
    if (routed in offered && routed !in INDEXED_FOLDER_BLOCKLIST && FolderNames.isValid(routed)) routed else UNCLASSIFIED_FOLDER

/**
 * Whether to point out that routing is off: some user folders are listed ([folders] minus
 * [RESERVED_FOLDERS]) and none of them has a tag, so every routed note goes to [UNCLASSIFIED_FOLDER].
 */
fun routingHasNoTags(folders: List<String>, tags: Map<String, List<String>>): Boolean {
    val userFolders = folders.filter { it !in RESERVED_FOLDERS }
    return userFolders.isNotEmpty() && userFolders.none { tags[it].orEmpty().isNotEmpty() }
}
