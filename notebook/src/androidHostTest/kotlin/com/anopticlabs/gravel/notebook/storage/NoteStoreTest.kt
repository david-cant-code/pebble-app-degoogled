package com.anopticlabs.gravel.notebook.storage

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.anopticlabs.gravel.notebook.notes.NoteMarkdown
import com.anopticlabs.gravel.notebook.notes.NotePaths
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteStoreTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private var now = 1_700_000_000_000L
    private val store = NoteStore(root, fake) { now }
    private val files = EncryptedFiles(root, fake)
    private val metaFile = File(root, NoteStore.META_FILE)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun md(title: String, tags: List<String>, body: String) = NoteMarkdown.buildMarkdown(title, tags, body, "2026-05-31")

    private fun noteFile(id: String) = File(root, "${NoteStore.NOTES_DIR}/$id")

    private fun quarantined() = files.quarantineDir.listFiles().orEmpty().map { it.name }.sorted()

    private fun record(id: String) = NotebookJson.decodeFromString<NoteRecord>(files.readText(noteFile(id))!!)

    private fun writeRecord(id: String, folder: String, name: String, content: String, modifiedAtMillis: Long = 1L) =
        files.writeText(noteFile(id), NotebookJson.encodeToString(NoteRecord(NoteRecord.VERSION, folder, name, null, null, modifiedAtMillis, content)))

    private fun corrupt(file: File) = file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })

    private suspend fun stemsAfterCreate(folder: String, content: String): NoteStems? {
        val id = store.createNote(folder, "x.md", content)
        return store.readStems(strict = true)[id]
    }

    @Test
    fun createThenReadRoundTripsAndIndexesTheNote() = runBlocking {
        val content = md("Q3 plans", listOf("Shopping", "Errands"), "the budget meeting is on tuesday")
        val id = store.createNote("work", "q3-plans_05.31.26_1407.md", content, title = "Q3 plans", date = "2026-05-31", modifiedAtMillis = 42L)

        assertTrue(isValidNoteId(id), id)
        assertEquals(content, store.readNote(id))
        val entry = assertNotNull(store.readMetadata()[id])
        assertEquals("work", entry.folder)
        assertEquals("q3-plans_05.31.26_1407.md", entry.name)
        assertEquals(NoteStore.sha256(content), entry.hash)
        assertEquals(42L, entry.modifiedAtMillis)
        assertEquals("Q3 plans", entry.title)
        assertEquals("2026-05-31", entry.date)
        assertEquals(NoteStems(NoteStore.sha256(content), setOf("budget", "meet", "tuesdai"), setOf("shop", "errand")), store.readStems(strict = true)[id])
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theNoteFileHoldsItsFolderNameTitleDateTimeAndContent() = runBlocking {
        val id = store.createNote("work", "plan.md", "the body", title = "Plan", date = "2026-05-31", modifiedAtMillis = 7L)
        assertEquals(NoteRecord(NoteRecord.VERSION, "work", "plan.md", "Plan", "2026-05-31", 7L, "the body"), record(id))
    }

    @Test
    fun theDirectoryNamesNoFolderNameOrTitle() = runBlocking {
        store.createNote("private-matters", "divorce-lawyer-call_09.22.26_1700.md", "call them", title = "Divorce lawyer call")
        val paths = root.walk().map { it.relativeTo(root).path }.toList()
        assertTrue(paths.none { it.contains("divorce") || it.contains("lawyer") || it.contains("private") }, paths.joinToString())
        val notes = File(root, NoteStore.NOTES_DIR).listFiles().orEmpty()
        assertEquals(1, notes.size)
        assertTrue(isValidNoteId(notes.single().name))
    }

    @Test
    fun blankTitleAndDateAreStoredAsAbsent() = runBlocking {
        val id = store.createNote("work", "a.md", "body", title = " ", date = "")
        val entry = store.readMetadata().getValue(id)
        assertNull(entry.title)
        assertNull(entry.date)
        assertNull(record(id).title)
    }

    @Test
    fun updateNoteReplacesContentTitleAndDateAndKeepsTheFolderAndName() = runBlocking {
        val id = store.createNote("work", "a.md", "old", title = "Old", date = "2026-01-01", modifiedAtMillis = 1L)

        assertTrue(store.updateNote(id, "new", title = "New", date = "2026-02-02", modifiedAtMillis = 2L))

        assertEquals("new", store.readNote(id))
        val entry = store.readMetadata().getValue(id)
        assertEquals(listOf("work", "a.md", "New", "2026-02-02"), listOf(entry.folder, entry.name, entry.title, entry.date))
        assertEquals(2L, entry.modifiedAtMillis)
        assertEquals(NoteRecord(NoteRecord.VERSION, "work", "a.md", "New", "2026-02-02", 2L, "new"), record(id))

        val indexBytes = metaFile.readBytes()
        assertFalse(store.updateNote(NoteStore.randomNoteId(), "ghost"))
        assertContentEquals(indexBytes, metaFile.readBytes())
        assertEquals(1, File(root, NoteStore.NOTES_DIR).listFiles().orEmpty().size)
    }

    @Test
    fun onlyNoteIdsValidFoldersAndPlainNamesAreAccepted() = runBlocking {
        val badIds = listOf("../notes_meta", "", "work/a.md", "0123456789ABCDEF0123456789ABCDEF", "0123456789abcdef0123456789abcde", "${"a".repeat(32)}/x")
        for (bad in badIds) {
            assertFailsWith<IllegalArgumentException>(bad) { store.readNote(bad) }
            assertFailsWith<IllegalArgumentException>(bad) { store.updateNote(bad, "body") }
            assertFailsWith<IllegalArgumentException>(bad) { store.deleteNote(bad) }
            assertFailsWith<IllegalArgumentException>(bad) { store.moveNote(bad, "work") }
        }
        val badFolders = listOf("../work", "work/sub", "WORK", "Work", "work.", "archive ", "café", "-lead", "")
        for (bad in badFolders) {
            assertFailsWith<IllegalArgumentException>(bad) { store.createNote(bad, "a.md", "body") }
            assertFailsWith<IllegalArgumentException>(bad) { store.moveNote(NoteStore.randomNoteId(), bad) }
        }
        val badNames = listOf("", ".hidden.md", "café.md", "sp ace.md", "../x.md", "a/b.md", "${"a".repeat(NotePaths.MAX_NAME_CHARS + 1)}.md")
        for (bad in badNames) assertFailsWith<IllegalArgumentException>(bad) { store.createNote("work", bad, "body") }
        assertFalse(metaFile.exists())
        assertFalse(File(root, NoteStore.NOTES_DIR).exists())
    }

    @Test
    fun stemsFollowTheFolder() = runBlocking {
        val content = md("t", listOf("shopping"), "important content")
        assertNull(stemsAfterCreate("archive", content))
        val indexed = assertNotNull(stemsAfterCreate("unclassified", content))
        assertEquals(setOf("import", "content"), indexed.tokens)
        assertEquals(setOf("shop"), indexed.tags)
    }

    @Test
    fun stemsLiveInTheStemsFileOfTheIdsFirstHexDigitAndTheIndexCarriesNone() = runBlocking {
        val ids = (1..40).map { store.createNote("work", "n$it.md", "word$it body") }
        val stemsDir = File(root, NoteStore.STEMS_DIR)
        val shards = stemsDir.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(ids.map { it[0].toString() }.toSet(), shards)
        for (shard in shards) {
            val held = NotebookJson.decodeFromString<NoteStemsFile>(files.readText(File(stemsDir, shard))!!).notes.keys
            assertEquals(ids.filter { it[0].toString() == shard }.toSet(), held, shard)
        }
        val indexJson = files.readText(metaFile)!!
        assertFalse(indexJson.contains("tokens") || indexJson.contains("body"), "the index carries stems")
    }

    @Test
    fun deletingAndUpdatingANoteKeepItsStemsCurrent() = runBlocking {
        val id = store.createNote("work", "a.md", "first words")
        store.updateNote(id, "second words")
        assertEquals(NoteStems(NoteStore.sha256("second words"), setOf("second", "word")), store.readStems(strict = true)[id])
        store.deleteNote(id)
        assertNull(store.readStems(strict = true)[id])
    }

    @Test
    fun aStemsFileThatCannotBeWrittenThisTimeDoesNotFailTheNoteWrite() = runBlocking {
        fake.failSealWhenAadContains = NoteStore.STEMS_DIR
        val id = store.createNote("work", "a.md", "body words")
        fake.failSealWhenAadContains = null

        assertEquals("body words", store.readNote(id))
        assertEquals(setOf(id), store.readMetadata(strict = true).keys)
        assertNull(store.readStems(strict = true)[id])
        store.updateNote(id, "body words again")
        assertEquals(NoteStore.sha256("body words again"), store.readStems(strict = true)[id]?.hash)
    }

    @Test
    fun aCorruptStemsFileIsSetAsideAndStartedAgainByTheNextWrite() = runBlocking {
        val first = store.createNote("work", "a.md", "alpha")
        val shard = File(root, "${NoteStore.STEMS_DIR}/${first[0]}")
        corrupt(shard)
        assertFailsWith<CorruptFileException> { store.readStems(strict = true) }
        assertTrue(store.readStems().isEmpty())

        store.updateNote(first, "beta")

        assertEquals(setOf("beta"), store.readStems(strict = true).getValue(first).tokens)
        assertEquals(listOf("${NoteStore.STEMS_DIR}__${first[0]}.$now"), quarantined())
    }

    @Test
    fun frontMatterIsNotTokenizedAndTagsComeOnlyFromIt() = runBlocking {
        val withTags = "---\ntitle: my title\ntags: [secret_token, agreed]\n---\n\njust the body content"
        val id = store.createNote("notes", "x.md", withTags)
        val stems = store.readStems(strict = true).getValue(id)
        assertEquals(setOf("just", "bodi", "content"), stems.tokens)
        assertEquals(setOf("secret", "token", "agre"), stems.tags)

        // An edit that rewrites the same note keeps the stems it had: they come from the display tags.
        store.updateNote(id, "$withTags edited")
        assertEquals(setOf("secret", "token", "agre"), store.readStems(strict = true).getValue(id).tags)

        assertNull(stemsAfterCreate("y", "---\ntitle: t\n---\n\nbody")!!.tags)
        assertEquals(emptySet(), stemsAfterCreate("z", md("t", emptyList(), "body"))!!.tags)
        assertNull(stemsAfterCreate("w", "no front matter")!!.tags)
    }

    @Test
    fun createNoteTakesTheFirstFreeNameAndReusesANameWithTheSameContent() = runBlocking {
        val name = "todo_05.31.26_1407.md"
        val a = store.createNote("work", name, "A", title = "Todo")
        val b = store.createNote("work", name, "B")
        assertEquals(a, store.createNote("work", name, "A"))
        val c = store.createNote("work", name, "C")
        assertEquals(b, store.createNote("work", name, "B"))

        val names = store.readMetadata().mapValues { it.value.name }
        assertEquals(mapOf(a to name, b to "todo-2_05.31.26_1407.md", c to "todo-3_05.31.26_1407.md"), names)
        assertEquals(listOf("A", "B", "C"), listOf(a, b, c).map { store.readNote(it) })
        assertEquals(3, store.folderCount("work"))

        val plain = store.createNote("unclassified", "plain.md", "x")
        val plain2 = store.createNote("unclassified", "plain.md", "y")
        assertEquals(listOf("plain.md", "plain-2.md"), listOf(plain, plain2).map { store.readMetadata().getValue(it).name })
        val elsewhere = store.createNote("home", name, "B")
        assertNotEquals(b, elsewhere)
        assertEquals(name, store.readMetadata().getValue(elsewhere).name)
    }

    @Test
    fun moveNoteKeepsTheIdTheFileAndEveryFieldAndReindexesForTheDestination() = runBlocking {
        val content = md("Title Here", listOf("alpha", "beta"), "body content with searchable words")
        val id = store.createNote("unclassified", "note.md", content, title = "Title Here", date = "2026-04-15", modifiedAtMillis = 7L)
        val before = store.readMetadata().getValue(id)

        assertEquals("note.md", store.moveNote(id, "notes-other"))

        assertEquals(before.copy(folder = "notes-other"), store.readMetadata().getValue(id))
        assertEquals(content, store.readNote(id))
        assertEquals(NoteRecord(NoteRecord.VERSION, "notes-other", "note.md", "Title Here", "2026-04-15", 7L, content), record(id))
        assertEquals(listOf(id), File(root, NoteStore.NOTES_DIR).listFiles().orEmpty().map { it.name })
    }

    @Test
    fun moveNoteIntoAndOutOfABlocklistedFolderDropsAndRebuildsItsStems() = runBlocking {
        val content = "---\ntitle: \"t\"\ntags: [\"shopping\", \"errands\"]\n---\n\nimportant content"
        val id = store.createNote("unclassified", "x.md", content)
        assertNotNull(store.readStems(strict = true)[id])

        store.moveNote(id, "archive")
        assertNull(store.readStems(strict = true)[id])

        store.moveNote(id, "unclassified")
        val restored = store.readStems(strict = true).getValue(id)
        assertEquals(setOf("import", "content"), restored.tokens)
        assertEquals(setOf("shop", "errand"), restored.tags)
    }

    @Test
    fun moveNoteSuffixesANameTakenInTheDestination() = runBlocking {
        val occupant = store.createNote("unclassified", "b.md", "occupant body", title = "Occupant")
        val mover = store.createNote("work", "b.md", "mover body", title = "Mover")

        assertEquals("b-2.md", store.moveNote(mover, "unclassified"))

        assertEquals("b.md", store.readMetadata().getValue(occupant).name)
        assertEquals("b-2.md", store.readMetadata().getValue(mover).name)
        assertEquals("b-2.md", record(mover).name)
        assertEquals("occupant body", store.readNote(occupant))
        assertEquals("mover body", store.readNote(mover))

        store.createNote("home", "n_05.31.26_1407.md", "home")
        val work = store.createNote("work", "n_05.31.26_1407.md", "work")
        assertEquals("n-2_05.31.26_1407.md", store.moveNote(work, "home"))
    }

    @Test
    fun moveNoteToItsOwnFolderAndOfAnUnlistedIdChangeNothing() = runBlocking {
        val id = store.createNote("work", "a.md", "body", title = "A")
        val before = store.readMetadata()
        val indexBytes = metaFile.readBytes()

        assertEquals("a.md", store.moveNote(id, "work"))
        assertNull(store.moveNote(NoteStore.randomNoteId(), "home"))

        assertEquals(before, store.readMetadata())
        assertContentEquals(indexBytes, metaFile.readBytes())
        assertEquals("body", store.readNote(id))
    }

    @Test
    fun deleteNoteRemovesTheFileAndTheEntry() = runBlocking {
        val id = store.createNote("unclassified", "d.md", "body")
        store.deleteNote(id)
        assertTrue(store.readMetadata().isEmpty())
        assertNull(store.readNote(id))
        assertFalse(noteFile(id).exists())

        val indexBytes = metaFile.readBytes()
        store.deleteNote(NoteStore.randomNoteId())
        assertContentEquals(indexBytes, metaFile.readBytes())
    }

    @Test
    fun aFailedIndexWriteDuringADeleteLeavesTheEntryWithoutItsFile() = runBlocking {
        val id = store.createNote("work", "a.md", "body")

        fake.failSealWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.deleteNote(id) }
        fake.failSealWhenAadContains = null

        assertFalse(noteFile(id).exists())
        assertEquals(setOf(id), store.readMetadata().keys)
        assertNull(store.readNote(id))
        assertEquals(ReconcileResult(), store.reconcile())
        store.ensureFolder("work")
        assertEquals(DeleteFolderResult.Deleted(1), store.deleteFolder("work"))
        assertEquals("unclassified", store.readMetadata().getValue(id).folder)
        store.deleteNote(id)
        assertTrue(store.readMetadata().isEmpty())
    }

    @Test
    fun aTransientIndexReadAbortsBeforeAnythingIsWritten() = runBlocking {
        val id = store.createNote("f", "n.md", "OLD", title = "T")
        val entryBefore = store.readMetadata().getValue(id)

        store.forgetCachedIndexForTest()
        fake.failOpenWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.updateNote(id, "NEW", title = "T") }
        assertFailsWith<TransientCryptoException> { store.createNote("f", "m.md", "NEW") }
        assertFailsWith<TransientCryptoException> { store.deleteNote(id) }
        assertFailsWith<TransientCryptoException> { store.moveNote(id, "g") }
        assertFailsWith<TransientCryptoException> { store.reconcile() }
        fake.failOpenWhenAadContains = null

        assertEquals("OLD", store.readNote(id))
        assertEquals(entryBefore, store.readMetadata().getValue(id))
        assertEquals(listOf(id), File(root, NoteStore.NOTES_DIR).listFiles().orEmpty().map { it.name })
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun aTransientNoteReadAbortsAMoveInsteadOfReportingItAbsent() = runBlocking {
        val id = store.createNote("f", "n.md", "OLD")
        fake.failOpenWhenAadContains = "notes/$id"
        assertFailsWith<TransientCryptoException> { store.moveNote(id, "g") }
        fake.failOpenWhenAadContains = null
        assertEquals("f", store.readMetadata().getValue(id).folder)
        assertEquals("f", record(id).folder)
    }

    @Test
    fun aMissingKeyAbortsEveryMutatorAndLeavesTheFilesInPlace() = runBlocking {
        val id = store.createNote("f", "n.md", "OLD", title = "T")
        store.ensureFolder("f")
        store.updateFolderTags { mapOf("f" to listOf("tag")) }
        val names = listOf(NoteStore.META_FILE, NoteStore.FOLDERS_FILE, NoteStore.FOLDER_TAGS_FILE, "${NoteStore.NOTES_DIR}/$id")
        val bytes = names.map { File(root, it).readBytes() }

        store.forgetCachedIndexForTest()
        fake.failOpenWithKeyUnavailable = true
        assertFailsWith<KeyUnavailableException> { store.updateNote(id, "NEW") }
        assertFailsWith<KeyUnavailableException> { store.createNote("f", "m.md", "NEW") }
        assertFailsWith<KeyUnavailableException> { store.deleteNote(id) }
        assertFailsWith<KeyUnavailableException> { store.moveNote(id, "g") }
        assertFailsWith<KeyUnavailableException> { store.reconcile() }
        assertFailsWith<KeyUnavailableException> { store.updateFolders { it + "x" } }
        assertFailsWith<KeyUnavailableException> { store.ensureDefaultFolders() }
        assertFailsWith<KeyUnavailableException> { store.updateFolderTags { emptyMap() } }
        assertFailsWith<KeyUnavailableException> { store.pruneOrphanFolderTags() }
        assertFailsWith<KeyUnavailableException> { store.deleteFolder("f") }
        assertFailsWith<KeyUnavailableException> { store.readNote(id, strict = true) }
        assertFailsWith<KeyUnavailableException> { store.readMetadata(strict = true) }
        assertNull(store.readNote(id))
        assertTrue(store.readMetadata().isEmpty())
        assertEquals(listOf("unclassified"), store.readFolders())
        assertTrue(store.readFolderTags().isEmpty())
        fake.failOpenWithKeyUnavailable = false

        val after = names.map { File(root, it).readBytes() }
        bytes.zip(after).forEach { (b, a) -> assertContentEquals(b, a) }
        assertEquals("OLD", store.readNote(id))
        assertEquals(1, File(root, NoteStore.NOTES_DIR).listFiles().orEmpty().size)
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun authenticButUnparseableStoreFilesAbortMutatorsAndReadAsEmpty() = runBlocking {
        for (name in listOf(NoteStore.META_FILE, NoteStore.FOLDERS_FILE, NoteStore.FOLDER_TAGS_FILE)) {
            files.writeText(File(root, name), "definitely not json")
        }
        val seeded = listOf(NoteStore.META_FILE, NoteStore.FOLDERS_FILE, NoteStore.FOLDER_TAGS_FILE).map { File(root, it).readBytes() }

        assertFailsWith<PersistedFormatException> { store.createNote("unclassified", "x.md", "body") }
        assertFailsWith<PersistedFormatException> { store.updateNote(NoteStore.randomNoteId(), "body") }
        assertFailsWith<PersistedFormatException> { store.deleteNote(NoteStore.randomNoteId()) }
        assertFailsWith<PersistedFormatException> { store.ensureFolder("newfolder") }
        assertFailsWith<PersistedFormatException> { store.updateFolderTags { it + ("a" to listOf("b")) } }
        assertFailsWith<PersistedFormatException> { store.pruneOrphanFolderTags() }
        assertFailsWith<PersistedFormatException> { store.readMetadata(strict = true) }
        assertFailsWith<PersistedFormatException> { store.readFolders(strict = true) }
        assertFailsWith<PersistedFormatException> { store.readFolderTags(strict = true) }
        assertFalse(File(root, NoteStore.NOTES_DIR).exists())

        assertTrue(store.readMetadata().isEmpty())
        assertEquals(listOf("unclassified"), store.readFolders())
        assertTrue(store.readFolderTags().isEmpty())
        assertTrue(store.listNoteItems("unclassified").isEmpty())

        val after = listOf(NoteStore.META_FILE, NoteStore.FOLDERS_FILE, NoteStore.FOLDER_TAGS_FILE).map { File(root, it).readBytes() }
        seeded.zip(after).forEach { (b, a) -> assertContentEquals(b, a) }
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun anIndexEntryWithAnInvalidIdFolderOrNameIsAFormatFailure() = runBlocking<Unit> {
        val good = NoteMetadata("work", "a.md", "h", 1L)
        for (notes in listOf(mapOf("not-an-id" to good), mapOf(NoteStore.randomNoteId() to good.copy(folder = "../x")), mapOf(NoteStore.randomNoteId() to good.copy(name = "a/b.md")))) {
            files.writeText(metaFile, NotebookJson.encodeToString(NoteIndexFile(NoteIndexFile.VERSION, notes)))
            assertFailsWith<PersistedFormatException> { store.readMetadata(strict = true) }
        }
        files.writeText(metaFile, NotebookJson.encodeToString(NoteIndexFile(2, emptyMap())))
        assertFailsWith<PersistedFormatException> { store.readMetadata(strict = true) }
    }

    @Test
    fun aCorruptNoteIsQuarantinedWhileItsEntryStaysAndMovesUntilDeleted() = runBlocking {
        val id = store.createNote("work", "bad.md", "body", title = "Bad")
        noteFile(id).writeBytes(byteArrayOf(0, 1, 2, 3))

        assertNull(store.readNote(id))
        assertEquals(listOf("notes__$id.$now"), quarantined())
        assertEquals(setOf(id), store.readMetadata().keys)
        assertEquals("bad.md", store.moveNote(id, "home"))
        assertEquals("home", store.readMetadata().getValue(id).folder)

        store.deleteNote(id)
        assertTrue(store.readMetadata().isEmpty())
    }

    @Test
    fun listNoteItemsIsNewestFirstWithNameAndAbsentDateFallbacks() = runBlocking {
        val older = store.createNote("work", "older_05.31.26_1407.md", "a", title = "Older", date = "2026-05-31", modifiedAtMillis = 10L)
        val newer = store.createNote("work", "newer.md", "b", modifiedAtMillis = 20L)
        store.createNote("workshop", "other.md", "c", modifiedAtMillis = 30L)

        val items = store.listNoteItems("work")
        assertEquals(NoteItem(newer, "work", "newer.md", "newer", null, 20L), items[0])
        assertEquals(NoteItem(older, "work", "older_05.31.26_1407.md", "Older", "2026-05-31", 10L), items[1])
        assertEquals(2, items.size)
        assertEquals(2, store.folderCount("work"))
        assertEquals(1, store.folderCount("workshop"))
        assertEquals(0, store.folderCount("wor"))
    }

    @Test
    fun createNoteReuseKeepsTheExistingEntryAndRestoresAMissingFile() = runBlocking {
        val id = store.createNote("work", "todo.md", "A", title = "Todo", date = "2026-05-31", modifiedAtMillis = 5L)
        val entry = store.readMetadata().getValue(id)
        val indexBytes = metaFile.readBytes()

        now += 1_000
        assertEquals(id, store.createNote("work", "todo.md", "A"))
        assertEquals(entry, store.readMetadata().getValue(id))
        assertContentEquals(indexBytes, metaFile.readBytes())

        noteFile(id).delete()
        assertEquals(id, store.createNote("work", "todo.md", "A"))
        assertEquals("A", store.readNote(id))
        assertEquals(NoteRecord(NoteRecord.VERSION, "work", "todo.md", "Todo", "2026-05-31", 5L, "A"), record(id))
        assertEquals(entry, store.readMetadata().getValue(id))
    }

    @Test
    fun aReusedNoteThatCannotBeReadAbortsCreateNoteAndIsNotRewritten() = runBlocking {
        val id = store.createNote("work", "todo.md", "A")
        val before = noteFile(id).readBytes()
        fake.failOpenWhenAadContains = "notes/$id"
        assertFailsWith<TransientCryptoException> { store.createNote("work", "todo.md", "A") }
        fake.failOpenWhenAadContains = null
        fake.keyUnavailableWhenAadContains = "notes/$id"
        assertFailsWith<KeyUnavailableException> { store.createNote("work", "todo.md", "A") }
        fake.keyUnavailableWhenAadContains = null
        assertContentEquals(before, noteFile(id).readBytes())
    }

    @Test
    fun createNoteReuseRewritesOnlyAFileThatDoesNotOpen() = runBlocking {
        val id = store.createNote("work", "todo.md", "A", title = "Todo", date = "2026-05-31", modifiedAtMillis = 5L)
        val written = NoteRecord(NoteRecord.VERSION, "work", "todo.md", "Todo", "2026-05-31", 5L, "A")

        for (damage in listOf<(File) -> Unit>({ corrupt(it) }, { files.writeText(it, "not a record") })) {
            damage(noteFile(id))
            assertEquals(id, store.createNote("work", "todo.md", "A"))
            assertEquals(written, record(id))
        }
        assertEquals(emptyList(), quarantined())

        writeRecord(id, "work", "todo.md", "B", modifiedAtMillis = 5L)
        val other = noteFile(id).readBytes()
        assertEquals(id, store.createNote("work", "todo.md", "A"))
        assertContentEquals(other, noteFile(id).readBytes())
    }

    @Test
    fun aFailedIndexWriteDuringAMoveLeavesTheNoteListedWhereItWas() = runBlocking {
        val id = store.createNote("work", "a.md", "body", title = "A")
        val entry = store.readMetadata().getValue(id)

        fake.failSealWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.moveNote(id, "home") }
        fake.failSealWhenAadContains = null

        assertEquals(mapOf(id to entry), store.readMetadata())
        assertEquals("body", store.readNote(id))
        assertEquals("a.md", store.moveNote(id, "home"))
        assertEquals("home", store.readMetadata().getValue(id).folder)
    }

    @Test
    fun aCorruptStoreFileFailsEveryMutatorClosedAndNoReadQuarantinesIt() = runBlocking {
        val id = store.createNote("work", "a.md", "body")
        store.ensureFolder("work")
        store.updateFolderTags { mapOf("work" to listOf("t")) }
        val names = listOf(NoteStore.META_FILE, NoteStore.FOLDERS_FILE, NoteStore.FOLDER_TAGS_FILE)
        val corrupted = names.map { name -> File(root, name).also(::corrupt).readBytes() }
        store.forgetCachedIndexForTest()

        assertFailsWith<CorruptFileException> { store.updateNote(id, "new") }
        assertFailsWith<CorruptFileException> { store.createNote("work", "b.md", "new") }
        assertFailsWith<CorruptFileException> { store.deleteNote(id) }
        assertFailsWith<CorruptFileException> { store.moveNote(id, "home") }
        assertFailsWith<CorruptFileException> { store.ensureFolder("x") }
        assertFailsWith<CorruptFileException> { store.ensureDefaultFolders() }
        assertFailsWith<CorruptFileException> { store.updateFolderTags { emptyMap() } }
        assertFailsWith<CorruptFileException> { store.pruneOrphanFolderTags() }
        assertFailsWith<CorruptFileException> { store.deleteFolder("work") }
        assertFailsWith<CorruptFileException> { store.readMetadata(strict = true) }
        assertFailsWith<CorruptFileException> { store.readFolders(strict = true) }
        assertFailsWith<CorruptFileException> { store.readFolderTags(strict = true) }

        assertTrue(store.readMetadata().isEmpty())
        assertEquals(listOf("unclassified"), store.readFolders())
        assertTrue(store.readFolderTags().isEmpty())
        assertEquals("body", store.readNote(id))
        assertEquals(listOf(id), File(root, NoteStore.NOTES_DIR).listFiles().orEmpty().map { it.name })
        names.zip(corrupted).forEach { (name, bytes) -> assertContentEquals(bytes, File(root, name).readBytes(), name) }
        assertEquals(emptyList(), quarantined())
    }

    // The index in memory

    @Test
    fun onceReadOrWrittenTheIndexIsServedFromMemoryToEveryStoreOnTheRoot() = runBlocking {
        val id = store.createNote("work", "a.md", "body")
        fake.failOpenWhenAadContains = NoteStore.META_FILE
        assertEquals(setOf(id), store.readMetadata(strict = true).keys)
        assertEquals(setOf(id), NoteStore(root, fake) { now }.readMetadata(strict = true).keys)
        val second = store.createNote("work", "b.md", "more")
        assertEquals(setOf(id, second), store.listNoteItems("work").map { it.id }.toSet())
        fake.failOpenWhenAadContains = null

        store.forgetCachedIndexForTest()
        assertEquals(setOf(id, second), store.readMetadata(strict = true).keys)
    }

    @Test
    fun aReadThatDegradesToEmptyIsNotRemembered() = runBlocking<Unit> {
        val id = store.createNote("work", "a.md", "body")
        store.forgetCachedIndexForTest()
        fake.failOpenWhenAadContains = NoteStore.META_FILE
        assertTrue(store.readMetadata().isEmpty())
        fake.failOpenWhenAadContains = null
        assertEquals(setOf(id), store.readMetadata().keys)

        store.forgetCachedIndexForTest()
        files.writeText(metaFile, "not the index")
        assertTrue(store.readMetadata().isEmpty())
        assertFailsWith<PersistedFormatException> { store.readMetadata(strict = true) }
    }

    @Test
    fun aFailedIndexWriteLeavesTheIndexInMemoryAsOnDisk() = runBlocking {
        val id = store.createNote("work", "a.md", "body")
        fake.failSealWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.createNote("work", "b.md", "never listed") }
        fake.failSealWhenAadContains = null
        val inMemory = store.readMetadata()
        store.forgetCachedIndexForTest()
        assertEquals(store.readMetadata(strict = true), inMemory)
        assertEquals(setOf(id), inMemory.keys)
    }

    // Review follow-ups

    private val longName = "a".repeat(NotePaths.MAX_NAME_CHARS - 3) + ".md"

    @Test
    fun aSuffixedNameNeverPassesTheNameRule() = runBlocking {
        val home = store.createNote("home", longName, "home")
        store.createNote("work", longName, "work")

        assertFailsWith<IllegalStateException> { store.moveNote(home, "work") }
        assertFailsWith<IllegalStateException> { store.createNote("work", longName, "other") }

        store.forgetCachedIndexForTest()
        assertEquals(setOf("home", "work"), store.readMetadata(strict = true).values.map { it.folder }.toSet())
        assertEquals(ReconcileResult(), store.reconcile())
    }

    @Test
    fun aRebuildLeavesInPlaceANoteWithNoFreeName() = runBlocking {
        val first = "1".repeat(32)
        val second = "2".repeat(32)
        writeRecord(first, "work", longName, "one", modifiedAtMillis = 1L)
        writeRecord(second, "work", longName, "two", modifiedAtMillis = 2L)

        assertEquals(ReconcileResult(rebuilt = true, listed = 1, leftInPlace = 1), store.reconcile())

        assertEquals(setOf(first), store.readMetadata(strict = true).keys)
        assertTrue(noteFile(second).exists())
    }

    @Test
    fun reconcileKeepsACopyWhoseListedTwinDoesNotOpen() = runBlocking {
        val listed = store.createNote("work", "a.md", "same")
        val orphan = NoteStore.randomNoteId()
        writeRecord(orphan, "work", "a.md", "same")
        corrupt(noteFile(listed))

        assertEquals(ReconcileResult(listed = 1), store.reconcile())

        assertEquals("a-2.md", store.readMetadata().getValue(orphan).name)
        assertEquals("same", store.readNote(orphan))
    }

    @Test
    fun movingANoteBackToItsListedFolderRewritesAFileLeftInTheOther() = runBlocking {
        val id = store.createNote("work", "a.md", "body")
        fake.failSealWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.moveNote(id, "home") }
        fake.failSealWhenAadContains = null
        assertEquals("home", record(id).folder)

        assertEquals("a.md", store.moveNote(id, "work"))

        assertEquals("work", record(id).folder)
        corrupt(metaFile)
        store.forgetCachedIndexForTest()
        store.reconcile()
        assertEquals("work", store.readMetadata().getValue(id).folder)
    }

    @Test
    fun aReaderThatLoadedAnOlderIndexNeverReplacesAWritersNewerOne() = runBlocking {
        val first = store.createNote("work", "a.md", "a")
        store.forgetCachedIndexForTest()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = AtomicBoolean(true)
        fake.beforeOpen = { location ->
            if (location == NoteStore.META_FILE && gated.compareAndSet(true, false)) {
                entered.countDown()
                release.await()
            }
        }
        val reader = thread { store.readMetadata() }
        entered.await()
        val second = store.createNote("work", "b.md", "b")
        release.countDown()
        reader.join()
        fake.beforeOpen = null

        assertEquals(setOf(first, second), store.readMetadata().keys)
    }

    @Test
    fun withTheIndexInMemoryTheNextWriteReplacesACorruptIndexFile() = runBlocking {
        val first = store.createNote("work", "a.md", "a")
        corrupt(metaFile)
        val second = store.createNote("work", "b.md", "b")
        store.forgetCachedIndexForTest()
        assertEquals(setOf(first, second), store.readMetadata(strict = true).keys)
    }

    @Test
    fun aStemsWriteFailingForAnotherReasonThanStorageEscapesWithTheNoteListed() = runBlocking {
        fake.failSealWithErrorWhenAadContains = NoteStore.STEMS_DIR
        val thrown = assertFailsWith<RuntimeException> { store.createNote("work", "a.md", "body") }
        fake.failSealWithErrorWhenAadContains = null
        assertFalse(thrown is TransientCryptoException)
        assertEquals(1, store.readMetadata(strict = true).size)
    }

    @Test
    fun aStemsWriteDropsEntriesOfNotesTheIndexDoesNotList() = runBlocking {
        val ids = ArrayDeque(listOf("a" + "1".repeat(31)))
        val seeded = NoteStore(root, fake, newId = { ids.removeFirst() }) { now }
        val stray = "a" + "2".repeat(31)
        files.writeText(File(root, "${NoteStore.STEMS_DIR}/a"), NotebookJson.encodeToString(NoteStemsFile(NoteStemsFile.VERSION, mapOf(stray to NoteStems("h", setOf("x"))))))

        val id = seeded.createNote("work", "a.md", "alpha")

        assertEquals(setOf(id), seeded.readStems(strict = true).keys)
    }

    @Test
    fun aNewNoteTakesAnIdNoNoteHas() = runBlocking {
        val taken = store.createNote("work", "a.md", "a")
        val next = "b".repeat(32)
        val ids = ArrayDeque(listOf(taken, "not-an-id", next))
        val injected = NoteStore(root, fake, newId = { ids.removeFirst() }) { now }
        assertEquals(next, injected.createNote("work", "b.md", "b"))
    }

    @Test
    fun repairStemsRecomputesMissingAndStaleEntriesAndDropsUnneededOnes() = runBlocking {
        val ids = ArrayDeque(listOf("c" + "1".repeat(31), "c" + "2".repeat(31), "c" + "3".repeat(31)))
        val injected = NoteStore(root, fake, newId = { ids.removeFirst() }) { now }
        val a = injected.createNote("work", "a.md", "alpha words")
        val b = injected.createNote("work", "b.md", "beta words")
        val archived = injected.createNote("archive", "c.md", "gamma")
        val stale = mapOf(b to NoteStems("stale", setOf("x")), archived to NoteStems("h", setOf("y")))
        files.writeText(File(root, "${NoteStore.STEMS_DIR}/c"), NotebookJson.encodeToString(NoteStemsFile(NoteStemsFile.VERSION, stale)))

        assertEquals(StemsRepair(recomputed = 2, dropped = 1), injected.repairStems())

        val stems = injected.readStems(strict = true)
        val index = injected.readMetadata()
        assertEquals(setOf(a, b), stems.keys)
        assertEquals(index.getValue(a).hash, stems.getValue(a).hash)
        assertEquals(index.getValue(b).hash, stems.getValue(b).hash)
        assertEquals(StemsRepair(0, 0), injected.repairStems())
    }

    @Test
    fun aReusedNoteGetsItsMissingStemsBack() = runBlocking<Unit> {
        val id = store.createNote("work", "a.md", "body words")
        File(root, NoteStore.STEMS_DIR).deleteRecursively()
        assertEquals(id, store.createNote("work", "a.md", "body words"))
        assertNotNull(store.readStems(strict = true)[id])
    }

    @Test
    fun aNoteThatDoesNotParseIsNotQuotedInTheLog() = runBlocking {
        val id = NoteStore.randomNoteId()
        files.writeText(noteFile(id), """{"v":1,"folder":"work","name":"a.md","content":"SECRETWORD" broken""")
        val lines = mutableListOf<String>()
        val capture = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                lines += message + " " + generateSequence(throwable) { it.cause }.joinToString { "${it.message}" }
            }
        }
        Logger.setLogWriters(capture)
        try {
            assertNull(store.readNote(id))
            val refused = assertFailsWith<PersistedFormatException> { store.readNote(id, strict = true) }
            lines += generateSequence<Throwable>(refused) { it.cause }.joinToString { "${it.message}" }
            store.reconcile()
        } finally {
            Logger.setLogWriters(platformLogWriter())
        }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { it.contains("SECRETWORD") }, lines.joinToString("\n"))
    }

    // Reconcile

    private suspend fun threeNotes(): Map<String, NoteMetadata> {
        store.createNote("work", "plan_05.31.26_1407.md", md("Plan", listOf("budget"), "the plan"), title = "Plan", date = "2026-05-31", modifiedAtMillis = 3L)
        store.createNote("archive", "old.md", "archived words", modifiedAtMillis = 1L)
        store.createNote("home", "list.md", "milk eggs", title = "List", modifiedAtMillis = 2L)
        return store.readMetadata()
    }

    @Test
    fun reconcileRebuildsACorruptIndexFromTheNoteFilesAndSetsTheOldOneAside() = runBlocking {
        val expected = threeNotes()
        corrupt(metaFile)
        store.forgetCachedIndexForTest()

        val stems = store.readStems(strict = true)
        File(root, NoteStore.STEMS_DIR).deleteRecursively()

        assertEquals(ReconcileResult(rebuilt = true, listed = 3), store.reconcile())

        assertEquals(expected, store.readMetadata(strict = true))
        assertEquals(stems, store.readStems(strict = true))
        assertEquals(listOf("${NoteStore.META_FILE}.$now"), quarantined())
    }

    @Test
    fun reconcileRebuildsAnUnparseableIndexAndAnAbsentOneWhileNoteFilesExist() = runBlocking {
        val expected = threeNotes()
        files.writeText(metaFile, "not the index")
        store.forgetCachedIndexForTest()
        assertEquals(ReconcileResult(rebuilt = true, listed = 3), store.reconcile())
        assertEquals(expected, store.readMetadata(strict = true))
        assertEquals(1, quarantined().size)

        metaFile.delete()
        assertEquals(ReconcileResult(rebuilt = true, listed = 3), store.reconcile())
        assertEquals(expected, store.readMetadata(strict = true))
    }

    @Test
    fun reconcileOfAConsistentOrEmptyNotebookWritesNothing() = runBlocking {
        assertEquals(ReconcileResult(), store.reconcile())
        assertFalse(metaFile.exists())

        threeNotes()
        val indexBytes = metaFile.readBytes()
        File(root, "${NoteStore.NOTES_DIR}/README").writeText("not a note")
        assertEquals(ReconcileResult(), store.reconcile())
        assertContentEquals(indexBytes, metaFile.readBytes())
        assertEquals("not a note", File(root, "${NoteStore.NOTES_DIR}/README").readText())
    }

    @Test
    fun reconcileListsANoteWhoseIndexWriteNeverHappened() = runBlocking {
        val listed = store.createNote("work", "kept.md", "kept")
        fake.failSealWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.createNote("work", "lost.md", "lost body", title = "Lost", modifiedAtMillis = 4L) }
        fake.failSealWhenAadContains = null
        val orphan = File(root, NoteStore.NOTES_DIR).listFiles().orEmpty().map { it.name }.single { it != listed }
        assertEquals(setOf(listed), store.readMetadata().keys)

        assertEquals(ReconcileResult(listed = 1), store.reconcile())

        val entry = store.readMetadata().getValue(orphan)
        assertEquals(listOf("work", "lost.md", "Lost"), listOf(entry.folder, entry.name, entry.title))
        assertEquals(4L, entry.modifiedAtMillis)
        assertEquals("lost body", store.readNote(orphan))
    }

    @Test
    fun reconcileDeletesALeftoverCopyOfAListedNote() = runBlocking {
        fake.failSealWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.createNote("work", "a.md", "same") }
        fake.failSealWhenAadContains = null
        val listed = store.createNote("work", "a.md", "same")

        assertEquals(ReconcileResult(dropped = 1), store.reconcile())

        assertEquals(setOf(listed), store.readMetadata().keys)
        assertEquals(listOf(listed), File(root, NoteStore.NOTES_DIR).listFiles().orEmpty().map { it.name })
    }

    @Test
    fun reconcileKeepsACopyWhoseListedTwinLostItsFile() = runBlocking {
        val listed = store.createNote("work", "a.md", "same")
        val orphan = NoteStore.randomNoteId()
        writeRecord(orphan, "work", "a.md", "same")
        noteFile(listed).delete()

        assertEquals(ReconcileResult(listed = 1), store.reconcile())

        assertEquals("a-2.md", store.readMetadata().getValue(orphan).name)
        assertEquals("same", store.readNote(orphan))
    }

    @Test
    fun reconcileListsAnUnlistedNoteUnderAFreeNameAndRewritesItsFile() = runBlocking {
        val occupant = store.createNote("work", "a.md", "occupant")
        val orphan = NoteStore.randomNoteId()
        writeRecord(orphan, "work", "a.md", "different")

        assertEquals(ReconcileResult(listed = 1), store.reconcile())

        assertEquals("a.md", store.readMetadata().getValue(occupant).name)
        assertEquals("a-2.md", store.readMetadata().getValue(orphan).name)
        assertEquals("a-2.md", record(orphan).name)
    }

    @Test
    fun aRebuildGivesTwoNotesClaimingOneNameDistinctNamesTheOlderKeepingIt() = runBlocking {
        val first = "f".repeat(32)
        val second = "0".repeat(32)
        writeRecord(first, "work", "a.md", "one", modifiedAtMillis = 1L)
        writeRecord(second, "work", "a.md", "two", modifiedAtMillis = 2L)

        assertEquals(ReconcileResult(rebuilt = true, listed = 2), store.reconcile())

        val names = store.readMetadata().mapValues { it.value.name }
        assertEquals(mapOf(first to "a.md", second to "a-2.md"), names)
        assertEquals("a-2.md", record(second).name)
    }

    @Test
    fun reconcileQuarantinesNoteFilesThatFailToOpenAndLeavesUnparseableOnesInPlace() = runBlocking {
        threeNotes()
        val garbage = NoteStore.randomNoteId()
        noteFile(garbage).writeBytes(ByteArray(200) { 7 })
        val unparseable = NoteStore.randomNoteId()
        files.writeText(noteFile(unparseable), "not a record")
        val invalid = NoteStore.randomNoteId()
        files.writeText(noteFile(invalid), NotebookJson.encodeToString(NoteRecord(NoteRecord.VERSION, "Bad Folder", "a.md", null, null, 1L, "x")))
        val newer = NoteStore.randomNoteId()
        files.writeText(noteFile(newer), """{"v":2,"folder":"work","name":"a.md","modifiedAtMillis":1,"content":"x"}""")
        val kept = listOf(unparseable, invalid, newer).associateWith { noteFile(it).readBytes() }

        assertEquals(ReconcileResult(setAside = 1, leftInPlace = 3), store.reconcile())

        assertEquals(3, store.readMetadata().size)
        assertEquals(listOf("notes__$garbage.$now"), quarantined())
        kept.forEach { (id, bytes) -> assertContentEquals(bytes, noteFile(id).readBytes(), id) }
    }

    @Test
    fun aTransientFailureDuringARebuildLeavesTheOldIndexAndMutatorsRefuse() = runBlocking {
        val expected = threeNotes()
        val one = expected.keys.first()
        corrupt(metaFile)
        val corrupted = metaFile.readBytes()
        store.forgetCachedIndexForTest()

        fake.failOpenWhenAadContains = "notes/$one"
        assertFailsWith<TransientCryptoException> { store.reconcile() }
        fake.failOpenWhenAadContains = null
        assertContentEquals(corrupted, metaFile.readBytes())
        assertEquals(emptyList(), quarantined())
        assertFailsWith<CorruptFileException> { store.readMetadata(strict = true) }
        assertFailsWith<CorruptFileException> { store.createNote("work", "new.md", "new") }

        assertEquals(ReconcileResult(rebuilt = true, listed = 3), store.reconcile())
        assertEquals(expected, store.readMetadata(strict = true))
    }

    @Test
    fun anAbsentIndexWhileNoteFilesExistIsNotAnEmptyNotebook() = runBlocking {
        val expected = threeNotes()
        metaFile.delete()
        store.forgetCachedIndexForTest()

        assertFailsWith<TransientCryptoException> { store.readMetadata(strict = true) }
        assertFailsWith<TransientCryptoException> { store.createNote("work", "new.md", "new") }
        assertTrue(store.readMetadata().isEmpty())
        assertEquals(ReconcileResult(rebuilt = true, listed = 3), store.reconcile())
        assertEquals(expected, store.readMetadata())
    }

    @Test
    fun reconcileLeavesAnIndexItCannotOpenThisTimeInPlace() = runBlocking {
        threeNotes()
        val indexBytes = metaFile.readBytes()
        store.forgetCachedIndexForTest()
        fake.failOpenWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.reconcile() }
        fake.failOpenWhenAadContains = null
        assertContentEquals(indexBytes, metaFile.readBytes())
        assertEquals(emptyList(), quarantined())
    }
}
