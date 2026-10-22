package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.backup.RestorePlan
import com.anopticlabs.gravel.notebook.notes.ARCHIVE_FOLDER
import com.anopticlabs.gravel.notebook.notes.NoteDisplay
import com.anopticlabs.gravel.notebook.notes.NoteMarkdown
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteItem
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NotesViewModelTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private var now = 1_790_089_200_000L
    private val store = NoteStore(root, crypto)
    private val viewModel = NotesViewModel(store, { now })
    private val messages get() = viewModel.state.value.messages

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun md(title: String, body: String) = NoteMarkdown.buildMarkdown(title, emptyList(), body, "2026-09-27")

    private suspend fun note(folder: String, title: String, body: String = "body of $title"): String {
        now += 1000
        return store.createNote(folder, "${title.lowercase()}_09.27.26_1430.md", md(title, body), title = title, date = "2026-09-27", modifiedAtMillis = now)
    }

    /** Runs [block] with the screen watching. */
    private fun watching(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val watch = launch(Dispatchers.Default) { viewModel.watch() }
        try {
            block()
        } finally {
            watch.cancelAndJoin()
        }
    }

    private suspend fun until(what: String = "state", check: (NotesState) -> Boolean): NotesState {
        withTimeout(5.seconds) { while (!check(viewModel.state.value)) delay(10) }
        return viewModel.state.value
    }

    private suspend fun shownNote(): OpenNote = (until { it.note is NoteView.Shown }.note as NoteView.Shown).note

    private suspend fun listed(folder: String): List<NoteItem> =
        (until { it.notesFolder == folder && it.notes is Loadable.Ready }.notes as Loadable.Ready).value

    private suspend fun Job.done() = join()

    @Test
    fun theGridIsReadAgainAfterAStoreChangeFromElsewhere() = watching {
        until { it.grid is Loadable.Ready }
        store.createFolder("work")
        val grid = until { s -> (s.grid as? Loadable.Ready)?.value?.tags?.containsKey("work") == true }.grid as Loadable.Ready
        assertEquals(listOf("unclassified", "work", "archive"), grid.value.folders.map { it.name })
        assertEquals(listOf("work"), grid.value.tags?.get("work"))
    }

    @Test
    fun anOpenFolderListsANoteWrittenMeanwhile() = watching {
        val first = note("work", "First")
        viewModel.openFolder("work")
        assertEquals(listOf(first), listed("work").map { it.id })
        val second = note("work", "Second")
        until { s -> (s.notes as? Loadable.Ready)?.value?.size == 2 }
        assertEquals(listOf(second, first), listed("work").map { it.id })
    }

    @Test
    fun aReadFinishingAfterItsPageWasLeftIsDropped() = watching {
        val slow = note("work", "Slow", "slow body")
        val fast = note("work", "Fast", "fast body")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        crypto.beforeOpen = { location ->
            if (location.contains("notes/$slow")) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val seen = CopyOnWriteArrayList<NotesState>()
        val recorder = launch(Dispatchers.Unconfined) { viewModel.state.collect { seen += it } }
        viewModel.openNote(slow)
        withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }
        viewModel.back()
        viewModel.openNote(fast)
        release.countDown()
        assertEquals("fast body\n", shownNote().body)
        recorder.cancelAndJoin()
        assertEquals(NotesPage.Note(fast), viewModel.state.value.page)
        assertTrue(seen.none { (it.note as? NoteView.Shown)?.note?.id == slow })
    }

    @Test
    fun aNoteIsShownWithoutItsFrontMatterAndAnUnlistedOneIsGone() = watching {
        val id = note("work", "Plan", "the plan")
        viewModel.openNote(id)
        val shown = shownNote()
        assertEquals("the plan\n", shown.body)
        assertEquals("Plan", shown.displayTitle)
        viewModel.back()
        viewModel.openNote("0".repeat(32))
        assertEquals(NoteView.Gone, until { it.note == NoteView.Gone }.note)
    }

    @Test
    fun aNoteThatCannotBeReadThisTimeShowsTheProblemAndRetryRecovers() = watching {
        val id = note("work", "Plan")
        crypto.failOpenWhenAadContains = "notes/$id"
        viewModel.openNote(id)
        assertEquals(NoteView.Failed(StorageProblem.UNREADABLE), until { it.note is NoteView.Failed }.note)
        assertNull(viewModel.state.value.editing)
        viewModel.startEdit()
        assertNull(viewModel.state.value.editing)
        crypto.failOpenWhenAadContains = null
        viewModel.retry()
        assertEquals(id, shownNote().id)
    }

    @Test
    fun anEditRetitlesAndRenamesTheNoteAndTheShownNoteFollows() = watching {
        val id = note("work", "Plan", "old")
        viewModel.openNote(id)
        shownNote()
        viewModel.startEdit()
        val draft = assertNotNull(viewModel.state.value.editing)
        assertEquals("Plan", draft.title)
        assertEquals("old\n", draft.body)
        draft.title = "Better plan"
        draft.body = "new"
        assertEquals(EditorOutcome.Saved("work"), viewModel.saveEdit(draft))
        assertNull(viewModel.state.value.editing)
        val shown = (until { s -> (s.note as? NoteView.Shown)?.note?.displayTitle == "Better plan" }.note as NoteView.Shown).note
        assertEquals("new", shown.body)
        assertTrue(shown.content.contains("title: \"Better plan\""))
        assertEquals("better-plan_09.27.26_1430.md", store.readMetadata(strict = true).getValue(id).name)
    }

    @Test
    fun anEditIsRefusedWithTheTextKeptWhenTheNoteChangedTheTitleIsBlankOrItIsTooLong() = watching {
        val id = note("work", "Plan", "old")
        viewModel.openNote(id)
        shownNote()
        viewModel.startEdit()
        val draft = assertNotNull(viewModel.state.value.editing)
        draft.title = "  "
        assertEquals(EditorOutcome.Refused(EditorError.TITLE_MISSING), viewModel.saveEdit(draft))
        draft.title = "Plan"
        draft.body = "x".repeat(RestorePlan.MAX_NOTE_CHARS)
        assertEquals(EditorOutcome.Refused(EditorError.TOO_LARGE), viewModel.saveEdit(draft))

        store.updateNote(id, md("Plan", "restored"), title = "Plan", date = "2026-09-27")
        draft.body = "mine"
        assertEquals(EditorOutcome.Refused(EditorError.CHANGED), viewModel.saveEdit(draft))
        assertEquals(draft, viewModel.state.value.editing)
        assertEquals("mine", draft.body)
        assertEquals(md("Plan", "restored"), store.readNote(id, strict = true))
    }

    @Test
    fun editFromTheListOpensTheEditorOnlyOnceTheNoteIsRead() = watching {
        val id = note("work", "Plan")
        store.forgetCachedIndexForTest()
        store.readMetadata(strict = true)
        val release = CountDownLatch(1)
        crypto.beforeOpen = { location -> if (location.contains("notes/$id")) release.await(5, TimeUnit.SECONDS) }
        viewModel.openFolder("work")
        listed("work")
        viewModel.editNote(id)
        delay(100)
        assertNull(viewModel.state.value.editing)
        release.countDown()
        val draft = assertNotNull(until { it.editing != null }.editing)
        assertEquals(id, draft.edit?.id)
    }

    @Test
    fun anArchivedNoteOpensWithoutAnEditor() = watching {
        val id = note("work", "Plan")
        store.moveNote(id, ARCHIVE_FOLDER)
        viewModel.editNote(id)
        assertEquals(ARCHIVE_FOLDER, shownNote().folder)
        delay(100)
        viewModel.startEdit()
        assertNull(viewModel.state.value.editing)
    }

    @Test
    fun aBatchKeepsSelectedAndCountsTheNotesTheStoreFailedOn() = watching {
        val a = note("work", "Alpha")
        val b = note("work", "Beta")
        viewModel.openFolder("work")
        listed("work")
        viewModel.select(a)
        viewModel.toggle(b)
        crypto.failSealWhenAadContains = "notes/$b"
        viewModel.archive(setOf(a, b)).done()
        crypto.failSealWhenAadContains = null
        assertEquals(setOf(b), viewModel.state.value.selection)
        assertEquals(listOf<NotesMessage>(NotesMessage.NotesLeft(1)), messages)
        assertEquals(ARCHIVE_FOLDER, store.readMetadata(strict = true).getValue(a).folder)
        assertEquals("work", store.readMetadata(strict = true).getValue(b).folder)
    }

    @Test
    fun emptyingTheArchiveDeletesOnlyTheCountedNotesStillThere() = watching {
        val a = note("work", "Alpha")
        val b = note("work", "Beta")
        store.moveNote(a, ARCHIVE_FOLDER)
        store.moveNote(b, ARCHIVE_FOLDER)
        viewModel.openFolder(ARCHIVE_FOLDER)
        listed(ARCHIVE_FOLDER)
        viewModel.askEmptyArchive()
        val dialog = assertIs<NotesDialog.EmptyArchive>(viewModel.state.value.dialog)
        assertEquals(setOf(a, b), dialog.ids)
        store.moveNote(a, "work")
        val c = note("work", "Gamma")
        store.moveNote(c, ARCHIVE_FOLDER)
        viewModel.deleteForGood(dialog.ids).done()
        assertEquals(setOf(a, c), store.readMetadata(strict = true).keys)
        assertEquals(emptyList<NotesMessage>(), messages)
    }

    @Test
    fun selectionEndsWhenTheFolderIsLeftAndBackEndsItFirst() = watching {
        val a = note("work", "Alpha")
        viewModel.openFolder("work")
        listed("work")
        viewModel.select(a)
        assertTrue(viewModel.state.value.canGoBack)
        viewModel.back()
        assertNull(viewModel.state.value.selection)
        assertEquals(NotesPage.Folder("work"), viewModel.state.value.page)
        viewModel.select(a)
        viewModel.openNote(a)
        assertNull(viewModel.state.value.selection)
        viewModel.back()
        viewModel.back()
        assertEquals(listOf<NotesPage>(NotesPage.Folders), viewModel.state.value.pages)
    }

    @Test
    fun aSecondActionWhileOneRunsIsIgnored() = watching {
        store.ensureFolder("home")
        until { s -> (s.grid as? Loadable.Ready)?.value?.folders?.any { it.name == "home" } == true }
        viewModel.askCreateFolder()
        val release = CountDownLatch(1)
        crypto.beforeOpen = { location -> if (location.contains(NoteStore.FOLDERS_FILE)) release.await(5, TimeUnit.SECONDS) }
        val first = viewModel.createFolder("work", "")
        until { it.busy }
        val second = viewModel.createFolder("work", "")
        assertTrue(second.isCompleted)
        release.countDown()
        first.done()
        crypto.beforeOpen = null
        assertNull(viewModel.state.value.dialog)
        assertEquals(1, store.readFolders(strict = true).count { it == "work" })
        assertEquals(false, viewModel.state.value.busy)
    }

    @Test
    fun createFolderKeepsTheDialogWithTheReasonOrReportsMissingTags() = watching {
        viewModel.askCreateFolder()
        viewModel.createFolder("Bad!", "").done()
        assertEquals(NotesDialog.CreateFolder(CreateFolderError.INVALID), viewModel.state.value.dialog)
        viewModel.createFolder("archive", "").done()
        assertEquals(NotesDialog.CreateFolder(CreateFolderError.RESERVED), viewModel.state.value.dialog)
        crypto.failSealWhenAadContains = NoteStore.FOLDER_TAGS_FILE
        viewModel.createFolder("work", "").done()
        crypto.failSealWhenAadContains = null
        assertNull(viewModel.state.value.dialog)
        assertEquals(listOf<NotesMessage>(NotesMessage.CreatedWithoutTags), messages)
        viewModel.askCreateFolder()
        viewModel.createFolder("Work", "").done()
        assertEquals(NotesDialog.CreateFolder(CreateFolderError.EXISTS), viewModel.state.value.dialog)
    }

    @Test
    fun aFolderDeleteThatFailsPartWayKeepsTheFolderAndCanBeRetried() = watching {
        store.createFolder("work")
        val a = note("work", "Alpha")
        crypto.failSealWhenAadContains = "notes/$a"
        viewModel.deleteFolder("work").done()
        crypto.failSealWhenAadContains = null
        assertEquals(listOf<NotesMessage>(NotesMessage.FolderKept(1)), messages)
        assertTrue("work" in store.readFolders(strict = true))
        viewModel.deleteFolder("work").done()
        assertEquals(NotesMessage.FolderDeleted(1), messages.last())
        assertEquals("unclassified", store.readMetadata(strict = true).getValue(a).folder)
    }

    @Test
    fun tagsSaveReplaceOrClearAndAFailureKeepsTheDialog() = watching {
        store.createFolder("work")
        until { s -> (s.grid as? Loadable.Ready)?.value?.tags?.containsKey("work") == true }
        viewModel.askEditTags("work")
        assertEquals(NotesDialog.EditTags("work", "work"), viewModel.state.value.dialog)
        crypto.failSealWhenAadContains = NoteStore.FOLDER_TAGS_FILE
        viewModel.saveTags("work", "Budget, q3").done()
        crypto.failSealWhenAadContains = null
        assertEquals(NotesDialog.EditTags("work", "Budget, q3", failed = true), viewModel.state.value.dialog)
        viewModel.saveTags("work", "Budget, q3").done()
        assertNull(viewModel.state.value.dialog)
        assertEquals(listOf("budget", "q3"), store.readFolderTags(strict = true)["work"])
        viewModel.saveTags("work", " , ").done()
        assertNull(store.readFolderTags(strict = true)["work"])
    }

    @Test
    fun showNoteOpensTheNoteOverTheGridFromAnywhere() = watching {
        val id = note("work", "Alpha")
        viewModel.openFolder("work")
        viewModel.showNote(id)
        assertEquals(listOf(NotesPage.Folders, NotesPage.Note(id)), viewModel.state.value.pages)
        assertEquals(id, shownNote().id)
    }

    @Test
    fun aRowShowsTheNoteDateOrElseWhenItLastChanged() {
        val item = NoteItem("0".repeat(32), "work", "a.md", "A", null, 1_790_089_200_000L)
        assertEquals("2026-09-22 15:00", noteRowDate(item, TimeZone.UTC))
        assertEquals("2026-09-27", noteRowDate(item.copy(date = "2026-09-27"), TimeZone.UTC))
    }

    @Test
    fun showingTheNoteAlreadyOpenReadsItAgain() = watching {
        val id = note("work", "Plan")
        viewModel.openNote(id)
        shownNote()
        val release = CountDownLatch(1)
        crypto.beforeOpen = { location -> if (location.contains("notes/$id")) release.await(5, TimeUnit.SECONDS) }
        viewModel.showNote(id)
        assertEquals(NoteView.Loading, viewModel.state.value.note)
        release.countDown()
        assertEquals(id, shownNote().id)
        crypto.beforeOpen = null
    }

    @Test
    fun showNoteKeepsAnOpenEditAndARunningAction() = watching {
        val a = note("work", "Alpha")
        val b = note("work", "Beta")
        store.moveNote(b, ARCHIVE_FOLDER)
        viewModel.openNote(a)
        shownNote()
        viewModel.startEdit()
        val draft = assertNotNull(viewModel.state.value.editing)
        val release = CountDownLatch(1)
        crypto.beforeSeal = { location -> if (location.contains("notes/$b")) release.await(5, TimeUnit.SECONDS) }
        val running = viewModel.move(setOf(b), "work")
        until { it.busy }
        viewModel.showNote(b)
        assertEquals(draft, viewModel.state.value.editing)
        assertTrue(viewModel.state.value.busy)
        release.countDown()
        running.done()
        crypto.beforeSeal = null
    }

    @Test
    fun aBatchFinishingAfterItsPageWasLeftLeavesTheNextPagesSelectionAlone() = watching {
        store.ensureFolder("home")
        val a = note("work", "Alpha")
        val h = note("home", "Home")
        viewModel.openFolder("work")
        listed("work")
        viewModel.select(a)
        val release = CountDownLatch(1)
        crypto.beforeSeal = { location -> if (location.contains("notes/$a")) release.await(5, TimeUnit.SECONDS) }
        crypto.failSealWhenAadContains = "notes/$a"
        val running = viewModel.archive(setOf(a))
        until { it.busy }
        viewModel.back()
        viewModel.back()
        viewModel.openFolder("home")
        listed("home")
        viewModel.select(h)
        release.countDown()
        running.done()
        crypto.beforeSeal = null
        crypto.failSealWhenAadContains = null
        assertEquals(setOf(h), viewModel.state.value.selection)
        assertEquals(listOf<NotesMessage>(NotesMessage.NotesLeft(1)), messages)
    }

    /**
     * Runs [block] with a second ViewModel, watching, whose actions run on one thread; `hold()` blocks that thread until
     * `release()`, so an action asked for in between starts late.
     */
    private fun withHeldActions(block: suspend CoroutineScope.(NotesViewModel, hold: () -> Unit, release: () -> Unit) -> Unit) = runBlocking {
        val thread = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        val opened = AtomicBoolean(true)
        try {
            val held = NotesViewModel(store, { now }, dispatcher = thread.asCoroutineDispatcher())
            val watch = launch(Dispatchers.Default) { held.watch() }
            block(held, { thread.execute { opened.set(gate.await(5, TimeUnit.SECONDS)) } }, { gate.countDown() })
            watch.cancelAndJoin()
            assertTrue(opened.get(), "the held action started on the timeout, not on release")
        } finally {
            thread.shutdownNow()
        }
    }

    private suspend fun NotesViewModel.listedIn(folder: String) =
        withTimeout(5.seconds) { while (state.value.notesFolder != folder || state.value.notes !is Loadable.Ready) delay(10) }

    @Test
    fun aMoveAskedJustBeforeGoingBackIsNotOpenedOnThePageBehind() = withHeldActions { held, hold, release ->
        store.ensureFolder("home")
        val a = note("work", "Alpha")
        held.openFolder("work")
        held.listedIn("work")
        hold()
        val asking = held.askMove(setOf(a), restore = false)
        held.back()
        release()
        asking.join()
        assertNull(held.state.value.dialog)
        assertEquals(listOf<NotesPage>(NotesPage.Folders), held.state.value.pages)
        assertEquals(emptyList(), held.state.value.messages)
    }

    @Test
    fun aMoveAskedThenBackAndBackInIsNotOpenedOnTheNewVisit() = withHeldActions { held, hold, release ->
        store.ensureFolder("home")
        val a = note("work", "Alpha")
        held.openFolder("work")
        held.listedIn("work")
        hold()
        val asking = held.askMove(setOf(a), restore = false)
        held.back()
        held.openFolder("work")
        release()
        asking.join()
        assertNull(held.state.value.dialog)
    }

    @Test
    fun aBatchStartingAfterItsPageWasLeftLeavesTheNextPagesSelectionAlone() = withHeldActions { held, hold, release ->
        store.ensureFolder("home")
        val a = note("work", "Alpha")
        val h = note("home", "Home")
        held.openFolder("work")
        held.listedIn("work")
        held.select(a)
        crypto.failSealWhenAadContains = "notes/$a"
        hold()
        val running = held.archive(setOf(a))
        held.back()
        held.back()
        held.openFolder("home")
        held.listedIn("home")
        held.select(h)
        release()
        running.join()
        crypto.failSealWhenAadContains = null
        assertEquals(setOf(h), held.state.value.selection)
    }

    @Test
    fun aMoveDialogIsNotOpenedOverAPageTheUserWentTo() = watching {
        store.ensureFolder("home")
        val a = note("work", "Alpha")
        viewModel.openFolder("work")
        listed("work")
        val release = CountDownLatch(1)
        crypto.beforeOpen = { location -> if (location.contains(NoteStore.FOLDERS_FILE)) release.await(5, TimeUnit.SECONDS) }
        val asking = viewModel.askMove(setOf(a), restore = false)
        until { it.busy }
        viewModel.back()
        release.countDown()
        asking.done()
        crypto.beforeOpen = null
        assertNull(viewModel.state.value.dialog)
    }

    @Test
    fun theGridStaysUsableWhenTheTagsOrTheFolderListCannotBeRead() = watching {
        store.createFolder("work")
        val a = note("work", "Alpha")
        crypto.failOpenWhenAadContains = NoteStore.FOLDER_TAGS_FILE
        viewModel.retry()
        val grid = (until { s -> ((s.grid as? Loadable.Ready)?.value?.problem) != null }.grid as Loadable.Ready).value
        assertNull(grid.tags)
        assertEquals(setOf("unclassified", "work"), grid.listed)
        viewModel.showFolderActions(grid.folders.first { it.name == "work" })
        assertEquals(false, (viewModel.state.value.sheet as NotesSheet.FolderActions).canEditTags)
        viewModel.dismissSheet()
        crypto.failOpenWhenAadContains = NoteStore.FOLDERS_FILE
        viewModel.retry()
        val noList = (until { s -> ((s.grid as? Loadable.Ready)?.value?.listed) == null }.grid as Loadable.Ready).value
        assertEquals(listOf("unclassified", "work", "archive"), noList.folders.map { it.name })
        assertEquals(StorageProblem.UNREADABLE, noList.problem)
        assertEquals(false, viewModel.state.value.takesNewNotes("work"))
        crypto.failOpenWhenAadContains = null
        viewModel.openFolder("work")
        assertEquals(listOf(a), listed("work").map { it.id })
    }

    @Test
    fun aDamagedNoteShowsAsDamagedAndItsFileStaysInPlace() = watching {
        val id = note("work", "Plan")
        val file = java.io.File(root, "${NoteStore.NOTES_DIR}/$id")
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        viewModel.openNote(id)
        assertEquals(NoteView.Failed(StorageProblem.DAMAGED), until { it.note is NoteView.Failed }.note)
        assertTrue(file.exists())
    }

    @Test
    fun anEditAfterAFailedIndexWriteSavesOnceTheNoteIsOpenedAgain() = watching {
        val id = note("work", "Plan", "old")
        viewModel.openNote(id)
        shownNote()
        viewModel.startEdit()
        val first = assertNotNull(viewModel.state.value.editing)
        first.body = "landed"
        crypto.failSealWhenAadContains = NoteStore.META_FILE
        assertEquals(EditorOutcome.Refused(EditorError.NOT_SAVED), viewModel.saveEdit(first))
        crypto.failSealWhenAadContains = null
        viewModel.closeEdit()
        viewModel.back()
        viewModel.openNote(id)
        assertEquals("landed", shownNote().body)
        viewModel.startEdit()
        val second = assertNotNull(viewModel.state.value.editing)
        second.body = "second"
        assertEquals(EditorOutcome.Saved("work"), viewModel.saveEdit(second))
    }

    @Test
    fun aLoadThatResumesAfterThePageChangedIsDropped() {
        val main = java.util.concurrent.Executors.newSingleThreadExecutor()
        val mainDispatcher = main.asCoroutineDispatcher()
        try {
            runBlocking {
                val slow = note("work", "Slow", "slow body")
                val fast = note("work", "Fast", "fast body")
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                crypto.beforeOpen = { location ->
                    if (location.contains("notes/$slow")) {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
                val seen = CopyOnWriteArrayList<NotesState>()
                val recorder = launch(Dispatchers.Unconfined) { viewModel.state.collect { seen += it } }
                val watch = launch(mainDispatcher) { viewModel.watch() }
                withContext(mainDispatcher) { viewModel.openNote(slow) }
                withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }
                // One task on the watching thread: the read finishes and its resumption queues behind this task, then the page changes.
                withContext(mainDispatcher) {
                    release.countDown()
                    Thread.sleep(300)
                    viewModel.back()
                    viewModel.openNote(fast)
                }
                assertEquals("fast body\n", shownNote().body)
                watch.cancelAndJoin()
                recorder.cancelAndJoin()
                assertTrue(seen.none { it.page == NotesPage.Note(fast) && (it.note as? NoteView.Shown)?.note?.id == slow })
            }
        } finally {
            mainDispatcher.close()
        }
    }

    @Test
    fun leavingANoteBeforeItIsReadCancelsItsPendingEdit() = watching {
        val id = note("work", "Plan")
        store.forgetCachedIndexForTest()
        store.readMetadata(strict = true)
        val release = CountDownLatch(1)
        crypto.beforeOpen = { location -> if (location.contains("notes/$id")) release.await(5, TimeUnit.SECONDS) }
        viewModel.editNote(id)
        viewModel.back()
        release.countDown()
        crypto.beforeOpen = null
        viewModel.openNote(id)
        shownNote()
        delay(100)
        assertNull(viewModel.state.value.editing)
    }

    @Test
    fun aDraftIsChangedByAnyTextWhenNewAndByAnyChangeWhenAnEdit() {
        val fresh = NoteDraft()
        assertEquals(false, fresh.changed)
        fresh.folder = "work"
        assertEquals(false, fresh.changed)
        fresh.body = "x"
        assertEquals(true, fresh.changed)
        val edit = NoteDraft("work", "Plan", "body", EditTarget("0".repeat(32), "h", "c"))
        assertEquals(false, edit.changed)
        edit.body = "body!"
        assertEquals(true, edit.changed)
        assertEquals(false, edit.titleChanged)
        edit.body = "body"
        edit.title = "Plan 2"
        assertEquals(true, edit.changed)
        assertEquals(true, edit.titleChanged)
    }

    @Test
    fun anUnchangedEditClosesWithoutAWrite() = watching {
        val id = note("work", "Plan")
        viewModel.openNote(id)
        shownNote()
        viewModel.startEdit()
        val draft = assertNotNull(viewModel.state.value.editing)
        val sealed = crypto.sealed.size
        assertEquals(EditorOutcome.Saved("work"), viewModel.saveEdit(draft))
        assertEquals(sealed, crypto.sealed.size)
        assertNull(viewModel.state.value.editing)
        assertEquals(emptyList<NotesMessage>(), messages)
    }

    @Test
    fun theNotePageAndTheEditorKeepTheBodysLeadingIndentation() = watching {
        val id = note("work", "Code", "    indented code\nline")
        viewModel.openNote(id)
        assertEquals("    indented code\nline\n", shownNote().body)
        viewModel.startEdit()
        val draft = assertNotNull(viewModel.state.value.editing)
        assertEquals("    indented code\nline\n", draft.body)
        draft.title = "Code 2"
        assertEquals(EditorOutcome.Saved("work"), viewModel.saveEdit(draft))
        val saved = assertNotNull(store.readNote(id, strict = true))
        assertTrue(saved.endsWith("---\n\n    indented code\nline\n"), saved)
    }

    @Test
    fun aBodyOnlyEditKeepsARawTitleAndTheName() = watching {
        now += 1000
        val raw = "  " + "t".repeat(250) + "  "
        val content = "---\ntitle: raw\n---\n\nbody\n"
        val id = store.createNote("work", "raw_09.27.26_1430.md", content, title = raw, modifiedAtMillis = now)
        viewModel.openNote(id)
        shownNote()
        viewModel.startEdit()
        val draft = assertNotNull(viewModel.state.value.editing)
        draft.body = "new body"
        assertEquals(EditorOutcome.Saved("work"), viewModel.saveEdit(draft))
        val entry = store.readMetadata(strict = true).getValue(id)
        assertEquals(raw, entry.title)
        assertEquals("raw_09.27.26_1430.md", entry.name)
        assertEquals("---\ntitle: raw\n---\n\nnew body", store.readNote(id, strict = true))
    }

    @Test
    fun anEditSavedWhileTheEditorLeavesStillLandsAndClosesIt() = watching {
        val id = note("work", "Plan", "old")
        viewModel.openNote(id)
        shownNote()
        viewModel.startEdit()
        val draft = assertNotNull(viewModel.state.value.editing)
        draft.body = "kept"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        crypto.beforeSeal = { location ->
            if (location.contains("notes/$id")) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val editor = CoroutineScope(Dispatchers.Default).launch { viewModel.saveEdit(draft) }
        withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }
        editor.cancel()
        release.countDown()
        until { it.editing == null }
        crypto.beforeSeal = null
        assertTrue(assertNotNull(store.readNote(id, strict = true)).endsWith("kept"))
        assertEquals(listOf<NotesMessage>(NotesMessage.NoteSaved), until { it.messages.isNotEmpty() }.messages)
    }

    @Test
    fun aMessageWaitsUntilItIsShown() = runBlocking {
        viewModel.askCreateFolder()
        crypto.failSealWhenAadContains = NoteStore.FOLDER_TAGS_FILE
        viewModel.createFolder("work", "").done()
        crypto.failSealWhenAadContains = null
        assertEquals(listOf<NotesMessage>(NotesMessage.CreatedWithoutTags), messages)
        viewModel.messageShown(NotesMessage.NotChanged)
        assertEquals(listOf<NotesMessage>(NotesMessage.CreatedWithoutTags), messages)
        viewModel.messageShown(NotesMessage.CreatedWithoutTags)
        assertEquals(emptyList<NotesMessage>(), messages)
    }

    @Test
    fun aNoteOverTheEditLimitOpensReadOnly() = watching {
        val long = note("work", "Long", "x".repeat(NoteDisplay.MAX_EDIT_CHARS + 1))
        viewModel.editNote(long)
        val shown = shownNote()
        assertTrue(shown.tooLongToEdit)
        delay(100)
        assertNull(viewModel.state.value.editing)
        viewModel.startEdit()
        assertNull(viewModel.state.value.editing)
        viewModel.back()
        val fits = note("work", "Fits", "x".repeat(NoteDisplay.MAX_EDIT_CHARS - 1))
        viewModel.openNote(fits)
        shownNote()
        viewModel.startEdit()
        assertNotNull(viewModel.state.value.editing)
    }

    @Test
    fun aDraftRefusesABodyOverTheEditLimitAndKeepsItsText() {
        val draft = NoteDraft()
        assertTrue(draft.offerBody("x".repeat(NoteDisplay.MAX_EDIT_CHARS)))
        assertEquals(false, draft.offerBody("y".repeat(NoteDisplay.MAX_EDIT_CHARS + 1)))
        assertEquals("x".repeat(NoteDisplay.MAX_EDIT_CHARS), draft.body)
    }

    @Test
    fun aTappedLinkWaitsForConfirmationWhenAllowedAndIsRefusedOtherwise() {
        viewModel.linkTapped("  https://example.org/a ")
        assertEquals("https://example.org/a", viewModel.state.value.link)
        viewModel.dismissLink()
        assertNull(viewModel.state.value.link)
        viewModel.linkTapped("javascript:alert(1)")
        assertNull(viewModel.state.value.link)
        assertEquals(listOf<NotesMessage>(NotesMessage.LinkRefused), messages)
        viewModel.linkTapped("tel:+15555550100")
        viewModel.linkNotOpened()
        assertNull(viewModel.state.value.link)
        assertEquals(NotesMessage.NoAppForLink, messages.last())
    }

    @Test
    fun leavingTheNoteDropsAPendingLink() = watching {
        val id = note("work", "Plan")
        viewModel.openNote(id)
        shownNote()
        viewModel.linkTapped("https://example.org")
        viewModel.back()
        assertNull(viewModel.state.value.link)
        viewModel.openNote(id)
        viewModel.linkTapped("https://example.org")
        viewModel.showNote(id)
        assertNull(viewModel.state.value.link)
    }
}
