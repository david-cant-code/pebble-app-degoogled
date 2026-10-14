package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.backup.BackupStaging
import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookHousekeepingTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val now = System.currentTimeMillis()
    private val notes = NoteStore(root, crypto)
    private val queue = QueueStore(root, crypto)
    private val backupStaging = BackupStaging(root)
    private var leftoverPasses = 0
    private var leftoverPass: () -> Unit = { leftoverPasses++ }
    private val housekeeping = NotebookHousekeeping(notes, queue, backupStaging) { leftoverPass() }
    private val files = EncryptedFiles(root, crypto)
    private val audio = ByteArray(64) { it.toByte() }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun old(file: File) = file.apply { setLastModified(now - 10 * 60 * 1000L) }

    private fun staleStagingFile() = old(File(files.stagingDir.apply { mkdirs() }, "x.tmp").apply { writeBytes(audio) })

    private fun orphanBlob() = old(File(root, "${QueueStore.QUEUE_DIR}/orphan${QueueStore.BLOB_EXTENSION}").also { files.write(it, audio) })

    private fun retained(id: String) = File(root, "${QueueStore.PENDING_DIR}/$id~clip.m4a").apply { parentFile.mkdirs(); writeBytes(audio) }

    /** A note file the index does not list, as a create interrupted before its index write leaves it. */
    private fun unlistedNote(): String = NoteStore.randomNoteId().also { id ->
        files.writeText(File(root, "${NoteStore.NOTES_DIR}/$id"), NotebookJson.encodeToString(NoteRecord.serializer(), NoteRecord(NoteRecord.VERSION, "work", "a.md", null, null, 1L, "body")))
    }

    @Test
    fun atStartEveryStepRuns() = runBlocking<Unit> {
        notes.updateFolders { listOf("work") }
        queue.enqueue("listed", audio, "l.m4a", 1L)
        val staging = staleStagingFile()
        val orphan = orphanBlob()
        val kept = retained("r1")
        val note = unlistedNote()
        val leftOver = BackupStaging(root).newFile().file
        val current = backupStaging.newFile().file

        housekeeping.run(atStart = true)

        assertEquals(setOf(note), notes.readMetadata(strict = true).keys)
        assertFalse(staging.exists())
        assertFalse(leftOver.exists())
        assertTrue(current.exists())
        assertFalse(orphan.exists())
        assertFalse(kept.exists())
        assertEquals(listOf("listed", "r1"), queue.readQueue(strict = true).map { it.id })
        assertEquals(listOf(UNCLASSIFIED_FOLDER, "work"), notes.readFolders(strict = true))
    }

    @Test
    fun laterRunsLeaveOutWhatOnlyAStartNeeds() = runBlocking<Unit> {
        notes.updateFolders { listOf("work") }
        queue.enqueue("listed", audio, "l.m4a", 1L)
        val staging = staleStagingFile()
        val orphan = orphanBlob()
        retained("r1")
        unlistedNote()
        val leftOver = BackupStaging(root).newFile().file

        housekeeping.run(atStart = false)

        assertFailsWith<TransientCryptoException> { notes.readMetadata(strict = true) }
        assertTrue(staging.exists())
        assertTrue(leftOver.exists())
        assertTrue(orphan.exists())
        assertEquals(listOf("work"), notes.readFolders(strict = true))
        assertEquals(listOf("listed", "r1"), queue.readQueue(strict = true).map { it.id })
    }

    private fun setAside(at: Long) = File(files.quarantineDir.apply { mkdirs() }, "${NoteStore.META_FILE}.$at").apply { writeBytes(audio) }

    @Test
    fun aFailedLeftoverPassLeavesTheSetAsideSweepToRun() = runBlocking<Unit> {
        val old = setAside(now - EncryptedFiles.QUARANTINE_MAX_AGE_MS - 60_000)
        leftoverPass = { throw IllegalStateException("injected") }

        housekeeping.run(atStart = false)

        assertFalse(old.exists())
    }

    @Test
    fun everyRunSweepsTheSetAsideFilesAndAsksForTheStartOverLeftovers() = runBlocking<Unit> {
        for (atStart in listOf(false, true)) {
            val old = setAside(now - EncryptedFiles.QUARANTINE_MAX_AGE_MS - 60_000)
            val recent = setAside(now - EncryptedFiles.QUARANTINE_MAX_AGE_MS / 2)

            housekeeping.run(atStart)

            assertFalse(old.exists(), "atStart=$atStart")
            assertTrue(recent.exists(), "atStart=$atStart")
        }
        assertEquals(2, leftoverPasses)
    }

    @Test
    fun aFailedStepLeavesTheLaterStepsToRun() = runBlocking<Unit> {
        notes.updateFolders { listOf("work") }
        queue.enqueue("listed", audio, "l.m4a", 1L)
        val orphan = orphanBlob()
        retained("r1")
        crypto.failSealWithErrorWhenAadContains = NoteStore.FOLDERS_FILE

        housekeeping.run(atStart = true)

        assertEquals(listOf("work"), notes.readFolders(strict = true))
        assertEquals(listOf("listed", "r1"), queue.readQueue(strict = true).map { it.id })
        assertFalse(orphan.exists())
    }

    @Test
    fun aDamagedRecordingsListIsRebuiltAtStartBeforeTheRetainedRecordingsAreQueued() = runBlocking<Unit> {
        queue.enqueue("listed", audio, "l.m4a", 1L)
        retained("r1")
        val meta = File(root, QueueStore.QUEUE_META_FILE)
        files.writeText(meta, "not json")

        housekeeping.run(atStart = false)
        assertFailsWith<PersistedFormatException> { queue.readQueue(strict = true) }

        housekeeping.run(atStart = true)
        assertEquals(listOf("listed", "r1"), queue.readQueue(strict = true).map { it.id })
        assertEquals(QueueStore.RECOVERED_NAME, queue.readQueue(strict = true).first().originalFileName)
    }
}
