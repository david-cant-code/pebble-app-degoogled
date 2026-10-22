package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.notes.FolderNames
import com.anopticlabs.gravel.notebook.notes.NotePaths
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One note's entry in the `notes_meta` index, enough to list it without reading the file. */
@Serializable
data class NoteMetadata(
    val folder: String,
    val name: String,
    val hash: String,
    val modifiedAtMillis: Long,
    val title: String? = null,
    val date: String? = null,
)

/**
 * One note's search stems, kept apart from the index so a listing never reads them; [hash] is the
 * content hash they were computed from, so a reader can tell stale stems from current ones.
 */
@Serializable
data class NoteStems(
    val hash: String,
    val tokens: Set<String>,
    /** Stems of the front matter tags; null for a note without a `tags:` field. */
    val tags: Set<String>? = null,
)

/** One of the 16 stems files, holding the notes whose id starts with its hex digit. Persisted format: a new shape gets a new [version]. */
@Serializable
internal class NoteStemsFile(
    @SerialName("v") val version: Int,
    val notes: Map<String, NoteStems>,
) {
    fun requireWellFormed(shard: Char) {
        require(version == VERSION) { "stems version $version" }
        require(notes.keys.all { isValidNoteId(it) && it[0] == shard }) { "stems entry with an invalid id for shard $shard" }
    }

    companion object {
        const val VERSION = 1
    }
}

/** The `notes_meta` file: the index entries by note id. Persisted format: a new shape gets a new [version]. */
@Serializable
internal class NoteIndexFile(
    @SerialName("v") val version: Int,
    val notes: Map<String, NoteMetadata>,
) {
    fun requireWellFormed() {
        require(version == VERSION) { "index version $version" }
        require(notes.all { (id, m) -> isValidNoteId(id) && FolderNames.isValid(m.folder) && NotePaths.isValidName(m.name) }) {
            "index entry with an invalid id, folder or name"
        }
    }

    companion object {
        const val VERSION = 1
    }
}

/**
 * A note file's plaintext: the note and everything the index lists it by, so the index can be
 * rebuilt from the note files alone. Persisted format: a new shape gets a new [version].
 */
@Serializable
internal data class NoteRecord(
    @SerialName("v") val version: Int,
    val folder: String,
    val name: String,
    val title: String? = null,
    val date: String? = null,
    val modifiedAtMillis: Long,
    val content: String,
) {
    fun requireWellFormed() {
        require(version == VERSION) { "note record version $version" }
        require(FolderNames.isValid(folder) && NotePaths.isValidName(name)) { "note record with an invalid folder or name" }
    }

    companion object {
        const val VERSION = 1
    }
}

/** A note as a list row; [date] is the front matter date when the index has one. */
data class NoteItem(
    val id: String,
    val folder: String,
    val name: String,
    val title: String,
    val date: String?,
    val modifiedAtMillis: Long,
)

data class FolderItem(val name: String, val count: Int)

/** The JSON dialect of every Notebook store file: nulls and defaults omitted, unknown keys ignored. */
val NotebookJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    // Decoded text is note content and titles; a decoding failure's message must not quote it.
    exceptionsWithDebugInfo = false
}
