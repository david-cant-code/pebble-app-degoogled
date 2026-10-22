package com.anopticlabs.gravel.notebook.storage

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedFilesTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private var now = 1_700_000_000_000L
    private val files = EncryptedFiles(root, fake) { now }
    private val singleton = File(root, "notes_meta")
    private val note = File(root, "notes/work/todo.md")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aFileIsNamedInLogsByItsPathUnderTheRoot() {
        assertEquals("notes_meta", files.label(singleton))
        assertEquals("notes/work/todo.md", files.label(note))
        assertEquals("a file outside the Notebook", files.label(File(root.parentFile, "elsewhere")))
    }

    @Test
    fun locationIsThePathRelativeToTheRoot() {
        assertEquals("notes_meta", files.locationFor(singleton).decodeToString())
        assertEquals("notes/work/todo.md", files.locationFor(note).decodeToString())
        assertEquals("notes/work/todo.md", files.locationFor(File(root, "notes/./work/../work/todo.md")).decodeToString())
        assertFailsWith<IllegalArgumentException> { files.locationFor(File(root.parentFile, "elsewhere")) }
        assertFailsWith<IllegalArgumentException> { files.locationFor(root) }
    }

    @Test
    fun writeThenReadRoundTripsAndLeavesNoStagingFile() {
        files.writeText(note, "hello")
        assertEquals("hello", files.readText(note))
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
        files.writeText(note, "replaced")
        assertEquals("replaced", files.readText(note))
    }

    @Test
    fun concurrentWritersOfOneFileLeaveItReadableWithOneOfTheirPayloads() {
        val payloads = (1..8).map { "payload-$it" }
        payloads.map { payload -> thread { repeat(5) { files.writeText(note, payload) } } }.forEach { it.join() }
        val read = assertNotNull(files.readText(note))
        assertTrue(read in payloads, read)
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
        assertFalse(files.quarantineDir.exists())
    }

    @Test
    fun aWriteIoFailureIsTransient() {
        note.mkdirs()
        File(note, "child").writeBytes(byteArrayOf(1))
        assertFailsWith<TransientCryptoException> { files.writeText(note, "hello") }
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aCorruptFileCanBeLeftInPlaceAndThenFailsAStrictRead() {
        files.writeText(singleton, "hello")
        val bytes = singleton.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        singleton.writeBytes(bytes)

        assertNull(files.read(singleton, strict = false, quarantineCorrupt = false))
        assertFailsWith<CorruptFileException> { files.read(singleton, strict = true, quarantineCorrupt = false) }
        assertContentEquals(bytes, singleton.readBytes())
        assertFalse(files.quarantineDir.exists())

        assertNull(files.read(singleton, strict = true))
        assertFalse(singleton.exists())
        assertEquals(1, files.quarantineDir.listFiles().orEmpty().size)
    }

    @Test
    fun sweepStagingDeletesOnlyFilesAtLeastTheGateOld() {
        files.stagingDir.mkdirs()
        val fresh = File(files.stagingDir, "fresh.tmp").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 1_000) }
        val old = File(files.stagingDir, "old.tmp").apply { writeBytes(byteArrayOf(2)); setLastModified(now - EncryptedFiles.STAGING_SWEEP_MIN_AGE_MS) }

        files.sweepStaging()

        assertTrue(fresh.exists())
        assertFalse(old.exists())
    }

    private val thirtyDays = 30L * 24 * 60 * 60 * 1000

    private fun setAside(name: String) = File(files.quarantineDir.apply { mkdirs() }, name).apply { writeBytes(byteArrayOf(1)) }

    @Test
    fun aSetAsideCopyAlreadyGoneCountsAsDeleted() {
        // A dangling link in a read-only directory is listed, cannot be deleted and does not exist: the state of a copy
        // the sweep deletes between the listing and the delete.
        val dir = files.quarantineDir.apply { mkdirs() }
        val link = runCatching { Files.createSymbolicLink(File(dir, "queue__abc.bin.$now").toPath(), File(root, "gone").toPath()) }
        assumeTrue("needs symbolic links", link.isSuccess)
        try {
            dir.setWritable(false)
            assumeTrue("needs a user that file permissions bind", !dir.canWrite())
            assertTrue(files.deleteQuarantined(File(root, "queue/abc.bin")))
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun aSetAsideFileGoesThirtyDaysAfterTheTimeInItsNameWhateverItsOwnTime() {
        singleton.writeBytes(ByteArray(64))
        singleton.setLastModified(now - 400 * thirtyDays)
        val copy = assertNotNull(files.quarantine(singleton, RuntimeException("test")))

        now += thirtyDays - 1
        files.sweepQuarantine()
        assertTrue(copy.exists())

        now += 1
        files.sweepQuarantine()
        assertFalse(copy.exists())
    }

    @Test
    fun aKeptCopyUsedAgainTakesTheTimeOfTheLaterUse() {
        singleton.writeBytes(ByteArray(64))
        val first = assertNotNull(files.quarantine(singleton, RuntimeException("test"), keep = true))
        now += thirtyDays - 1
        val again = assertNotNull(files.quarantine(singleton, RuntimeException("test"), keep = true))
        assertEquals(listOf("notes_meta.$now"), files.quarantineDir.list()!!.toList())
        assertEquals("notes_meta.$now", again.name)
        assertFalse(first.exists())

        now += 1
        files.sweepQuarantine()
        assertTrue(again.exists())
    }

    @Test
    fun aKeptCopyKeepsItsNameWhenTheLaterNameIsTaken() {
        singleton.writeBytes(ByteArray(64))
        val first = assertNotNull(files.quarantine(singleton, RuntimeException("test"), keep = true))
        now += 1_000
        val taken = setAside("notes_meta.$now")

        val again = assertNotNull(files.quarantine(singleton, RuntimeException("test"), keep = true))

        assertEquals(first, again)
        assertContentEquals(ByteArray(64), again.readBytes())
        assertContentEquals(byteArrayOf(1), taken.readBytes())
    }

    @Test
    fun theSetAsideTimeIsReadFromTheEndOfTheName() {
        val old = now - EncryptedFiles.QUARANTINE_MAX_AGE_MS
        // Own times that would decide the other way, were a name's time not found.
        val kept = listOf("notes__work__todo.md.$now", "notes__work__todo.md.$now.7", "notes__v1.2.$now", "queue__a.bin.$old.$now", "notes__n.$old.7.$now")
            .map { setAside(it).apply { setLastModified(old) } }
        val gone = listOf("notes__work__todo.md.$old", "notes__work__todo.md.$old.7", "notes__v1.2.$old", "notes__v1.2.$old.99", "queue__a.bin.$now.$old")
            .map { setAside(it).apply { setLastModified(now) } }

        files.sweepQuarantine()

        assertEquals(kept.map { it.name }.sorted(), files.quarantineDir.list()!!.sorted())
        gone.forEach { assertFalse(it.exists(), it.name) }
    }

    @Test
    fun aSetAsideTimeAheadOfTheClockKeepsTheFile() {
        // Own times that would delete them, were the name's time not what counts.
        val ahead = listOf(now + 1, now + thirtyDays, now + 400 * thirtyDays).map { setAside("notes_meta.$it").apply { setLastModified(now - thirtyDays) } }

        files.sweepQuarantine()

        ahead.forEach { assertTrue(it.exists(), it.name) }
    }

    @Test
    fun aNameWithoutATimeGoesByTheFilesOwnTimeAndADirectoryStays() {
        val tooLong = "9".repeat(20)
        val old = listOf("stray", "stray.$tooLong").map { setAside(it).apply { setLastModified(now - thirtyDays) } }
        val recent = listOf("stray.tmp", "recent.$tooLong").map { setAside(it).apply { setLastModified(now - thirtyDays + 1_000) } }
        val folder = File(files.quarantineDir, "folder.1").apply { mkdirs() }

        files.sweepQuarantine()

        old.forEach { assertFalse(it.exists(), it.name) }
        recent.forEach { assertTrue(it.exists(), it.name) }
        assertTrue(folder.isDirectory)
    }

    @Test
    fun aFileOverTheReadCapIsCorruptWithoutBeingRead() {
        files.writeText(singleton, "hello")
        val length = singleton.length()

        assertEquals("hello", files.readText(singleton, maxBytes = length))
        assertNull(files.read(singleton, strict = false, quarantineCorrupt = false, maxBytes = length - 1))
        assertFailsWith<CorruptFileException> { files.read(singleton, strict = true, quarantineCorrupt = false, maxBytes = length - 1) }
        assertTrue(singleton.exists())
        assertNull(files.read(singleton, maxBytes = length - 1))
        assertFalse(singleton.exists())
        assertEquals(1, files.quarantineDir.listFiles().orEmpty().size)
    }

    @Test
    fun anAbsentFileReadsAsNullWithoutQuarantine() {
        assertNull(files.read(singleton))
        assertNull(files.read(singleton, strict = true))
        assertFalse(files.quarantineDir.exists())
    }

    @Test
    fun aReadIoFailureIsTransientNotAbsentAndNotCorruption() {
        singleton.mkdirs()

        assertNull(files.read(singleton))
        assertFailsWith<TransientCryptoException> { files.read(singleton, strict = true) }
        assertTrue(singleton.isDirectory)
        assertFalse(files.quarantineDir.exists())
    }

    @Test
    fun aFileMovedToAnotherPathFailsToOpenAndIsQuarantined() {
        files.writeText(note, "hello")
        val moved = File(root, "notes/home/todo.md").also { it.parentFile.mkdirs() }
        Files.move(note.toPath(), moved.toPath())

        assertNull(files.readText(moved))
        assertFalse(moved.exists())
        assertQuarantined("notes__home__todo.md.$now")
    }

    @Test
    fun aTamperedEnvelopeIsQuarantinedInBothModes() {
        files.writeText(singleton, "hello")
        val bytes = singleton.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xff).toByte()
        singleton.writeBytes(bytes)

        assertNull(files.read(singleton, strict = true))
        assertFalse(singleton.exists())
        assertQuarantined("notes_meta.$now")
    }

    @Test
    fun aFileThatIsNotAnEnvelopeIsQuarantined() {
        singleton.writeText("plain text, never sealed")
        assertNull(files.read(singleton))
        assertFalse(singleton.exists())
        assertQuarantined("notes_meta.$now")
    }

    @Test
    fun aTransientFailureNeverQuarantinesAndOnlyStrictRethrows() {
        files.writeText(singleton, "hello")
        val sealed = singleton.readBytes()
        fake.failOpenWithTransient = true

        assertNull(files.read(singleton))
        assertFailsWith<TransientCryptoException> { files.read(singleton, strict = true) }
        assertContentEquals(sealed, singleton.readBytes())
        assertFalse(files.quarantineDir.exists())

        fake.failOpenWithTransient = false
        assertEquals("hello", files.readText(singleton))
    }

    @Test
    fun aMissingKeyNeverQuarantinesAndOnlyStrictRethrows() {
        files.writeText(singleton, "hello")
        val sealed = singleton.readBytes()
        fake.failOpenWithKeyUnavailable = true

        assertNull(files.read(singleton))
        assertFailsWith<KeyUnavailableException> { files.read(singleton, strict = true) }
        assertContentEquals(sealed, singleton.readBytes())
        assertFalse(files.quarantineDir.exists())
    }

    @Test
    fun aProgrammingErrorPropagatesInBothModes() {
        files.writeText(singleton, "hello")
        val broken = EncryptedFiles(root, object : Cryptographer {
            override fun seal(plaintext: ByteArray, aad: ByteArray) = plaintext
            override fun open(envelope: ByteArray, aad: ByteArray): ByteArray = throw IllegalStateException("bug")
        })
        assertFailsWith<IllegalStateException> { broken.read(singleton) }
        assertFailsWith<IllegalStateException> { broken.read(singleton, strict = true) }
        assertTrue(singleton.exists())
    }

    @Test
    fun aFailedSealLeavesTheExistingFileUntouched() {
        files.writeText(singleton, "before")
        val sealed = singleton.readBytes()
        fake.failSealWhenAadContains = "notes_meta"

        assertFailsWith<TransientCryptoException> { files.writeText(singleton, "after") }
        assertContentEquals(sealed, singleton.readBytes())
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun quarantineNamesCarryTheFolderAndNeverCollide() {
        val work = File(root, "notes/work/todo.md").also { it.parentFile.mkdirs(); it.writeText("w") }
        val home = File(root, "notes/home/todo.md").also { it.parentFile.mkdirs(); it.writeText("h") }

        val first = assertNotNull(files.quarantine(work, RuntimeException("test")))
        val second = assertNotNull(files.quarantine(home, RuntimeException("test")))
        assertEquals("notes__work__todo.md.$now", first.name)
        assertEquals("notes__home__todo.md.$now", second.name)
        assertEquals("w", first.readText())
        assertEquals("h", second.readText())

        work.writeText("again in the same millisecond")
        val third = assertNotNull(files.quarantine(work, RuntimeException("test")))
        assertEquals("notes__work__todo.md.$now.1", third.name)
        assertEquals("w", first.readText())
        assertNull(files.quarantine(work, RuntimeException("gone")))
    }

    private fun assertQuarantined(name: String) {
        val entries = files.quarantineDir.listFiles().orEmpty().map { it.name }
        assertEquals(listOf(name), entries)
    }
}
