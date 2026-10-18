package com.anopticlabs.gravel.notebook.ui

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.backup.RestorePlan
import com.anopticlabs.gravel.notebook.notes.ARCHIVE_FOLDER
import com.anopticlabs.gravel.notebook.notes.DEFAULT_FOLDERS
import com.anopticlabs.gravel.notebook.notes.Frontmatter
import com.anopticlabs.gravel.notebook.notes.INDEXED_FOLDER_BLOCKLIST
import com.anopticlabs.gravel.notebook.notes.NoteDisplay
import com.anopticlabs.gravel.notebook.notes.RESERVED_FOLDERS
import com.anopticlabs.gravel.notebook.notes.captureRouteFolders
import com.anopticlabs.gravel.notebook.notes.parseTagsCsv
import com.anopticlabs.gravel.notebook.sources.TypedNotes
import com.anopticlabs.gravel.notebook.storage.BatchResult
import com.anopticlabs.gravel.notebook.storage.CreateFolderResult
import com.anopticlabs.gravel.notebook.storage.DeleteFolderResult
import com.anopticlabs.gravel.notebook.storage.EditNoteResult
import com.anopticlabs.gravel.notebook.storage.FolderItem
import com.anopticlabs.gravel.notebook.storage.NoteItem
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.isStorageFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** A screen of the Notes section; [NotesState.pages] holds them outermost first. */
sealed interface NotesPage {
    data object Folders : NotesPage

    data class Folder(val name: String) : NotesPage

    data class Note(val id: String) : NotesPage
}

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Ready<T>(val value: T) : Loadable<T>

    data class Failed(val problem: StorageProblem) : Loadable<Nothing>
}

/**
 * The folder grid. [listed] is null when the folder list could not be read (the folders shown are
 * then the defaults and those holding notes), [tags] null when the folder tags could not be;
 * [problem] says why.
 */
data class FolderGrid(
    val folders: List<FolderItem>,
    val listed: Set<String>?,
    val tags: Map<String, List<String>>?,
    val problem: StorageProblem? = null,
)

/** A note as its screen shows it: [content] is the whole text, [hash] what the index lists it with. */
data class OpenNote(val id: String, val folder: String, val title: String?, val displayTitle: String, val content: String, val hash: String) {
    val body: String = Frontmatter.editableBody(content)

    val tooLongToEdit: Boolean = body.length > NoteDisplay.MAX_EDIT_CHARS

    /** Whether the editor opens on it: not archived, and within [NoteDisplay.MAX_EDIT_CHARS]. */
    val editable: Boolean get() = folder != ARCHIVE_FOLDER && !tooLongToEdit
}

sealed interface NoteView {
    data object Loading : NoteView

    data class Shown(val note: OpenNote) : NoteView

    /** The index no longer lists the note, or lists it without its file. */
    data object Gone : NoteView

    data class Failed(val problem: StorageProblem) : NoteView
}

sealed interface NotesSheet {
    /** [canEditTags]: the folder is listed and the tags were read. */
    data class FolderActions(val folder: FolderItem, val canEditTags: Boolean) : NotesSheet

    data class NoteActions(val note: NoteItem) : NotesSheet
}

enum class CreateFolderError { INVALID, RESERVED, EXISTS, NOT_SAVED }

sealed interface NotesDialog {
    data class CreateFolder(val error: CreateFolderError? = null) : NotesDialog

    data class EditTags(val folder: String, val csv: String, val failed: Boolean = false) : NotesDialog

    data class DeleteFolder(val folder: FolderItem) : NotesDialog

    /** [restore] moves out of the archive; [targets] excludes the folder the notes are in. */
    data class MoveNotes(val ids: Set<String>, val targets: List<String>, val restore: Boolean) : NotesDialog

    data class DeleteNotes(val ids: Set<String>) : NotesDialog

    data class EmptyArchive(val ids: Set<String>) : NotesDialog
}

sealed interface NotesMessage {
    data class FolderDeleted(val moved: Int) : NotesMessage

    /** [remaining] notes could not be moved out, so the folder stays. */
    data class FolderKept(val remaining: Int) : NotesMessage

    data object CreatedWithoutTags : NotesMessage

    /** [count] notes of an action were left as they were. */
    data class NotesLeft(val count: Int) : NotesMessage

    data object NotChanged : NotesMessage

    data object NoteGone : NotesMessage

    data object NoteSaved : NotesMessage

    data object LinkRefused : NotesMessage

    data object NoAppForLink : NotesMessage
}

data class NotesState(
    val pages: List<NotesPage> = listOf(NotesPage.Folders),
    /** Counts navigations, so a return to the same page is read again and a read for an earlier visit is dropped. */
    val visit: Long = 0,
    val grid: Loadable<FolderGrid> = Loadable.Loading,
    val notes: Loadable<List<NoteItem>> = Loadable.Loading,
    /** The folder [notes] lists. */
    val notesFolder: String? = null,
    val note: NoteView = NoteView.Loading,
    /** The note whose editor opens once it is shown. */
    val pendingEdit: String? = null,
    val selection: Set<String>? = null,
    val sheet: NotesSheet? = null,
    val dialog: NotesDialog? = null,
    val editing: NoteDraft? = null,
    /** A link from the shown note waiting for the user to confirm opening it. */
    val link: String? = null,
    val busy: Boolean = false,
    /** Messages not shown yet, oldest first; they wait while the section is not on screen. */
    val messages: List<NotesMessage> = emptyList(),
) {
    val page: NotesPage get() = pages.last()
    val canGoBack: Boolean get() = selection != null || pages.size > 1

    /** Whether "New note" is offered in [folder]: one the grid lists, other than the archive. */
    fun takesNewNotes(folder: String): Boolean {
        val listed = (grid as? Loadable.Ready)?.value?.listed ?: return false
        return folder !in INDEXED_FOLDER_BLOCKLIST && folder in listed
    }
}

/**
 * The Notes section: folders, a folder's notes, one note, and the edits to them. [grid], [notes]
 * and [note] belong to the innermost page and are read again when it changes and after every
 * [NoteStore.revision] change; a read finishing after its page was left is dropped. Actions and
 * edit saves run in the ViewModel's scope, actions one at a time; a result that belongs to a page
 * is applied only while that page is still shown.
 */
class NotesViewModel(
    private val store: NoteStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineContext = Dispatchers.IO,
    /** Runs actions and edit saves; a test holds it to start an action late. */
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(NotesState())
    val state: StateFlow<NotesState> = _state.asStateFlow()

    private val retries = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val acting = AtomicBoolean(false)

    override fun onCleared() {
        scope.cancel()
    }

    /** Reads the innermost page's data while the screen is started; see the class comment. */
    suspend fun watch() {
        combine(_state.map { it.page to it.visit }.distinctUntilChanged(), store.revision, retries) { shown, _, _ -> shown }
            .collectLatest { (page, visit) -> load(page, visit) }
    }

    fun retry() = retries.update { it + 1 }

    fun messageShown(message: NotesMessage) = _state.update {
        if (it.messages.firstOrNull() == message) it.copy(messages = it.messages.drop(1)) else it
    }

    private fun post(message: NotesMessage) = _state.update { it.copy(messages = it.messages + message) }

    // Navigation

    fun openFolder(name: String) = push(NotesPage.Folder(name))

    fun openNote(id: String) = push(NotesPage.Note(id))

    /** Opens note [id] over the folder grid, from outside the section. */
    fun showNote(id: String) = _state.update { leave(it, listOf(NotesPage.Folders, NotesPage.Note(id))) }

    /** Ends a selection, or else goes to the enclosing page. */
    fun back() = _state.update {
        when {
            it.selection != null -> it.copy(selection = null)
            it.pages.size > 1 -> leave(it, it.pages.dropLast(1))
            else -> it
        }
    }

    private fun push(page: NotesPage) = _state.update { if (it.page == page) it else leave(it, it.pages + page) }

    private fun leave(state: NotesState, pages: List<NotesPage>): NotesState {
        val top = pages.last()
        return state.copy(
            pages = pages,
            visit = state.visit + 1,
            notes = if (top is NotesPage.Folder && top.name != state.notesFolder) Loadable.Loading else state.notes,
            note = NoteView.Loading,
            pendingEdit = null,
            selection = null,
            sheet = null,
            dialog = null,
            link = null,
        )
    }

    // Loading

    private suspend fun load(page: NotesPage, visit: Long) {
        when (page) {
            NotesPage.Folders -> {
                val grid = read { readGrid() }
                _state.update { if (it.visit == visit) it.copy(grid = grid) else it }
            }
            is NotesPage.Folder -> {
                val notes = read { store.listNoteItems(page.name, strict = true) }
                _state.update { state ->
                    if (state.visit != visit) return@update state
                    val listed = (notes as? Loadable.Ready)?.value?.mapTo(HashSet()) { it.id }
                    state.copy(notes = notes, notesFolder = page.name, selection = state.selection?.let { sel -> listed?.let { sel intersect it } ?: sel })
                }
            }
            is NotesPage.Note -> {
                val view = loadNote(page.id, (_state.value.note as? NoteView.Shown)?.note)
                _state.update { state ->
                    if (state.visit != visit) return@update state
                    val next = state.copy(note = view)
                    if (view is NoteView.Shown && state.pendingEdit == page.id) withEditor(next.copy(pendingEdit = null)) else next
                }
            }
        }
    }

    /** The grid needs the index; a folder list or tags file that cannot be read leaves the grid usable without it. */
    private fun readGrid(): FolderGrid {
        val index = store.readMetadata(strict = true)
        var problem: StorageProblem? = null
        fun <T> part(read: () -> T): T? = try {
            read()
        } catch (e: Exception) {
            if (!isStorageFailure(e)) throw e
            logger.w { "Notes grid part unreadable: ${e::class.simpleName}" }
            problem = problem ?: storageProblemOf(e)
            null
        }
        val listed = part { store.readFolders(strict = true) }
        val tags = part { store.readFolderTags(strict = true) }
        return FolderGrid(store.folderItems(listed ?: DEFAULT_FOLDERS, index), listed?.toSet(), tags, problem)
    }

    private suspend fun <T> read(block: () -> T): Loadable<T> = withContext(io) {
        try {
            Loadable.Ready(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "Notes read failed: ${e::class.simpleName}" }
            Loadable.Failed(storageProblemOf(e))
        }
    }

    /**
     * The note's view; [shown] is kept without opening the file while the index lists it with the
     * same hash. The file is read without quarantining it, so a damaged note stays in place.
     */
    private suspend fun loadNote(id: String, shown: OpenNote?): NoteView = withContext(io) {
        try {
            val entry = store.readMetadata(strict = true)[id] ?: return@withContext NoteView.Gone
            val displayTitle = entry.title?.takeIf { it.isNotBlank() } ?: entry.name.removeSuffix(".md")
            if (shown != null && shown.id == id && shown.hash == entry.hash) {
                return@withContext NoteView.Shown(shown.copy(folder = entry.folder, title = entry.title, displayTitle = displayTitle))
            }
            val content = store.readNote(id, strict = true, quarantineCorrupt = false) ?: return@withContext NoteView.Gone
            NoteView.Shown(OpenNote(id, entry.folder, entry.title, displayTitle, content, entry.hash))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "Note read failed: ${e::class.simpleName}" }
            NoteView.Failed(storageProblemOf(e))
        }
    }

    // Sheets, dialogs and selection

    fun showFolderActions(folder: FolderItem) {
        if (folder.name in RESERVED_FOLDERS) return
        _state.update { state ->
            val grid = (state.grid as? Loadable.Ready)?.value
            val canEditTags = grid?.tags != null && grid.listed?.contains(folder.name) == true
            state.copy(sheet = NotesSheet.FolderActions(folder, canEditTags))
        }
    }

    fun showNoteActions(note: NoteItem) = _state.update { if (it.selection == null) it.copy(sheet = NotesSheet.NoteActions(note)) else it }

    fun dismissSheet() = _state.update { it.copy(sheet = null) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun askCreateFolder() = _state.update { it.copy(sheet = null, dialog = NotesDialog.CreateFolder()) }

    fun askEditTags(folder: String) = _state.update { state ->
        val tags = (state.grid as? Loadable.Ready)?.value?.tags?.get(folder).orEmpty()
        state.copy(sheet = null, dialog = NotesDialog.EditTags(folder, tags.joinToString(", ")))
    }

    fun askDeleteFolder(folder: FolderItem) = _state.update { it.copy(sheet = null, dialog = NotesDialog.DeleteFolder(folder)) }

    fun askDeleteNotes(ids: Set<String>) = _state.update { it.copy(sheet = null, dialog = NotesDialog.DeleteNotes(ids)) }

    /** Asks to empty the archive of the notes its list shows now; only those are deleted. */
    fun askEmptyArchive() = _state.update { state ->
        val shown = (state.notes as? Loadable.Ready)?.value.orEmpty()
        if (state.page != NotesPage.Folder(ARCHIVE_FOLDER) || shown.isEmpty()) state
        else state.copy(dialog = NotesDialog.EmptyArchive(shown.mapTo(LinkedHashSet()) { it.id }))
    }

    fun select(id: String) = _state.update { it.copy(sheet = null, selection = (it.selection ?: emptySet()) + id) }

    fun toggle(id: String) = _state.update { state ->
        val selection = state.selection ?: return@update state
        state.copy(selection = if (id in selection) selection - id else selection + id)
    }

    fun selectAll() = _state.update { state ->
        val shown = (state.notes as? Loadable.Ready)?.value ?: return@update state
        state.copy(selection = shown.mapTo(LinkedHashSet()) { it.id })
    }

    fun selectNone() = _state.update { if (it.selection != null) it.copy(selection = emptySet()) else it }

    // Actions

    /** Runs [block] unless another action is running; a store failure it lets out becomes [NotesMessage.NotChanged]. */
    private fun act(block: suspend () -> Unit): Job {
        if (!acting.compareAndSet(false, true)) return CompletableDeferred(Unit)
        _state.update { it.copy(busy = true) }
        return scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w { "Notes action failed: ${e::class.simpleName}" }
                post(NotesMessage.NotChanged)
            } finally {
                _state.update { it.copy(busy = false) }
                acting.set(false)
            }
        }
    }

    /** Creates a folder; the dialog stays open with the reason when it is refused or not saved. */
    fun createFolder(name: String, tagsCsv: String) = act {
        val error = try {
            when (store.createFolder(name, tagsCsv)) {
                is CreateFolderResult.Created -> null
                is CreateFolderResult.CreatedWithoutTags -> {
                    post(NotesMessage.CreatedWithoutTags)
                    null
                }
                CreateFolderResult.InvalidName -> CreateFolderError.INVALID
                is CreateFolderResult.Reserved -> CreateFolderError.RESERVED
                is CreateFolderResult.AlreadyExists -> CreateFolderError.EXISTS
            }
        } catch (e: Exception) {
            if (!isStorageFailure(e)) throw e
            CreateFolderError.NOT_SAVED
        }
        _state.update { state ->
            if (state.dialog !is NotesDialog.CreateFolder) state else state.copy(dialog = error?.let { NotesDialog.CreateFolder(it) })
        }
    }

    fun saveTags(folder: String, csv: String) = act {
        val tags = parseTagsCsv(csv)
        val saved = try {
            if (folder in RESERVED_FOLDERS || folder !in store.readFolders(strict = true)) {
                post(NotesMessage.NotChanged)
            } else {
                store.updateFolderTags { if (tags.isEmpty()) it - folder else it + (folder to tags) }
            }
            true
        } catch (e: Exception) {
            if (!isStorageFailure(e)) throw e
            false
        }
        _state.update { state ->
            val dialog = state.dialog as? NotesDialog.EditTags ?: return@update state
            state.copy(dialog = if (saved) null else dialog.copy(csv = csv, failed = true))
        }
    }

    fun deleteFolder(folder: String) = act {
        _state.update { it.copy(dialog = null) }
        when (val result = store.deleteFolder(folder)) {
            is DeleteFolderResult.Deleted -> post(NotesMessage.FolderDeleted(result.movedNotes))
            is DeleteFolderResult.NotesRemain -> post(NotesMessage.FolderKept(result.ids.size))
            DeleteFolderResult.Reserved -> Unit
        }
    }

    /** Offers the folders [ids] can move to: every folder a note can be written to, less the one shown. */
    fun askMove(ids: Set<String>, restore: Boolean): Job {
        // Read at the tap: a page visit that starts before the action runs must not get the dialog.
        val asked = _state.value
        return act { showMoveDialog(ids, restore, asked) }
    }

    private suspend fun showMoveDialog(ids: Set<String>, restore: Boolean, asked: NotesState) {
        val here = (asked.pages.last() as? NotesPage.Folder)?.name
        val targets = withContext(io) { captureRouteFolders(store.readFolders(strict = true)) } - setOfNotNull(here)
        _state.update { if (it.visit == asked.visit) it.copy(sheet = null, dialog = NotesDialog.MoveNotes(ids, targets, restore)) else it }
    }

    fun move(ids: Set<String>, target: String) = batch(ids) { store.moveNotes(ids, target) }

    fun archive(ids: Set<String>) = batch(ids) { store.moveNotes(ids, ARCHIVE_FOLDER) }

    /** Deletes those of [ids] still in the archive; a note moved out meanwhile is left alone. */
    fun deleteForGood(ids: Set<String>) = batch(ids, reportGone = false) { store.deleteArchivedNotes(ids) }

    /**
     * Runs [run] over [ids], closing any sheet or dialog first. While the page visit the batch was
     * asked from lasts, a selection keeps the notes the store failed on and ends when none failed;
     * those are counted in [NotesMessage.NotesLeft]. A single note no longer listed is reported
     * when [reportGone].
     */
    private fun batch(ids: Set<String>, reportGone: Boolean = true, run: suspend () -> BatchResult): Job {
        val visit = _state.value.visit
        return act { runBatch(ids, reportGone, visit, run) }
    }

    private suspend fun runBatch(ids: Set<String>, reportGone: Boolean, visit: Long, run: suspend () -> BatchResult) {
        _state.update { if (it.visit == visit) it.copy(sheet = null, dialog = null) else it }
        val result = run()
        _state.update { state ->
            if (state.visit != visit || state.selection == null) state
            else state.copy(selection = result.failed.takeIf { it.isNotEmpty() })
        }
        if (result.failed.isNotEmpty()) post(NotesMessage.NotesLeft(result.failed.size))
        else if (reportGone && ids.size == 1 && result.notThere.isNotEmpty()) post(NotesMessage.NoteGone)
    }

    // Editing

    /** Opens note [id] with its editor over it once it is read. */
    fun editNote(id: String) = _state.update { state ->
        val page = NotesPage.Note(id)
        if (state.page == page) withEditor(state) else leave(state, state.pages + page).copy(pendingEdit = id)
    }

    /** Opens the editor on the shown note when it is [OpenNote.editable]. */
    fun startEdit() = _state.update(::withEditor)

    private fun withEditor(state: NotesState): NotesState {
        val note = (state.note as? NoteView.Shown)?.note ?: return state
        if (!note.editable || state.editing != null) return state
        return state.copy(sheet = null, editing = NoteDraft(note.folder, note.title.orEmpty(), note.body, EditTarget(note.id, note.hash, note.content)))
    }

    fun closeEdit() = _state.update { it.copy(editing = null) }

    /** A link tapped in a note: asked about when [NoteLinkSafety] allows it, refused with a message otherwise. */
    fun linkTapped(link: String) {
        val allowed = NoteLinkSafety.allowed(link)
        if (allowed == null) post(NotesMessage.LinkRefused) else _state.update { it.copy(link = allowed) }
    }

    fun dismissLink() = _state.update { it.copy(link = null) }

    fun linkNotOpened() {
        dismissLink()
        post(NotesMessage.NoAppForLink)
    }

    /**
     * Saves [draft] over the note it edits, in the ViewModel's scope, so the outcome is applied to
     * the state even when the editor leaves meanwhile. An unchanged draft closes without a write; a
     * title left as it was keeps the note's title and name.
     */
    suspend fun saveEdit(draft: NoteDraft): EditorOutcome = scope.async { saveEditNow(draft) }.await()

    private suspend fun saveEditNow(draft: NoteDraft): EditorOutcome {
        val target = draft.edit ?: return EditorOutcome.Refused(EditorError.NOT_SAVED)
        if (!draft.changed) return closed(draft, saved = false)
        val title = if (draft.titleChanged) TypedNotes.cleanTitle(draft.title).ifEmpty { return EditorOutcome.Refused(EditorError.TITLE_MISSING) } else null
        val body = Frontmatter.recombine(target.content, draft.body)
        val content = if (title != null) Frontmatter.withTitle(body, title) else body
        if (content.length > RestorePlan.MAX_NOTE_CHARS) return EditorOutcome.Refused(EditorError.TOO_LARGE)
        val result = try {
            store.editNote(target.id, target.hash, content, title, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "Note edit not saved: ${e::class.simpleName}" }
            return EditorOutcome.Refused(EditorError.NOT_SAVED)
        }
        return when (result) {
            is EditNoteResult.Saved -> closed(draft, saved = true)
            EditNoteResult.Changed -> EditorOutcome.Refused(EditorError.CHANGED)
            EditNoteResult.NotListed -> EditorOutcome.Refused(EditorError.GONE)
            EditNoteResult.Archived -> EditorOutcome.Refused(EditorError.ARCHIVED)
        }
    }

    private fun closed(draft: NoteDraft, saved: Boolean): EditorOutcome {
        _state.update { if (it.editing === draft) it.copy(editing = null) else it }
        if (saved) post(NotesMessage.NoteSaved)
        return EditorOutcome.Saved(draft.folder)
    }

    private companion object {
        val logger = Logger.withTag("NotesViewModel")
    }
}
