package com.anopticlabs.gravel.notebook.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteStoreFoldersTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private val store = NoteStore(root, fake)
    private val files = EncryptedFiles(root, fake)
    private val foldersFile = File(root, NoteStore.FOLDERS_FILE)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun foldersDefaultUntilTheyArePersisted() = runBlocking {
        assertEquals(listOf("unclassified"), store.readFolders())
        assertEquals(listOf("unclassified"), store.readFolders(strict = true))
        assertFalse(foldersFile.exists())

        assertEquals(listOf("unclassified"), store.ensureDefaultFolders())
        assertFalse(foldersFile.exists())

        store.updateFolders { listOf("work") }
        assertTrue(foldersFile.exists())
        assertEquals(listOf("work"), store.readFolders())
        assertEquals(listOf("unclassified", "work"), store.ensureDefaultFolders())
        assertEquals(listOf("unclassified", "work"), store.readFolders())
        files.writeText(foldersFile, "[]")
        assertEquals(listOf("unclassified"), store.readFolders(strict = true))
    }

    @Test
    fun ensureFolderIsIdempotentAndAnUnchangedUpdateWritesNothing() = runBlocking {
        assertEquals(listOf("unclassified", "work"), store.ensureFolder("work"))
        val bytes = foldersFile.readBytes()
        assertEquals(listOf("unclassified", "work"), store.ensureFolder("work"))
        assertEquals(listOf("unclassified", "work"), store.updateFolders { it })
        assertContentEquals(bytes, foldersFile.readBytes())
    }

    @Test
    fun folderItemsPutUnclassifiedFirstAndTheArchiveLastWithCounts() = runBlocking {
        store.updateFolders { listOf("work", "archive") }
        store.createNote("work", "a.md", "a")
        store.createNote("work", "b.md", "b")
        store.createNote("archive", "c.md", "c")
        store.createNote("unlisted", "d.md", "d")

        assertEquals(
            listOf(FolderItem("unclassified", 0), FolderItem("work", 2), FolderItem("unlisted", 1), FolderItem("archive", 1)),
            store.folderItems(),
        )
    }

    @Test
    fun readFoldersDropsInvalidNamesAndDuplicates() = runBlocking {
        files.writeText(foldersFile, """["work","Bad Name","work","archive","a/b","","ok_1"]""")
        assertEquals(listOf("work", "archive", "ok_1"), store.readFolders())
        assertEquals(listOf("work", "archive", "ok_1"), store.readFolders(strict = true))
        files.writeText(foldersFile, """["Bad Name"]""")
        assertEquals(listOf("unclassified"), store.readFolders())
    }

    @Test
    fun createFolderNormalizesValidatesAndSeedsTheNameTag() = runBlocking {
        assertEquals(CreateFolderResult.Created("my-work", listOf("my-work", "budget", "q3")), store.createFolder("  My Work ", "Budget, q3"))
        assertEquals(CreateFolderResult.Created("plain", listOf("plain")), store.createFolder("plain"))
        assertEquals(listOf("unclassified", "my-work", "plain"), store.readFolders())
        assertEquals(mapOf("my-work" to listOf("my-work", "budget", "q3"), "plain" to listOf("plain")), store.readFolderTags())

        assertEquals(CreateFolderResult.AlreadyExists("plain"), store.createFolder("Plain"))
        assertEquals(CreateFolderResult.InvalidName, store.createFolder("Bad!"))
        assertEquals(CreateFolderResult.InvalidName, store.createFolder(""))
        assertEquals(CreateFolderResult.InvalidName, store.createFolder("a".repeat(65)))
        for (reserved in listOf("archive", "Unclassified")) {
            assertEquals(CreateFolderResult.Reserved(reserved.lowercase()), store.createFolder(reserved))
        }
        assertEquals(listOf("unclassified", "my-work", "plain"), store.readFolders())
    }

    @Test
    fun aFolderWhoseTagsCannotBeWrittenIsReportedAsListedWithoutThem() = runBlocking {
        fake.failSealWhenAadContains = NoteStore.FOLDER_TAGS_FILE
        assertEquals(CreateFolderResult.CreatedWithoutTags("work"), store.createFolder("work", "budget"))
        fake.failSealWhenAadContains = null
        assertTrue("work" in store.readFolders(strict = true))
        assertEquals(emptyMap(), store.readFolderTags(strict = true))
        assertEquals(CreateFolderResult.AlreadyExists("work"), store.createFolder("work"))
    }

    @Test
    fun strictFolderItemsFailWhereLenientOnesShowDefaultsOrZeroCounts() = runBlocking {
        store.createFolder("work")
        store.createNote("work", "a.md", "x")
        store.forgetCachedIndexForTest()
        fake.failOpenWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { store.folderItems(strict = true) }
        assertEquals(FolderItem("work", 0), store.folderItems().first { it.name == "work" })
        fake.failOpenWhenAadContains = NoteStore.FOLDERS_FILE
        assertFailsWith<TransientCryptoException> { store.folderItems(strict = true) }
        assertEquals(listOf("unclassified", "work", "archive"), store.folderItems().map { it.name })
        fake.failOpenWhenAadContains = null
        assertEquals(FolderItem("work", 1), store.folderItems(strict = true).first { it.name == "work" })
    }

    @Test
    fun folderTagsRoundTripAndAnEntryCanBeRemoved() = runBlocking {
        assertTrue(store.readFolderTags().isEmpty())
        val written = store.updateFolderTags { it + ("work" to listOf("meeting", "project")) + ("health" to listOf("doctor")) }
        assertEquals(mapOf("work" to listOf("meeting", "project"), "health" to listOf("doctor")), written)
        assertEquals(written, store.readFolderTags())

        assertEquals(mapOf("health" to listOf("doctor")), store.updateFolderTags { it - "work" })
        assertEquals(mapOf("health" to listOf("doctor")), store.readFolderTags())
    }

    @Test
    fun pruneOrphanFolderTagsDropsUnlistedFoldersAndWritesOnlyThen() = runBlocking {
        store.updateFolders { it + "work" }
        store.updateFolderTags { mapOf("work" to listOf("meeting"), "ghost" to listOf("orphan")) }

        assertEquals(mapOf("work" to listOf("meeting")), store.pruneOrphanFolderTags())
        assertEquals(mapOf("work" to listOf("meeting")), store.readFolderTags())

        val bytes = File(root, NoteStore.FOLDER_TAGS_FILE).readBytes()
        assertEquals(mapOf("work" to listOf("meeting")), store.pruneOrphanFolderTags())
        assertContentEquals(bytes, File(root, NoteStore.FOLDER_TAGS_FILE).readBytes())
    }

    @Test
    fun pruningConcurrentWithFolderCreationKeepsEveryNewEntry() = runBlocking {
        repeat(30) { i ->
            coroutineScope {
                launch(Dispatchers.IO) { store.createFolder("race$i", "freshtag") }
                launch(Dispatchers.IO) { store.pruneOrphanFolderTags() }
            }
            assertEquals(listOf("race$i", "freshtag"), store.readFolderTags()["race$i"], "iteration $i")
        }
    }

    @Test
    fun deleteFolderMovesItsNotesToUnclassifiedThenUnlistsItAndDropsItsTags() = runBlocking {
        store.createFolder("doomed", "x")
        val a = store.createNote("doomed", "a.md", "A", title = "A")
        val b = store.createNote("doomed", "b.md", "B")
        val occupant = store.createNote("unclassified", "b.md", "occupant")

        assertEquals(DeleteFolderResult.Deleted(2), store.deleteFolder("doomed"))

        val placed = store.readMetadata().mapValues { "${it.value.folder}/${it.value.name}" }
        assertEquals(mapOf(a to "unclassified/a.md", occupant to "unclassified/b.md", b to "unclassified/b-2.md"), placed)
        assertEquals(listOf("A", "occupant", "B"), listOf(a, occupant, b).map { store.readNote(it) })
        assertEquals("A", store.readMetadata().getValue(a).title)
        assertEquals(listOf("unclassified"), store.readFolders())
        assertTrue(store.readFolderTags().isEmpty())
    }

    @Test
    fun deleteFolderKeepsTheFolderWhileANoteCannotBeReadThisTimeAndMovesACorruptOneAsAnEntry() = runBlocking {
        store.createFolder("doomed", "x")
        val a = store.createNote("doomed", "a.md", "A", modifiedAtMillis = 1L)
        val b = store.createNote("doomed", "b.md", "B", modifiedAtMillis = 2L)
        val folders = { store.readMetadata().mapValues { it.value.folder } }

        store.ensureFolder("later")
        fake.failOpenWhenAadContains = "notes/$b"
        assertEquals(DeleteFolderResult.NotesRemain(listOf(b)), store.deleteFolder("doomed"))
        fake.failOpenWhenAadContains = null
        assertEquals("unclassified", folders()[a])
        assertEquals("doomed", folders()[b])
        assertEquals(listOf("unclassified", "doomed", "later"), store.readFolders())
        assertEquals(listOf("doomed", "x"), store.readFolderTags()["doomed"])

        File(root, "${NoteStore.NOTES_DIR}/$b").writeBytes(byteArrayOf(9, 9, 9))
        assertEquals(DeleteFolderResult.Deleted(1), store.deleteFolder("doomed"))
        assertEquals(mapOf(a to "unclassified", b to "unclassified"), folders())
        assertFalse("doomed" in store.readFolders())
        assertNull(store.readFolderTags()["doomed"])
    }

    @Test
    fun aCaptureDuringAFolderDeleteLandsInUnclassifiedAndTheFolderStaysGone() = runBlocking {
        store.createFolder("doomed")
        store.createNote("doomed", "a.md", "A")
        val released = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        fake.beforeOpen = { location ->
            if (location.contains("notes/")) {
                entered.countDown()
                released.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        store.forgetCachedIndexForTest()
        store.readMetadata(strict = true)
        val deleting = launch(Dispatchers.IO) { store.deleteFolder("doomed") }
        kotlinx.coroutines.withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        val capture = async(Dispatchers.IO) { store.createListedNote("doomed", "b.md", "B") }
        released.countDown()
        deleting.join()
        fake.beforeOpen = null
        val created = capture.await()
        assertEquals("unclassified", created.folder)
        assertFalse("doomed" in store.readFolders(strict = true))
        assertTrue(store.readMetadata(strict = true).values.none { it.folder == "doomed" })
    }

    @Test
    fun aFolderDeleteWhoseCallerIsCanceledAfterTheUnlistStillFinishes() = runBlocking {
        store.createFolder("doomed", "x")
        store.createNote("doomed", "a.md", "A")
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeSeal = { location ->
            if (location.startsWith("${NoteStore.NOTES_DIR}/")) {
                holding.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val capture = async(Dispatchers.IO) { store.createListedNote("doomed", "b.md", "B") }
        withContext(Dispatchers.IO) { holding.await(5, TimeUnit.SECONDS) }
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val deleting = screen.launch { store.deleteFolder("doomed") }
        withTimeout(5_000) { while ("doomed" in store.readFolders(strict = true)) delay(5) }
        screen.cancel()
        release.countDown()
        deleting.join()
        capture.await()
        fake.beforeSeal = null

        assertFalse("doomed" in store.readFolders(strict = true))
        assertEquals(setOf("unclassified"), store.readMetadata(strict = true).values.map { it.folder }.toSet())
        assertNull(store.readFolderTags(strict = true)["doomed"])
    }

    @Test
    fun aFolderCreationWhoseCallerIsCanceledBetweenItsWritesStillGetsItsTags() = runBlocking {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeSeal = { location ->
            if (location == NoteStore.FOLDERS_FILE) {
                holding.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val creating = screen.launch { store.createFolder("fresh", "x") }
        withContext(Dispatchers.IO) { holding.await(5, TimeUnit.SECONDS) }
        screen.cancel()
        release.countDown()
        creating.join()
        fake.beforeSeal = null

        assertTrue("fresh" in store.readFolders(strict = true))
        assertEquals(listOf("fresh", "x"), store.readFolderTags(strict = true)["fresh"])
    }

    @Test
    fun aRelistThatFailsLeavesTheMoveFailureForTheCallerAndTheFolderUnlistedWithItsNote() = runBlocking {
        store.createFolder("doomed", "x")
        val a = store.createNote("doomed", "a.md", "A")
        var listWrites = 0
        fake.failSealWithErrorWhenAadContains = NoteStore.META_FILE
        fake.beforeSeal = { location ->
            if (location == NoteStore.FOLDERS_FILE && ++listWrites == 2) throw TransientCryptoException("injected relist failure", RuntimeException("injected"))
        }
        val e = assertFailsWith<RuntimeException> { store.deleteFolder("doomed") }
        fake.failSealWithErrorWhenAadContains = null
        fake.beforeSeal = null

        assertEquals(2, listWrites)
        assertEquals("injected non-transient seal failure", e.message)
        assertFalse("doomed" in store.readFolders(strict = true))
        assertEquals("doomed", store.readMetadata(strict = true).getValue(a).folder)

        assertEquals(DeleteFolderResult.Deleted(1), store.deleteFolder("doomed"))
        assertEquals("unclassified", store.readMetadata(strict = true).getValue(a).folder)
        assertNull(store.readFolderTags(strict = true)["doomed"])
    }

    @Test
    fun deleteFolderRefusesReservedFolders() = runBlocking {
        val id = store.createNote("archive", "a.md", "A")
        for (reserved in listOf("unclassified", "archive")) {
            assertEquals(DeleteFolderResult.Reserved, store.deleteFolder(reserved))
        }
        assertEquals("archive", store.readMetadata().getValue(id).folder)
    }

    @Test
    fun aFolderNamedTranscriptsIsAnOrdinaryFolder() = runBlocking {
        assertEquals(CreateFolderResult.Created("transcripts", listOf("transcripts", "x")), store.createFolder("transcripts", "x"))
        val id = store.createNote("transcripts", "a.md", "A")
        assertEquals(listOf("unclassified", "transcripts"), store.readFolders())
        assertTrue(id in store.readStems(strict = true))

        assertEquals(DeleteFolderResult.Deleted(1), store.deleteFolder("transcripts"))
        assertEquals(listOf("unclassified"), store.readFolders())
    }

    @Test
    fun aNullInsideAStoredListIsAFormatFailure() = runBlocking {
        files.writeText(File(root, NoteStore.FOLDER_TAGS_FILE), """{"work":["meeting",null]}""")
        files.writeText(foldersFile, """["work",null]""")
        assertFailsWith<PersistedFormatException> { store.readFolderTags(strict = true) }
        assertFailsWith<PersistedFormatException> { store.readFolders(strict = true) }
        assertTrue(store.readFolderTags().isEmpty())
        assertEquals(listOf("unclassified"), store.readFolders())
        assertTrue(files.quarantineDir.listFiles().orEmpty().isEmpty())
    }
}
