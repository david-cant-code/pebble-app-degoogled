package com.anopticlabs.gravel.notebook.notes

import com.anopticlabs.gravel.notebook.llm.NoteReplyParser
import com.anopticlabs.gravel.notebook.padTwo
import com.anopticlabs.gravel.notebook.llm.stringOrNull
import com.anopticlabs.gravel.notebook.routing.routedFolder
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import kotlinx.serialization.json.JsonObject

/** A note ready to be written: where it goes, what the file is called, and its markdown; [date] is its front matter date. */
data class BuiltNote(
    val title: String,
    val tags: List<String>,
    val folder: String,
    val filename: String,
    val markdown: String,
    val transcript: String,
    val classificationFailed: Boolean,
    val date: String,
)

/** Turns a model reply and the transcript it was made from into a [BuiltNote]. */
object NoteBuilder {

    /**
     * The reply gives the title and tags; the body is always the [transcript]. A reply that is not a
     * JSON object is a failed classification with the fallback title; one with no JSON at all also
     * gets the fallback tags.
     */
    fun buildNote(
        rawReply: String,
        transcript: String,
        folders: List<String>,
        folderTags: Map<String, List<String>>,
        now: LocalDateTime,
    ): BuiltNote {
        val parsed = NoteReplyParser.parseNoteReply(rawReply)
        val obj = parsed.parsed as? JsonObject
        val classificationFailed = parsed.fallback || obj == null
        val title = obj?.get("title")?.stringOrNull() ?: NoteReplyParser.FALLBACK_TITLE
        val tags = NoteSanitizers.sanitizeTags(obj?.get("tags"))
        val folder = routedFolder(tags, title, transcript, folders, folderTags)
        val date = now.isoDate()
        return BuiltNote(
            title = title,
            tags = tags,
            folder = folder,
            filename = noteFilename(title, now),
            markdown = NoteMarkdown.buildMarkdown(title, tags, transcript, date),
            transcript = transcript,
            classificationFailed = classificationFailed,
            date = date,
        )
    }

    /** A note typed in the app: no model, no tags, [body] as written, into [folder]. */
    fun typedNote(title: String, body: String, folder: String, now: LocalDateTime): BuiltNote {
        val date = now.isoDate()
        return BuiltNote(
            title = title,
            tags = emptyList(),
            folder = folder,
            filename = noteFilename(title, now),
            markdown = NoteMarkdown.buildMarkdown(title, emptyList(), body, date),
            transcript = body,
            classificationFailed = false,
            date = date,
        )
    }

    /**
     * Characters a slug keeps: the name, its `_MM.dd.yy_HHmm.md` stamp and the store's largest `-999`
     * suffix stay within [NotePaths.MAX_NAME_CHARS].
     */
    val MAX_SLUG_CHARS = NotePaths.MAX_NAME_CHARS - "_00.00.00_0000.md".length - "-999".length

    /** `slug_MM.dd.yy_HHmm.md`; the minute stamp keeps same-title notes apart across captures. */
    fun noteFilename(stem: String, now: LocalDateTime): String =
        "${NoteSlug.slugify(stem).take(MAX_SLUG_CHARS)}_${now.stampDate()}_${now.stampTime()}.md"

    /**
     * The name a note called [filename] takes when retitled [title]: the new slug ahead of the old
     * [noteFilename] stamp (the stamp alone when the title has no slug), or `slug.md` for a name
     * without one; null for a name without a stamp and a title without a slug, so the note keeps
     * [filename].
     */
    fun renamedFilename(filename: String, title: String): String? {
        val slug = NoteSlug.slugify(title).take(MAX_SLUG_CHARS)
        val match = FILENAME_STAMP.matchEntire(filename)
        val renamed = when {
            match != null -> "${slug}_${match.groupValues[2]}_${match.groupValues[3]}.md"
            slug.isNotEmpty() -> "$slug.md"
            else -> return null
        }
        return renamed.takeIf { NotePaths.isValidName(it) }
    }

    /** [filename] with `-n` after its slug, ahead of a [noteFilename] stamp; before `.md` for any other name. */
    fun suffixedFilename(filename: String, n: Int): String {
        val match = FILENAME_STAMP.matchEntire(filename)
        if (match != null) {
            val (slug, datePart, timePart) = match.destructured
            return "$slug-${n}_${datePart}_$timePart.md"
        }
        return if (filename.endsWith(".md")) "${filename.removeSuffix(".md")}-$n.md" else "$filename-$n"
    }

    private val FILENAME_STAMP = Regex("^(.*)_(\\d{2}\\.\\d{2}\\.\\d{2})_(\\d{4})\\.md$")

    private fun LocalDateTime.isoDate(): String = "$year-${month.number.padTwo()}-${day.padTwo()}"

    private fun LocalDateTime.stampDate(): String = "${month.number.padTwo()}.${day.padTwo()}.${(year % 100).padTwo()}"

    private fun LocalDateTime.stampTime(): String = "${hour.padTwo()}${minute.padTwo()}"
}
