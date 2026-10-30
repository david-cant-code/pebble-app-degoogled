package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.notes.ARCHIVE_FOLDER
import com.anopticlabs.gravel.notebook.notes.NoteMarkdown
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NoteStoreEditTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private var now = 1_700_000_000_000L
    private val store = NoteStore(root, fake) { now }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val original = NoteMarkdown.buildMarkdown("Old", listOf("plans"), "old body", "2026-09-27")

    private suspend fun create(folder: String = "work", name: String = "old_09.27.26_1430.md", content: String = original) =
        store.createNote(folder, name, content, title = "Old", date = "2026-09-27")

    @Test
    fun editReplacesContentAndTitleAndRenamesUnderTheOldStampKeepingDateAndFolder() = runBlocking {
        val id = create()
        now += 1000
        val edited = NoteMarkdown.buildMarkdown("New plan", listOf("plans"), "new body", "2026-09-27")
        assertEquals(EditNoteResult.Saved("new-plan_09.27.26_1430.md"), store.editNote(id, NoteStore.sha256(original), edited, "New plan"))
        assertEquals(edited, store.readNote(id, strict = true))
        val entry = store.readMetadata(strict = true).getValue(id)
        assertEquals(NoteMetadata("work", "new-plan_09.27.26_1430.md", NoteStore.sha256(edited), now, "New plan", "2026-09-27"), entry)
        assertEquals(NoteStore.sha256(edited), store.readStems(strict = true).getValue(id).hash)
    }

    @Test
    fun anEditWhileTheClockReadsAtOrBehindTheListedTimeIsTimedJustPastIt() = runBlocking {
        val id = create()
        val listed = now
        now -= 60_000
        store.editNote(id, NoteStore.sha256(original), "first", null)
        now = listed + 1
        store.editNote(id, NoteStore.sha256("first"), "second", null)
        assertEquals(listed + 2, store.readMetadata(strict = true).getValue(id).modifiedAtMillis)
        assertEquals(listed + 2, store.readNoteRecord(id, strict = true)!!.modifiedAtMillis)
    }

    @Test
    fun anEditsTimeStaysWithinTheStoredWindow() = runBlocking {
        val id = store.createNote("work", "late.md", "a", modifiedAtMillis = MAX_STORED_INSTANT_MILLIS - 1)
        store.editNote(id, NoteStore.sha256("a"), "b", null)
        store.editNote(id, NoteStore.sha256("b"), "c", null)
        assertEquals(MAX_STORED_INSTANT_MILLIS, store.readMetadata(strict = true).getValue(id).modifiedAtMillis)
    }

    @Test
    fun anUnchangedTitleKeepsTheName() = runBlocking {
        val id = create()
        assertEquals(EditNoteResult.Saved("old_09.27.26_1430.md"), store.editNote(id, NoteStore.sha256(original), "changed", "Old"))
    }

    @Test
    fun aRenameTakesASuffixWhenAnotherNoteInTheFolderHoldsTheName() = runBlocking {
        val id = create()
        store.createNote("work", "new-plan_09.27.26_1430.md", "other", title = "New plan")
        store.createNote("home", "second_09.27.26_1430.md", "elsewhere", title = "Second")
        assertEquals(EditNoteResult.Saved("new-plan-2_09.27.26_1430.md"), store.editNote(id, NoteStore.sha256(original), "a", "New plan"))
        val renamed = store.editNote(id, NoteStore.sha256("a"), "b", "Second")
        assertEquals(EditNoteResult.Saved("second_09.27.26_1430.md"), renamed)
    }

    @Test
    fun aTitleWithNoUsableNameKeepsTheName() = runBlocking {
        val id = create(name = "restored.md")
        assertEquals(EditNoteResult.Saved("restored.md"), store.editNote(id, NoteStore.sha256(original), "x", "★"))
        assertEquals("★", store.readMetadata(strict = true).getValue(id).title)
    }

    @Test
    fun aChangedArchivedOrUnlistedNoteIsRefusedAndNothingIsWritten() = runBlocking {
        val id = create()
        val sealedBefore = fake.sealed.size
        assertEquals(EditNoteResult.Changed, store.editNote(id, NoteStore.sha256("something else"), "x", "Old"))
        assertEquals(EditNoteResult.NotListed, store.editNote("0".repeat(32), NoteStore.sha256(original), "x", "Old"))
        assertEquals(sealedBefore, fake.sealed.size)
        assertEquals(original, store.readNote(id, strict = true))

        store.moveNote(id, ARCHIVE_FOLDER)
        val sealedAfterMove = fake.sealed.size
        assertEquals(EditNoteResult.Archived, store.editNote(id, NoteStore.sha256(original), "x", "Old"))
        assertEquals(sealedAfterMove, fake.sealed.size)
        assertEquals(original, store.readNote(id, strict = true))
    }

    @Test
    fun anIndexThatCannotBeReadThisTimeFailsTheEditWithTheNoteUnchanged() = runBlocking {
        val id = create()
        store.forgetCachedIndexForTest()
        fake.failOpenWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.editNote(id, NoteStore.sha256(original), "x", "New") }
        fake.failOpenWhenAadContains = null
        assertEquals(original, store.readNote(id, strict = true))
        assertEquals("Old", store.readMetadata(strict = true).getValue(id).title)
    }

    @Test
    fun revisionCountsTheIndexFolderAndTagWritesThatLand() = runBlocking {
        fun rev() = store.revision.value
        val start = rev()
        val id = create()
        assertEquals(start + 1, rev())
        store.ensureFolder("work")
        assertEquals(start + 2, rev())
        store.ensureFolder("work")
        assertEquals(start + 2, rev())
        store.updateFolderTags { it + ("work" to listOf("work")) }
        assertEquals(start + 3, rev())
        store.updateFolderTags { it }
        assertEquals(start + 3, rev())
        store.editNote(id, NoteStore.sha256(original), "x", "Old")
        assertEquals(start + 4, rev())

        fake.failSealWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.deleteNote(id) }
        fake.failSealWhenAadContains = null
        assertEquals(start + 4, rev())
        store.deleteNote(id)
        assertEquals(start + 5, rev())
    }

    @Test
    fun storesOverOneRootShareTheRevision() = runBlocking {
        val other = NoteStore(root, fake)
        val before = other.revision.value
        create()
        assertEquals(before + 1, other.revision.value)
    }

    @Test
    fun deleteArchivedNotesDeletesOnlyNotesListedInTheArchive() = runBlocking {
        val kept = create()
        val archived = create(name = "b.md", content = "b")
        store.moveNote(archived, ARCHIVE_FOLDER)
        val unknown = "0".repeat(32)
        assertEquals(BatchResult(setOf(archived), setOf(kept, unknown), emptySet()), store.deleteArchivedNotes(listOf(kept, archived, unknown)))
        assertEquals(original, store.readNote(kept, strict = true))
        assertEquals(setOf(kept), store.readMetadata(strict = true).keys)
        assertEquals(null, store.readNote(archived, strict = true))
    }

    @Test
    fun aBatchWritesTheIndexOncePerChunkAndGoesOnPastANoteItFailsOn() = runBlocking {
        val ids = (1..70).map { store.createNote("work", "n$it.md", "note $it") }
        val failing = ids[3]
        val sealedBefore = fake.sealed.count { it.contains(NoteStore.META_FILE) }
        fake.failSealWhenAadContains = "notes/$failing"
        val result = store.moveNotes(ids, ARCHIVE_FOLDER)
        fake.failSealWhenAadContains = null
        assertEquals(setOf(failing), result.failed)
        assertEquals(ids.toSet() - failing, result.done)
        assertEquals(2, fake.sealed.count { it.contains(NoteStore.META_FILE) } - sealedBefore)
        val folders = store.readMetadata(strict = true).mapValues { it.value.folder }
        assertEquals("work", folders[failing])
        assertEquals(69, folders.values.count { it == ARCHIVE_FOLDER })
    }

    @Test
    fun aDamagedNoteReadForDisplayStaysInPlace() = runBlocking {
        val id = create()
        val file = java.io.File(root, "${NoteStore.NOTES_DIR}/$id")
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertFailsWith<CorruptFileException> { store.readNote(id, strict = true, quarantineCorrupt = false) }
        assertEquals(true, file.exists())
    }

    @Test
    fun aNullTitleKeepsTheListedTitleAndName() = runBlocking {
        val id = create()
        assertEquals(EditNoteResult.Saved("old_09.27.26_1430.md"), store.editNote(id, NoteStore.sha256(original), "new body", null))
        val entry = store.readMetadata(strict = true).getValue(id)
        assertEquals("Old", entry.title)
        assertEquals(NoteStore.sha256("new body"), entry.hash)
    }

    @Test
    fun aRetitleToTheSameSlugKeepsTheNoteItsOwnName() = runBlocking {
        val id = create()
        assertEquals(EditNoteResult.Saved("old_09.27.26_1430.md"), store.editNote(id, NoteStore.sha256(original), "x", "OLD"))
    }

    @Test
    fun anIndexWriteThatFailsAfterTheFileLandedStillLetsTheNextEditSave() = runBlocking {
        val id = create()
        fake.failSealWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.editNote(id, NoteStore.sha256(original), "landed", null) }
        fake.failSealWhenAadContains = null
        val listedHash = store.readMetadata(strict = true).getValue(id).hash
        assertEquals(EditNoteResult.Saved("old_09.27.26_1430.md"), store.editNote(id, listedHash, "second", null))
        assertEquals("second", store.readNote(id, strict = true))
    }

    @Test
    fun createListedNoteWritesToAListedFolderAndOtherwiseToUnclassified() = runBlocking {
        store.ensureFolder("work")
        assertEquals("work", store.createListedNote("work", "a.md", "a").folder)
        val elsewhere = store.createListedNote("gone", "b.md", "b")
        assertEquals("unclassified", elsewhere.folder)
        assertEquals("unclassified", store.readMetadata(strict = true).getValue(elsewhere.id).folder)
        assertEquals("unclassified", store.createListedNote("unclassified", "c.md", "c").folder)
    }
}
