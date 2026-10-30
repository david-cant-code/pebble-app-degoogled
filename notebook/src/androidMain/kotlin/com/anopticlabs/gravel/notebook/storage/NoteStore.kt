package com.anopticlabs.gravel.notebook.storage

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.notes.ARCHIVE_FOLDER
import com.anopticlabs.gravel.notebook.notes.DEFAULT_FOLDERS
import com.anopticlabs.gravel.notebook.notes.FolderNames
import com.anopticlabs.gravel.notebook.notes.FrontmatterTags
import com.anopticlabs.gravel.notebook.notes.INDEXED_FOLDER_BLOCKLIST
import com.anopticlabs.gravel.notebook.notes.NoteBuilder
import com.anopticlabs.gravel.notebook.notes.NotePaths
import com.anopticlabs.gravel.notebook.notes.RESERVED_FOLDERS
import com.anopticlabs.gravel.notebook.notes.seedFolderTags
import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER
import com.anopticlabs.gravel.notebook.search.BodyTokenizer
import com.anopticlabs.gravel.notebook.search.computeStemmedTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

sealed interface CreateFolderResult {
    data class Created(val name: String, val tags: List<String>) : CreateFolderResult
    data object InvalidName : CreateFolderResult
    data class Reserved(val name: String) : CreateFolderResult
    data class AlreadyExists(val name: String) : CreateFolderResult

    /** Listed, but writing its seeded tags failed; the folder takes routed notes only once tagged. */
    data class CreatedWithoutTags(val name: String) : CreateFolderResult
}

/** A note [NoteStore.createListedNote] wrote, and the folder it went to. */
data class CreatedNote(val id: String, val folder: String)

/** A batch by id: done, not where the batch expected (unlisted, or not in the archive), or failed in the store. */
data class BatchResult(val done: Set<String>, val notThere: Set<String>, val failed: Set<String>)

sealed interface EditNoteResult {
    /** Saved under [name], which follows a new title. */
    data class Saved(val name: String) : EditNoteResult
    data object NotListed : EditNoteResult

    /** The note's content is no longer what the editor opened. */
    data object Changed : EditNoteResult
    data object Archived : EditNoteResult
}

/** A note as a restore hands it to the store, under the id it had in the archive; the fields are already bounded by the caller. */
class RestoreNoteRequest(
    val id: String,
    val folder: String,
    val name: String,
    val content: String,
    val title: String?,
    val date: String?,
    val modifiedAtMillis: Long,
)

/** [skipped] counts requests the index had no room for, that found no free name, or whose id names an unlisted file. */
data class RestoreNotesResult(val written: Int, val kept: Int, val skipped: Int)

sealed interface DeleteFolderResult {
    data class Deleted(val movedNotes: Int) : DeleteFolderResult
    data object Reserved : DeleteFolderResult

    /** Ids of notes under the folder the store failed to move; the folder is listed again. */
    data class NotesRemain(val ids: List<String>) : DeleteFolderResult
}

/**
 * What [NoteStore.reconcile] did: whether it rebuilt the index, and how many note files it listed,
 * deleted as copies, quarantined, or left in place unlisted (they open but do not parse, no free
 * name is left for them, or the index has no room for them).
 */
data class ReconcileResult(val rebuilt: Boolean = false, val listed: Int = 0, val dropped: Int = 0, val setAside: Int = 0, val leftInPlace: Int = 0)

/** What [NoteStore.repairStems] did: entries recomputed, and entries dropped for notes that need none. */
data class StemsRepair(val recomputed: Int, val dropped: Int)

/**
 * The Notebook's notes, their index (`notes_meta`), the folder list and the folder tags, as
 * encrypted files under [root]. A note is the file `notes/<id>` under a random id, holding a
 * [NoteRecord]; the index lists the same fields by id and is held in memory, process-wide per
 * root, once read or written. Each note's search stems live in one of 16 stems files, picked by
 * the id's first hex digit, updated after the index. Mutators run on [Dispatchers.IO]; the
 * non-suspend reads block on file and Keystore I/O, so callers run them on an I/O dispatcher.
 * Every read-modify-write holds its file's [NotebookLocks] mutex and reads strictly before it
 * writes, so a read that fails changes nothing on disk. A note file is written before the index
 * entry that lists it. No read quarantines the index, folder list or folder tags: a corrupt folder
 * list or tags file fails every mutator until a backup restore or a reset replaces it; a corrupt
 * index fails them from a process start until [reconcile] rebuilds it, and with the index in
 * memory the next index write replaces the file.
 */
class NoteStore internal constructor(
    root: File,
    crypto: Cryptographer,
    private val newId: () -> String = { randomNoteId() },
    private val maxMetaBytes: Long = MAX_META_BYTES,
    private val clock: () -> Long,
) {

    constructor(root: File, crypto: Cryptographer) : this(root, crypto, clock = System::currentTimeMillis)

    private val files = EncryptedFiles(root, crypto, clock)
    private val notesDir = File(files.root, NOTES_DIR)
    private val metaFile = File(files.root, META_FILE)
    private val stemsDir = File(files.root, STEMS_DIR)
    private val cacheKey = files.root.canonicalPath
    private val foldersFile = File(files.root, FOLDERS_FILE)
    private val folderTagsFile = File(files.root, FOLDER_TAGS_FILE)

    private val metaLock = NotebookLocks.lockFor(META_FILE)
    private val foldersLock = NotebookLocks.lockFor(FOLDERS_FILE)
    private val folderTagsLock = NotebookLocks.lockFor(FOLDER_TAGS_FILE)

    private val changes = revisions.getOrPut(cacheKey) { MutableStateFlow(0L) }

    /** Counts the index, folder list and folder tags writes that landed, process-wide per root, for screens to re-read on. */
    val revision: StateFlow<Long> = changes.asStateFlow()

    /** Deletes stale files from the staging directory every store under the root writes through. */
    suspend fun sweepStaging() = io { files.sweepStaging() }

    /** Deletes set-aside files past their time from the quarantine directory every store under the root shares. */
    suspend fun sweepQuarantine() = io { files.sweepQuarantine() }

    // Folders

    /** The listed folders, valid names only; the defaults until the list is written. */
    fun readFolders(strict: Boolean = false): List<String> {
        val json = files.readText(foldersFile, strict, quarantineCorrupt = false) ?: return DEFAULT_FOLDERS
        return parsePersisted(strict, FOLDERS_FILE, { DEFAULT_FOLDERS }) {
            NotebookJson.decodeFromString<List<String>>(json)
                .filter { FolderNames.isValid(it) }
                .distinct()
                .takeIf { it.isNotEmpty() }
        }
    }

    /** Replaces the folder list with [transform] of the current one; returns the new list. */
    suspend fun updateFolders(transform: (List<String>) -> List<String>): List<String> = io {
        foldersLock.withLock {
            val current = readFolders(strict = true)
            val updated = transform(current)
            if (updated != current) {
                files.writeText(foldersFile, NotebookJson.encodeToString(updated))
                changed()
            }
            updated
        }
    }

    suspend fun ensureFolder(name: String): List<String> = updateFolders { if (name in it) it else it + name }

    /** Puts [UNCLASSIFIED_FOLDER] first in the list when it is missing. */
    suspend fun ensureDefaultFolders(): List<String> = updateFolders { folders ->
        if (UNCLASSIFIED_FOLDER in folders) folders else listOf(UNCLASSIFIED_FOLDER) + folders
    }

    /** Every folder with its note count, listed or holding an indexed note; [UNCLASSIFIED_FOLDER] first, [ARCHIVE_FOLDER] last. */
    fun folderItems(strict: Boolean = false): List<FolderItem> = folderItems(readFolders(strict), readMetadata(strict))

    /** [folders] and every folder holding a note in [index], with counts, ordered as [folderItems]. */
    fun folderItems(folders: List<String>, index: Map<String, NoteMetadata>): List<FolderItem> {
        val names = folders.toMutableList()
        if (UNCLASSIFIED_FOLDER !in names) names.add(0, UNCLASSIFIED_FOLDER)
        val counts = index.values.groupingBy { it.folder }.eachCount()
        for (folder in counts.keys) if (folder !in names) names.add(folder)
        names.remove(ARCHIVE_FOLDER)
        names.add(ARCHIVE_FOLDER)
        return names.map { name -> FolderItem(name, counts[name] ?: 0) }
    }

    /** Adds a user folder with its own name seeded as its first routing tag; a failure after listing it leaves it without tags. */
    suspend fun createFolder(rawName: String, userTagsCsv: String = ""): CreateFolderResult {
        val name = FolderNames.normalize(rawName)
        if (!FolderNames.isValid(name)) return CreateFolderResult.InvalidName
        if (name in RESERVED_FOLDERS) return CreateFolderResult.Reserved(name)
        return toTheEnd {
            var exists = false
            updateFolders { folders ->
                if (name in folders) {
                    exists = true
                    folders
                } else {
                    folders + name
                }
            }
            if (exists) return@toTheEnd CreateFolderResult.AlreadyExists(name)
            val tags = seedFolderTags(name, userTagsCsv)
            try {
                updateFolderTags { it + (name to tags) }
            } catch (e: Exception) {
                if (!isStorageFailure(e)) throw e
                logger.w { "folder listed without its tags (${e::class.simpleName})" }
                return@toTheEnd CreateFolderResult.CreatedWithoutTags(name)
            }
            CreateFolderResult.Created(name, tags)
        }
    }

    /**
     * Unlists the folder, so [createListedNote] no longer writes to it, moves its notes to
     * [UNCLASSIFIED_FOLDER] under one hold of the index lock, then drops its tags. When a move
     * fails, or throws, the folder is listed again where it was and keeps its tags; a relist that
     * fails, or a process end before the move finishes, leaves it unlisted with the notes not yet
     * moved still under it, and deleting it again moves them. A process end after the move leaves its
     * tags.
     */
    suspend fun deleteFolder(name: String): DeleteFolderResult {
        if (name in RESERVED_FOLDERS) return DeleteFolderResult.Reserved
        return toTheEnd {
            var position = -1
            updateFolders { folders ->
                position = folders.indexOf(name)
                folders - name
            }
            val result = try {
                moveFolderNotes(name, UNCLASSIFIED_FOLDER)
            } catch (e: Throwable) {
                try {
                    relist(name, position)
                } catch (relistFailure: Exception) {
                    e.addSuppressed(relistFailure)
                }
                throw e
            }
            if (result.failed.isNotEmpty()) {
                relist(name, position)
                return@toTheEnd DeleteFolderResult.NotesRemain(result.failed.toList())
            }
            updateFolderTags { it - name }
            DeleteFolderResult.Deleted(result.done.size)
        }
    }

    // A folder's list entry, its notes and its tags are separate writes; a caller canceled between
    // them (a screen's scope) would leave them disagreeing.
    private suspend fun <T> toTheEnd(block: suspend CoroutineScope.() -> T): T = withContext(NonCancellable, block)

    private suspend fun relist(name: String, position: Int) {
        if (position < 0) return
        updateFolders { folders -> if (name in folders) folders else folders.toMutableList().apply { add(minOf(position, size), name) } }
    }

    /**
     * Sets a damaged folder list aside for the default folders plus every folder an indexed note is
     * in; returns whether it did. A failure to read either file propagates with the list in place.
     */
    suspend fun startFoldersFreshIfDamaged(): Boolean = io {
        foldersLock.withLock {
            val damage = damageOf { readFolders(strict = true) } ?: return@withLock false
            val holding = readMetadata(strict = true).values.map { it.folder }.filter { it != ARCHIVE_FOLDER }.distinct().sorted()
            keepAside(foldersFile, damage)
            files.writeText(foldersFile, NotebookJson.encodeToString((DEFAULT_FOLDERS + holding).distinct()))
            changed()
            true
        }
    }

    // Folder tags

    fun readFolderTags(strict: Boolean = false): Map<String, List<String>> {
        val json = files.readText(folderTagsFile, strict, quarantineCorrupt = false) ?: return emptyMap()
        return parsePersisted(strict, FOLDER_TAGS_FILE, { emptyMap() }) {
            NotebookJson.decodeFromString<Map<String, List<String>>>(json)
        }
    }

    /** Replaces the folder tags with [transform] of the current map; returns the new map. */
    suspend fun updateFolderTags(
        transform: (Map<String, List<String>>) -> Map<String, List<String>>,
    ): Map<String, List<String>> = io {
        folderTagsLock.withLock {
            val current = readFolderTags(strict = true)
            val updated = transform(current)
            if (updated != current) writeFolderTags(updated)
            updated
        }
    }

    /**
     * Drops the tags of folders that are no longer listed. The folder list is read under the tags
     * lock, so a folder being created cannot lose the tags it is about to get.
     */
    suspend fun pruneOrphanFolderTags(): Map<String, List<String>> = io {
        folderTagsLock.withLock {
            val folders = readFolders(strict = true).toSet()
            val current = readFolderTags(strict = true)
            val pruned = current.filterKeys { it in folders }
            if (pruned.size != current.size) writeFolderTags(pruned)
            pruned
        }
    }

    /** Sets damaged folder tags aside for each listed user folder's seed tags, as a new folder gets; returns whether it did. A failure to read either file propagates with the tags in place. */
    suspend fun startFolderTagsFreshIfDamaged(): Boolean = io {
        folderTagsLock.withLock {
            val damage = damageOf { readFolderTags(strict = true) } ?: return@withLock false
            val folders = readFolders(strict = true)
            keepAside(folderTagsFile, damage)
            writeFolderTags(folders.filter { it !in RESERVED_FOLDERS }.associateWith { seedFolderTags(it, "") })
            true
        }
    }

    private fun writeFolderTags(tags: Map<String, List<String>>) {
        files.writeText(folderTagsFile, NotebookJson.encodeToString(tags))
        changed()
    }

    // Notes

    /**
     * Writes a new note under [folder] as [filename] or the first `-n` suffixed name (within the
     * name rule) no note in the folder has; when the note holding a name on the way has this exact
     * content it is reused, rewritten when its file is missing or does not open. Returns the note's id.
     */
    suspend fun createNote(
        folder: String,
        filename: String,
        content: String,
        title: String? = null,
        date: String? = null,
        modifiedAtMillis: Long = clock(),
    ): String = io {
        require(FolderNames.isValid(folder)) { "not a folder name: $folder" }
        require(NotePaths.isValidName(filename)) { "not a note name: $filename" }
        metaLock.withLock { createLocked(folder, filename, content, title, date, modifiedAtMillis) }
    }

    /**
     * Creates a note as [createNote] does, in [folder] while the folder list holds it and in
     * [UNCLASSIFIED_FOLDER] otherwise; the list is read under the index lock, which [deleteFolder]
     * moves a folder's notes under after unlisting it.
     */
    suspend fun createListedNote(
        folder: String,
        filename: String,
        content: String,
        title: String? = null,
        date: String? = null,
        modifiedAtMillis: Long = clock(),
    ): CreatedNote = io {
        require(FolderNames.isValid(folder)) { "not a folder name: $folder" }
        require(NotePaths.isValidName(filename)) { "not a note name: $filename" }
        metaLock.withLock {
            val target = if (folder == UNCLASSIFIED_FOLDER || folder in readFolders(strict = true)) folder else UNCLASSIFIED_FOLDER
            CreatedNote(createLocked(target, filename, content, title, date, modifiedAtMillis), target)
        }
    }

    private fun createLocked(folder: String, filename: String, content: String, title: String?, date: String?, modifiedAtMillis: Long): String {
        val index = readMetadata(strict = true).toMutableMap()
        val hash = sha256(content)
        val taken = namesIn(index, folder)
        for (name in candidateNames(filename)) {
            val holder = taken[name]
            if (holder == null) {
                val id = freshId(index)
                val record = NoteRecord(NoteRecord.VERSION, folder, name, title.orBlankNull(), date.orBlankNull(), modifiedAtMillis, content)
                writeRecord(id, record)
                index[id] = metadataFor(record)
                writeIndex(index)
                updateStems(mapOf(id to stemsFor(record)))
                return id
            }
            val entry = index.getValue(holder)
            if (entry.hash == hash) {
                val record = recordOf(entry, content)
                if (!opens(holder)) writeRecord(holder, record)
                updateStems(mapOf(holder to stemsFor(record)))
                return holder
            }
        }
        throw IllegalStateException("no free name in $folder")
    }

    /** Replaces the content, title and date of note [id], keeping its folder and name; false when no such note is listed. */
    suspend fun updateNote(
        id: String,
        content: String,
        title: String? = null,
        date: String? = null,
        modifiedAtMillis: Long = clock(),
    ): Boolean = io {
        requireNoteId(id)
        metaLock.withLock {
            val index = readMetadata(strict = true).toMutableMap()
            val entry = index[id] ?: return@withLock false
            val record = NoteRecord(NoteRecord.VERSION, entry.folder, entry.name, title.orBlankNull(), date.orBlankNull(), modifiedAtMillis, content)
            writeRecord(id, record)
            index[id] = metadataFor(record)
            writeIndex(index)
            updateStems(mapOf(id to stemsFor(record)))
            true
        }
    }

    /**
     * Replaces note [id]'s content with [content], when the index still lists it with
     * [expectedHash] and outside [ARCHIVE_FOLDER]; the date and folder stay. A [title] other than
     * the listed one replaces it and renames the note ([NoteBuilder.renamedFilename], `-n` suffixed
     * when another note in the folder holds the name); null keeps both. The edit's time is
     * [modifiedAtMillis], or the listed time plus one when that is later, capped at
     * [MAX_STORED_INSTANT_MILLIS]; [restoreNotes] compares by it.
     */
    suspend fun editNote(
        id: String,
        expectedHash: String,
        content: String,
        title: String?,
        modifiedAtMillis: Long = clock(),
    ): EditNoteResult = io {
        requireNoteId(id)
        require(title == null || title.isNotBlank()) { "a note edit's title is blank" }
        metaLock.withLock {
            val index = readMetadata(strict = true).toMutableMap()
            val entry = index[id] ?: return@withLock EditNoteResult.NotListed
            if (entry.hash != expectedHash) return@withLock EditNoteResult.Changed
            if (entry.folder == ARCHIVE_FOLDER) return@withLock EditNoteResult.Archived
            val name = if (title == null || title == entry.title) {
                entry.name
            } else {
                val taken = namesIn(index, entry.folder)
                NoteBuilder.renamedFilename(entry.name, title)
                    ?.let { wanted -> candidateNames(wanted).firstOrNull { taken[it] == null || taken[it] == id } }
                    ?: entry.name
            }
            val at = minOf(maxOf(modifiedAtMillis, entry.modifiedAtMillis + 1), MAX_STORED_INSTANT_MILLIS)
            val record = NoteRecord(NoteRecord.VERSION, entry.folder, name, title ?: entry.title, entry.date, at, content)
            writeRecord(id, record)
            index[id] = metadataFor(record)
            writeIndex(index)
            updateStems(mapOf(id to stemsFor(record)))
            EditNoteResult.Saved(name)
        }
    }

    /**
     * The content of note [id], or null when its file is absent or could not be read this time (see
     * [EncryptedFiles.read]); with [quarantineCorrupt] false a file that fails to open stays in place.
     */
    fun readNote(id: String, strict: Boolean = false, quarantineCorrupt: Boolean = true): String? = readRecord(id, strict, quarantineCorrupt)?.content

    /** Deletes the file, then the index entry, so an interruption leaves at most an entry without its file. */
    suspend fun deleteNote(id: String): Unit = io {
        requireNoteId(id)
        metaLock.withLock {
            val index = readMetadata(strict = true).toMutableMap()
            deleteFile(id)
            if (index.remove(id) != null) writeIndex(index)
            updateStems(mapOf(id to null))
        }
    }

    /**
     * Moves note [id] to [toFolder] under its name, or the first `-n` suffixed name free there, and
     * re-indexes it for the destination; the file is rewritten before the index. A listed note whose
     * file is absent, or corrupt and so quarantined by the read, moves as an entry alone. A move to
     * the folder the note is listed in rewrites a file that says otherwise. Returns the name the
     * note holds there, or null when it is not listed.
     */
    suspend fun moveNote(id: String, toFolder: String): String? = io {
        requireNoteId(id)
        require(FolderNames.isValid(toFolder)) { "not a folder name: $toFolder" }
        metaLock.withLock {
            val index = readMetadata(strict = true).toMutableMap()
            val before = index[id]
            val stems = HashMap<String, NoteStems?>()
            val name = moveLocked(index, id, toFolder, stems) ?: return@withLock null
            if (index[id] != before) {
                writeIndex(index)
                updateStems(stems)
            }
            name
        }
    }

    /** Moves each of [ids] as [moveNote] does, under one hold of the index lock (see [batchLocked]). */
    suspend fun moveNotes(ids: Collection<String>, toFolder: String): BatchResult = io {
        ids.forEach(::requireNoteId)
        require(FolderNames.isValid(toFolder)) { "not a folder name: $toFolder" }
        metaLock.withLock { batchLocked(ids) { index, id, stems -> moveLocked(index, id, toFolder, stems) != null } }
    }

    /** Deletes each of [ids] that the index lists in [ARCHIVE_FOLDER], file first, under one hold of the index lock (see [batchLocked]). */
    suspend fun deleteArchivedNotes(ids: Collection<String>): BatchResult = io {
        ids.forEach(::requireNoteId)
        metaLock.withLock {
            batchLocked(ids) { index, id, stems ->
                if (index[id]?.folder != ARCHIVE_FOLDER) return@batchLocked false
                deleteFile(id)
                index.remove(id)
                stems[id] = null
                true
            }
        }
    }

    private suspend fun moveFolderNotes(from: String, to: String): BatchResult = io {
        metaLock.withLock {
            val ids = readMetadata(strict = true).filterValues { it.folder == from }.keys.toList()
            batchLocked(ids) { index, id, stems -> moveLocked(index, id, to, stems) != null }
        }
    }

    /**
     * Applies [each] to every id with the index lock held by the caller, writing the index and the
     * stems after each [WRITE_CHUNK] changed entries and at the end. A store failure on one id
     * leaves its entry as it was, is counted in [BatchResult.failed], and the rest go on; an index
     * write that fails throws.
     */
    private fun batchLocked(
        ids: Collection<String>,
        each: (MutableMap<String, NoteMetadata>, String, MutableMap<String, NoteStems?>) -> Boolean,
    ): BatchResult {
        val index = readMetadata(strict = true).toMutableMap()
        val stems = HashMap<String, NoteStems?>()
        val done = LinkedHashSet<String>()
        val notThere = LinkedHashSet<String>()
        val failed = LinkedHashSet<String>()
        var dirty = 0
        for (id in ids) {
            val before = index[id]
            try {
                if (each(index, id, stems)) done += id else notThere += id
            } catch (e: Exception) {
                if (!isStorageFailure(e)) throw e
                failed += id
                continue
            }
            if (index[id] != before && ++dirty == WRITE_CHUNK) {
                writeIndex(index)
                updateStems(stems)
                stems.clear()
                dirty = 0
            }
        }
        if (dirty > 0) {
            writeIndex(index)
            updateStems(stems)
        }
        return BatchResult(done, notThere, failed)
    }

    /**
     * [moveNote] on [index] with the index lock held by the caller, who writes the index; the
     * note's file is rewritten here, before that, and its stems change goes into [stems]. Null when
     * [id] is not listed.
     */
    private fun moveLocked(index: MutableMap<String, NoteMetadata>, id: String, toFolder: String, stems: MutableMap<String, NoteStems?>): String? {
        val entry = index[id] ?: return null
        val record = readRecord(id, strict = true)
        if (entry.folder == toFolder) {
            if (record != null && (record.folder != entry.folder || record.name != entry.name)) {
                writeRecord(id, record.copy(folder = entry.folder, name = entry.name))
            }
            return entry.name
        }
        val taken = namesIn(index, toFolder)
        val name = candidateNames(entry.name).firstOrNull { it !in taken }
            ?: throw IllegalStateException("no free name in $toFolder")
        if (record == null) {
            index[id] = entry.copy(folder = toFolder, name = name)
            stems[id] = null
            return name
        }
        val moved = record.copy(folder = toFolder, name = name)
        writeRecord(id, moved)
        index[id] = metadataFor(moved)
        stems[id] = stemsFor(moved)
        return name
    }

    /**
     * Merges notes from a backup under the index lock, matched by id, the index and the stems
     * written once per [WRITE_CHUNK] notes: a listed note with identical content is kept, rewritten
     * when its file is missing or does not open, a listed note modified later is kept while
     * its file opens with its content, any other listed note takes the request's content, title,
     * date and time in its own folder under its own name, and an
     * unlisted id becomes a note in the request's folder under its name or the first `-n` suffixed
     * name free there. An unlisted id whose file exists is skipped, so a restore overwrites no file
     * the index does not list. A request the store cannot spell throws [IllegalArgumentException]
     * before anything is written.
     */
    suspend fun restoreNotes(requests: List<RestoreNoteRequest>): RestoreNotesResult = io {
        for (r in requests) {
            require(isValidNoteId(r.id) && FolderNames.isValid(r.folder) && NotePaths.isValidName(r.name)) { "not a restorable note: ${r.id}" }
        }
        metaLock.withLock {
            val index = readMetadata(strict = true).toMutableMap()
            var indexBytes = indexBytes(index)
            val names = HashMap<String, MutableSet<String>>()
            for (m in index.values) names.getOrPut(m.folder) { HashSet() } += m.name
            val stems = HashMap<String, NoteStems?>()
            var written = 0
            var kept = 0
            var skipped = 0
            var dirty = 0
            for (request in requests) {
                val id = request.id
                val existing = index[id]
                if (existing != null && existing.hash == sha256(request.content)) {
                    val record = recordOf(existing, request.content)
                    if (!opens(id)) writeRecord(id, record)
                    stems[id] = stemsFor(record)
                    kept++
                    continue
                }
                if (existing != null && existing.modifiedAtMillis > request.modifiedAtMillis && holdsContent(id, existing.hash)) {
                    kept++
                    continue
                }
                val name = when {
                    existing != null -> existing.name
                    noteFile(id).exists() -> null
                    else -> names[request.folder].orEmpty().let { taken -> candidateNames(request.name).firstOrNull { it !in taken } }
                }
                if (name == null) {
                    skipped++
                    continue
                }
                val folder = existing?.folder ?: request.folder
                val record = NoteRecord(NoteRecord.VERSION, folder, name, request.title.orBlankNull(), request.date.orBlankNull(), request.modifiedAtMillis, request.content)
                val entry = metadataFor(record)
                val delta = entryBytes(id, entry) - (existing?.let { entryBytes(id, it) } ?: 0L)
                if (indexBytes + delta + NotebookEnvelope.MIN_LEN > maxMetaBytes) {
                    skipped++
                    continue
                }
                writeRecord(id, record)
                index[id] = entry
                names.getOrPut(folder) { HashSet() } += name
                stems[id] = stemsFor(record)
                indexBytes += delta
                written++
                if (++dirty == WRITE_CHUNK) {
                    writeIndex(index)
                    updateStems(stems)
                    stems.clear()
                    dirty = 0
                }
            }
            if (dirty > 0) writeIndex(index)
            updateStems(stems)
            RestoreNotesResult(written, kept, skipped)
        }
    }

    /** The notes of [folder], newest first; the title falls back to the name without `.md`. */
    fun listNoteItems(folder: String, strict: Boolean = false): List<NoteItem> =
        readMetadata(strict).entries
            .filter { it.value.folder == folder }
            .sortedByDescending { it.value.modifiedAtMillis }
            .map { (id, m) ->
                NoteItem(
                    id = id,
                    folder = m.folder,
                    name = m.name,
                    title = m.title?.takeIf { it.isNotBlank() } ?: m.name.removeSuffix(".md"),
                    date = m.date?.takeIf { it.isNotBlank() },
                    modifiedAtMillis = m.modifiedAtMillis,
                )
            }

    fun folderCount(folder: String): Int = readMetadata().values.count { it.folder == folder }

    // Index

    /**
     * The index entries by note id, from memory once a read has succeeded or a write has landed; an
     * entry with an invalid id, folder or name is a format failure. A read that degrades to empty is
     * not remembered. An absent index while note files exist (a rebuild is due) is a transient
     * failure on a strict read and empty otherwise.
     */
    fun readMetadata(strict: Boolean = false): Map<String, NoteMetadata> {
        indexCache[cacheKey]?.let { return it }
        // Checked before the read: an index written meanwhile replaces the remembered empty one.
        if (!metaFile.exists()) {
            val notesOnDisk = try {
                noteIdsOnDisk().isNotEmpty()
            } catch (e: TransientCryptoException) {
                if (strict) throw e
                return emptyMap()
            }
            if (!notesOnDisk) return indexCache.putIfAbsent(cacheKey, emptyMap()) ?: emptyMap()
            if (strict) throw TransientCryptoException("the note index is missing", IOException("note files exist without an index"))
            return emptyMap()
        }
        val json = files.readText(metaFile, strict, quarantineCorrupt = false, maxBytes = maxMetaBytes) ?: return emptyMap()
        var loaded: Map<String, NoteMetadata>? = null
        val result = parsePersisted(strict, META_FILE, { emptyMap() }) {
            NotebookJson.decodeFromString<NoteIndexFile>(json).also { it.requireWellFormed() }.notes.also { loaded = it }
        }
        val notes = loaded ?: return result
        return indexCache.putIfAbsent(cacheKey, notes) ?: notes
    }

    /** Every note's search stems by id, from the 16 stems files; a file that cannot be read this time reads as empty unless [strict]. */
    fun readStems(strict: Boolean = false): Map<String, NoteStems> {
        val out = HashMap<String, NoteStems>()
        for (shard in SHARDS) out += readStemsShard(shard, strict, quarantineCorrupt = false)
        return out
    }

    /**
     * Recomputes, under the index lock, the stems of every listed note in an indexed folder whose
     * entry is missing or carries another hash than the index, and drops entries no such note
     * needs. A transient or key failure propagates; a note whose file is absent or corrupt is
     * skipped. For a reader of the stems to run first.
     */
    suspend fun repairStems(): StemsRepair = io {
        metaLock.withLock {
            val index = readMetadata(strict = true)
            var recomputed = 0
            var dropped = 0
            for (shard in SHARDS) {
                val current = readStemsShard(shard, strict = true, quarantineCorrupt = true)
                val wanted = index.filter { (id, m) -> id[0] == shard && m.folder !in INDEXED_FOLDER_BLOCKLIST }
                val next = HashMap(current.filterKeys { it in wanted })
                dropped += current.size - next.size
                for ((id, m) in wanted) {
                    if (next[id]?.hash == m.hash) continue
                    val record = readRecord(id, strict = true) ?: continue
                    next[id] = stemsFor(record) ?: continue
                    recomputed++
                }
                if (next != current) writeStemsShard(shard, next)
            }
            StemsRepair(recomputed, dropped)
        }
    }

    /** Test-only: forgets the index held in memory, as a new process would start without it. */
    internal fun forgetCachedIndexForTest() {
        indexCache.remove(cacheKey)
    }

    /** Forgets the index held in memory once the files under the root were moved away, and tells screens to re-read. */
    fun forgetCachedIndex() {
        indexCache.remove(cacheKey)
        changed()
    }

    /**
     * Brings the index in line with the note files, under the index lock. An index that fails to
     * open or to parse is rebuilt from the note files: every note is read first, and the old index
     * is moved to quarantine only once the rebuilt one is ready to write; an absent index while
     * note files exist is rebuilt the same way. Otherwise each note file the index does not list is
     * read: one whose folder, name and content match a listed note whose file opens with that
     * content is deleted as a leftover copy, any other is listed (under a free suffixed name if its
     * own is taken). A note file that fails to open is quarantined; one that opens but does not
     * parse, finds no free name, or would take the index past its cap, is left in place; all stay
     * unlisted. A transient or key
     * failure propagates, with the index file as it was.
     */
    suspend fun reconcile(): ReconcileResult = io {
        metaLock.withLock {
            val onDisk = noteIdsOnDisk()
            var bad: Exception? = null
            val index = try {
                if (metaFile.exists()) readMetadata(strict = true) else null
            } catch (e: CorruptFileException) {
                bad = e
                null
            } catch (e: PersistedFormatException) {
                bad = e
                null
            }
            when {
                index != null -> place(index.toMutableMap(), onDisk.filterNot { it in index }, badIndex = null, rebuild = false)
                onDisk.isEmpty() && bad == null -> ReconcileResult()
                else -> place(mutableMapOf(), onDisk, badIndex = bad, rebuild = true)
            }
        }
    }

    /** Lists [unlisted] note files in [index] as [reconcile] describes, then writes the index if it changed. */
    private fun place(index: MutableMap<String, NoteMetadata>, unlisted: List<String>, badIndex: Exception?, rebuild: Boolean): ReconcileResult {
        val read = unlisted.map { id -> id to readRecordToPlace(id) }
        val records = read.mapNotNull { (id, r) -> (r as? PlaceRead.Record)?.let { id to it.record } }
            .sortedWith(compareBy({ it.second.modifiedAtMillis }, { it.first }))
        val stems = HashMap<String, NoteStems?>()
        var listed = 0
        var dropped = 0
        var unplaced = 0
        var bytes = indexBytes(index)
        for ((id, record) in records) {
            val hash = sha256(record.content)
            val copyOf = index.entries.firstOrNull { (other, m) ->
                m.folder == record.folder && m.name == record.name && m.hash == hash && holdsContent(other, hash)
            }
            if (copyOf != null) {
                deleteFile(id)
                stems[id] = null
                dropped++
                continue
            }
            val taken = namesIn(index, record.folder)
            val name = candidateNames(record.name).firstOrNull { it !in taken }
            if (name == null) {
                logger.w { "note $id has no free name left; left in place" }
                unplaced++
                continue
            }
            val placedEntry = metadataFor(record.copy(name = name))
            if (bytes + entryBytes(id, placedEntry) + NotebookEnvelope.MIN_LEN > maxMetaBytes) {
                logger.w { "note $id does not fit the index; left in place" }
                unplaced++
                continue
            }
            bytes += entryBytes(id, placedEntry)
            val placed = if (name == record.name) record else record.copy(name = name).also { writeRecord(id, it) }
            index[id] = metadataFor(placed)
            stems[id] = stemsFor(placed)
            listed++
        }
        if (badIndex != null) setIndexAside(badIndex)
        if (rebuild || listed > 0) writeIndex(index)
        updateStems(stems)
        return ReconcileResult(
            rebuilt = rebuild,
            listed = listed,
            dropped = dropped,
            setAside = read.count { it.second == PlaceRead.Quarantined },
            leftInPlace = read.count { it.second == PlaceRead.Unparseable } + unplaced,
        )
    }

    private sealed interface PlaceRead {
        class Record(val record: NoteRecord) : PlaceRead
        data object Quarantined : PlaceRead
        data object Unparseable : PlaceRead
    }

    /** The record of [id] for [reconcile]: a transient or key failure propagates. */
    private fun readRecordToPlace(id: String): PlaceRead {
        val json = files.readText(noteFile(id), strict = true, quarantineCorrupt = true) ?: return PlaceRead.Quarantined
        return try {
            PlaceRead.Record(parseRecord(json, strict = true)!!)
        } catch (e: PersistedFormatException) {
            logger.w { "note $id opens but does not parse; left in place" }
            PlaceRead.Unparseable
        }
    }

    /** Whether the file of [id] opens and holds content with [hash]; a transient or key failure propagates. */
    private fun holdsContent(id: String, hash: String): Boolean = openedRecord(id)?.let { sha256(it.content) == hash } == true

    /** Whether the file of [id] is there, opens and reads as a note, whatever its content; a transient or key failure propagates. */
    private fun opens(id: String): Boolean = openedRecord(id) != null

    /** The record in the file of [id], null when the file is missing, fails to open or does not parse; the file stays in place. */
    private fun openedRecord(id: String): NoteRecord? {
        val json = try {
            files.readText(noteFile(id), strict = true, quarantineCorrupt = false) ?: return null
        } catch (e: CorruptFileException) {
            return null
        }
        return try {
            parseRecord(json, strict = true)
        } catch (e: PersistedFormatException) {
            null
        }
    }

    /** The record of note [id] as [readNote] reads it, for a backup. */
    internal fun readNoteRecord(id: String, strict: Boolean): NoteRecord? = readRecord(id, strict)

    /** The ids of the note files on disk, listed or not. */
    internal fun noteFileIds(): List<String> = noteIdsOnDisk()

    /** Each note file on disk as `id:size:modified`, sorted: what changes when a file is written, whatever the index says. */
    internal fun noteFileStamps(): List<String> = noteIdsOnDisk().map { id -> noteFile(id).let { "$id:${it.length()}:${it.lastModified()}" } }

    private fun readRecord(id: String, strict: Boolean, quarantineCorrupt: Boolean = true): NoteRecord? {
        requireNoteId(id)
        val json = files.readText(noteFile(id), strict, quarantineCorrupt) ?: return null
        return parseRecord(json, strict)
    }

    private fun parseRecord(json: String, strict: Boolean): NoteRecord? =
        parsePersisted<NoteRecord?>(strict, "a note", { null }) {
            NotebookJson.decodeFromString<NoteRecord>(json).also { it.requireWellFormed() }
        }

    /** Refuses a record its own reader would refuse before writing it. */
    private fun writeRecord(id: String, record: NoteRecord) {
        record.requireWellFormed()
        files.writeText(noteFile(id), NotebookJson.encodeToString(record))
    }

    private fun writeIndex(index: Map<String, NoteMetadata>) {
        val file = NoteIndexFile(NoteIndexFile.VERSION, index).also { it.requireWellFormed() }
        val plaintext = NotebookJson.encodeToString(file).encodeToByteArray()
        require(plaintext.size + NotebookEnvelope.MIN_LEN <= maxMetaBytes) { "$META_FILE would be over its $maxMetaBytes byte cap" }
        files.write(metaFile, plaintext)
        indexCache[cacheKey] = index.toMap()
        changed()
    }

    private fun changed() = changes.update { it + 1 }

    /** The corrupt or format failure [read] throws, null when it reads; a transient or key failure propagates. */
    private inline fun damageOf(read: () -> Unit): Exception? = try {
        read()
        null
    } catch (e: CorruptFileException) {
        e
    } catch (e: PersistedFormatException) {
        e
    }

    /** Copies a damaged [file] to quarantine before the caller replaces it in one move. */
    private fun keepAside(file: File, cause: Exception) {
        files.quarantine(file, cause, "is damaged", keep = true) ?: throw TransientCryptoException("${file.name} could not be set aside", cause)
    }

    private fun setIndexAside(cause: Exception) {
        indexCache.remove(cacheKey)
        files.quarantine(metaFile, cause)
    }

    /**
     * Applies [changes] (null removes an entry) to the stems files they fall in, dropping entries of
     * notes the index no longer lists. A stems file that cannot be read or written this time is left
     * as it is (see [repairStems]), and a corrupt or unparseable one is quarantined and started
     * again; neither fails the note write that called this.
     */
    private fun updateStems(changes: Map<String, NoteStems?>) {
        val listed = readMetadata(strict = true)
        for ((shard, shardChanges) in changes.entries.groupBy { it.key[0] }) {
            try {
                val current = readStemsShard(shard, strict = true, quarantineCorrupt = true)
                val next = HashMap(current)
                for ((id, stems) in shardChanges) if (stems == null) next.remove(id) else next[id] = stems
                next.keys.retainAll(listed.keys)
                if (next != current) writeStemsShard(shard, next)
            } catch (e: Exception) {
                if (!isTransientCryptoFailure(e) && !isKeyUnavailableFailure(e)) throw e
                logger.w { "stems file $shard not updated this time (${e::class.simpleName})" }
            }
        }
    }

    private fun readStemsShard(shard: Char, strict: Boolean, quarantineCorrupt: Boolean): Map<String, NoteStems> {
        val file = stemsFile(shard)
        val json = files.readText(file, strict, quarantineCorrupt, maxBytes = MAX_META_BYTES) ?: return emptyMap()
        return try {
            parsePersisted(strict, "$STEMS_DIR/$shard", { emptyMap() }) {
                NotebookJson.decodeFromString<NoteStemsFile>(json).also { it.requireWellFormed(shard) }.notes
            }
        } catch (e: PersistedFormatException) {
            if (!quarantineCorrupt) throw e
            files.quarantine(file, e)
            emptyMap()
        }
    }

    private fun writeStemsShard(shard: Char, notes: Map<String, NoteStems>) {
        val file = NoteStemsFile(NoteStemsFile.VERSION, notes).also { it.requireWellFormed(shard) }
        files.writeText(stemsFile(shard), NotebookJson.encodeToString(file))
    }

    private fun stemsFile(shard: Char) = File(stemsDir, shard.toString())

    /** The search stems of [record]; none for a note in a folder the search index leaves out. */
    private fun stemsFor(record: NoteRecord): NoteStems? {
        if (record.folder in INDEXED_FOLDER_BLOCKLIST) return null
        return NoteStems(
            hash = sha256(record.content),
            tokens = BodyTokenizer.tokenizeNote(record.content),
            tags = computeStemmedTags(FrontmatterTags.parseFrontmatterTags(record.content)),
        )
    }

    /** Deletes the file of [id]; an I/O failure is transient. */
    private fun deleteFile(id: String) {
        val file = noteFile(id)
        if (!file.delete() && file.exists()) {
            throw TransientCryptoException("delete failed for note $id", IOException("the file is still there"))
        }
    }

    private fun metadataFor(record: NoteRecord) =
        NoteMetadata(record.folder, record.name, sha256(record.content), record.modifiedAtMillis, record.title, record.date)

    private fun recordOf(entry: NoteMetadata, content: String) =
        NoteRecord(NoteRecord.VERSION, entry.folder, entry.name, entry.title, entry.date, entry.modifiedAtMillis, content)

    /** The index's size as [writeIndex] seals it, in UTF-8 bytes. */
    private fun indexBytes(index: Map<String, NoteMetadata>): Long =
        NotebookJson.encodeToString(NoteIndexFile(NoteIndexFile.VERSION, index)).encodeToByteArray().size.toLong()

    /** What one entry adds to [indexBytes], its separators included. */
    private fun entryBytes(id: String, entry: NoteMetadata): Long = NotebookJson.encodeToString(entry).encodeToByteArray().size + id.length + 4L

    /** The names held in [folder], each with the id holding it. */
    private fun namesIn(index: Map<String, NoteMetadata>, folder: String): Map<String, String> =
        index.entries.filter { it.value.folder == folder }.associate { (id, m) -> m.name to id }

    /** [name], then its `-2`.. suffixed forms, leaving out any the name rule refuses. */
    private fun candidateNames(name: String): Sequence<String> =
        (sequenceOf(name) + (2..MAX_NAME_SUFFIX).asSequence().map { NoteBuilder.suffixedFilename(name, it) })
            .filter { NotePaths.isValidName(it) }

    /** An id no listed note and no file under `notes/` has. */
    private fun freshId(index: Map<String, NoteMetadata>): String {
        repeat(ID_ATTEMPTS) {
            val id = newId()
            if (isValidNoteId(id) && id !in index && !noteFile(id).exists()) return id
        }
        throw IllegalStateException("no free note id")
    }

    /** The note files under `notes/`; a directory that exists but cannot be listed is a transient failure. */
    private fun noteIdsOnDisk(): List<String> {
        if (!notesDir.exists()) return emptyList()
        val listed = notesDir.listFiles() ?: throw TransientCryptoException("could not list the note files", IOException("listFiles returned null"))
        return listed.filter { it.isFile && isValidNoteId(it.name) }.map { it.name }.sorted()
    }

    private fun noteFile(id: String) = File(notesDir, id)

    private fun requireNoteId(id: String) = require(isValidNoteId(id)) { "not a note id: $id" }

    private fun String?.orBlankNull(): String? = this?.takeIf { it.isNotBlank() }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val NOTES_DIR = "notes"
        const val META_FILE = "notes_meta"
        const val STEMS_DIR = "note_stems"
        const val FOLDERS_FILE = "folders_list"
        const val FOLDER_TAGS_FILE = "folder_tags"
        private const val MAX_NAME_SUFFIX = 999
        private const val WRITE_CHUNK = 64
        private const val ID_ATTEMPTS = 8
        private val random = SecureRandom()
        private val SHARDS = "0123456789abcdef".toList()
        private val indexCache = ConcurrentHashMap<String, Map<String, NoteMetadata>>()
        private val revisions = ConcurrentHashMap<String, MutableStateFlow<Long>>()
        private val logger = Logger.withTag("NotebookStorage")

        internal fun randomNoteId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        /** The index is read and written whole; a file this reader refuses is never written. */
        const val MAX_META_BYTES = 64L * 1024 * 1024

        fun sha256(content: String): String =
            MessageDigest.getInstance("SHA-256").digest(content.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
