package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.backup.BackupStaging
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.notes.ARCHIVE_FOLDER
import com.anopticlabs.gravel.notebook.notes.DEFAULT_FOLDERS
import com.anopticlabs.gravel.notebook.notes.seedFolderTags
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookRecoveryTest {
    private val root = Files.createTempDirectory("notebook-recovery").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val files = EncryptedFiles(root, crypto)
    private val notes = NoteStore(root, crypto)
    private val queue = QueueStore(root, crypto)
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val completions = VoiceCompletionStore(root, crypto)
    private var keyDeleted = 0
    private var reset = KeyReset.DELETED
    private var keyAbsent: () -> Boolean = { false }
    private val resets = mutableListOf<Boolean>()
    private var duringReset: suspend () -> Unit = {}
    private val recovery = NotebookRecovery(
        root, notes, queue, reminders, events, completions,
        resetKey = { force -> (if (force && reset == KeyReset.KEPT) KeyReset.DELETED else reset).also { if (it == KeyReset.DELETED) keyDeleted++ } },
        keyAbsent = { keyAbsent() },
        afterReset = {
            resets += it
            duringReset()
        },
        clock = { NOW },
    )
    private val quarantine = File(root, EncryptedFiles.QUARANTINE_DIR)
    private val scheduler = FakeScheduler()
    private val notifier = FakeNotifier()
    private val alarms = ReminderAlarms(reminders, events, scheduler, notifier, FakeSettings(), ReminderRefreshSignal(), { NOW }, { TimeZone.UTC })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun corrupt(name: String) = File(root, name).writeBytes(ByteArray(96) { 7 })

    private fun unparseable(name: String) = files.writeText(File(root, name), "not json")

    private fun quarantined(prefix: String) = quarantine.listFiles().orEmpty().count { it.name.startsWith("$prefix.") }

    private fun reminder(id: Int) = ReminderItem(id, NOW + 3_600_000L, "call the dentist")

    /** A folder as a Start over that ended midway leaves it. */
    private fun startOverFolder(millis: Long) = File(quarantine, "start-over-$millis").apply {
        File(this, "${NoteStore.NOTES_DIR}/n1").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        File(this, ReminderStore.FILE_NAME).writeBytes(byteArrayOf(2))
    }

    private suspend fun someOfEverything() {
        notes.createNote("work", "plan.md", "the plan")
        notes.ensureFolder("work")
        notes.updateFolderTags { mapOf("work" to listOf("work", "office")) }
        queue.enqueue(QueuedCapture("cap1", "cap1.m4a", NOW), byteArrayOf(1, 2, 3))
        reminders.create { reminder(it) }
        events.appendFire(ReminderEvent(1, NOW - 1_000, "earlier", firedAt = NOW - 1_000))
        completions.save(VoiceCompletion("c1", "took the bins out", NOW))
    }

    @Test
    fun aNotebookThatReadsHasNoTrouble() = runBlocking<Unit> {
        assertNull(recovery.diagnose())
        someOfEverything()
        assertNull(recovery.diagnose())
    }

    @Test
    fun eachDamagedFileIsNamedAsItsPart() = runBlocking<Unit> {
        someOfEverything()
        val byFile = mapOf(
            NoteStore.FOLDERS_FILE to NotebookPart.FOLDERS,
            NoteStore.FOLDER_TAGS_FILE to NotebookPart.FOLDER_TAGS,
            QueueStore.QUEUE_META_FILE to NotebookPart.RECORDINGS,
            ReminderStore.FILE_NAME to NotebookPart.REMINDERS,
            ReminderEventStore.FILE_NAME to NotebookPart.REMINDER_HISTORY,
            VoiceCompletionStore.FILE_NAME to NotebookPart.COMPLETIONS,
        )
        for ((name, part) in byFile) {
            val saved = File(root, name).readBytes()
            corrupt(name)
            assertEquals(StorageTrouble.Damaged(setOf(part)), recovery.diagnose(), name)
            File(root, name).writeBytes(saved)
        }
        unparseable(ReminderStore.FILE_NAME)
        corrupt(VoiceCompletionStore.FILE_NAME)
        assertEquals(StorageTrouble.Damaged(setOf(NotebookPart.REMINDERS, NotebookPart.COMPLETIONS)), recovery.diagnose())
    }

    @Test
    fun aDamagedNoteListIsNamedOnceTheIndexIsNoLongerHeld() = runBlocking<Unit> {
        someOfEverything()
        notes.forgetCachedIndexForTest()
        corrupt(NoteStore.META_FILE)
        assertEquals(StorageTrouble.Damaged(setOf(NotebookPart.NOTE_LIST)), recovery.diagnose())
    }

    @Test
    fun aMissingKeyOutranksDamageAndATransientFailureIsUnreadable() = runBlocking<Unit> {
        someOfEverything()
        notes.forgetCachedIndexForTest()
        crypto.failOpenWithTransient = true
        assertEquals(StorageTrouble.Unreadable, recovery.diagnose())
        crypto.failOpenWithTransient = false
        corrupt(ReminderStore.FILE_NAME)
        crypto.beforeOpen = { if (!it.contains(ReminderStore.FILE_NAME)) throw KeyUnavailableException("injected missing key") }
        assertEquals(StorageTrouble.KeyGone, recovery.diagnose())
    }

    @Test
    fun filesLeftWithoutAKeyAreAGoneKeyEvenWhenNoPartReadsThem() = runBlocking<Unit> {
        someOfEverything()
        for (name in root.list()!!) if (name != QueueStore.QUEUE_DIR && name != NoteStore.NOTES_DIR) File(root, name).deleteRecursively()
        notes.forgetCachedIndexForTest()
        keyAbsent = { true }
        crypto.failOpenWithKeyUnavailable = true
        assertEquals(StorageTrouble.KeyGone, recovery.diagnose())

        File(root, NoteStore.NOTES_DIR).deleteRecursively()
        assertEquals(StorageTrouble.KeyGone, recovery.diagnose())
        keyAbsent = { false }
        assertNull(recovery.diagnose())
        keyAbsent = { throw IllegalStateException("key store down") }
        assertNull(recovery.diagnose())
        keyAbsent = { true }

        assertTrue(recovery.startOver())
        assertFalse(File(root, QueueStore.QUEUE_DIR).exists())
        assertTrue(quarantine.list()!!.isEmpty())
        assertNull(recovery.diagnose())
    }

    @Test
    fun startingRemindersFreshSetsThemAndTheirHistoryAsideAndTheNextStoreNumbersAnew() = runBlocking<Unit> {
        someOfEverything()
        val noteIndex = File(root, NoteStore.META_FILE).readBytes()
        val oldIdentity = reminders.readFile(strict = true).storeId
        corrupt(ReminderStore.FILE_NAME)

        assertEquals(setOf(NotebookPart.REMINDERS), recovery.startFresh(setOf(NotebookPart.REMINDERS)))

        assertEquals(1, quarantined(ReminderStore.FILE_NAME))
        assertEquals(1, quarantined(ReminderEventStore.FILE_NAME))
        assertFalse(File(root, ReminderStore.FILE_NAME).exists())
        assertTrue(reminders.readAll(strict = true).isEmpty())
        assertTrue(events.readAll(strict = true).isEmpty())
        val created = reminders.create { reminder(it) }.id
        assertTrue(created in ReminderStore.FIRST_ID_FROM until ReminderStore.FIRST_ID_FROM + ReminderStore.FIRST_ID_SPAN, "$created")
        assertNotEquals(oldIdentity, reminders.readFile(strict = true).storeId)
        assertContentEquals(noteIndex, File(root, NoteStore.META_FILE).readBytes())
        assertEquals(1, completions.readAll(strict = true).size)
        assertEquals(listOf(false), resets)
        assertNull(recovery.diagnose())
    }

    /** A one-shot that fired and was snoozed in the picker; the reset then runs the re-arm, as the app's does among its steps. */
    private suspend fun snoozedBeforeAReset(): ReminderEventKey {
        val item = reminders.create { ReminderItem(it, NOW - 60_000L, "call the dentist") }
        val key = ReminderEventKey(item.id, item.remindAtMillis)
        alarms.recordFire(key, item.message)
        assertTrue(alarms.snoozeConfirmed(key, item.message, 1_800_000L))
        duringReset = { alarms.rearmAll() }
        return key
    }

    private suspend fun assertTheSnoozeStillRings(key: ReminderEventKey) {
        assertEquals(setOf(key), scheduler.snoozes.keys)
        assertTrue(events.readAll(strict = true).isEmpty())
        alarms.snoozedFire(key, "call the dentist")
        assertEquals(listOf(key to "call the dentist"), notifier.posted)
        assertEquals(listOf(key), events.readAll(strict = true).map { it.key })
    }

    @Test
    fun aSnoozeSetBeforeStartingRemindersFreshStillRingsAndIsInTheNewHistory() = runBlocking<Unit> {
        val key = snoozedBeforeAReset()
        corrupt(ReminderStore.FILE_NAME)
        assertEquals(setOf(NotebookPart.REMINDERS), recovery.startFresh(setOf(NotebookPart.REMINDERS)))
        assertTheSnoozeStillRings(key)
    }

    @Test
    fun aSnoozeSetBeforeStartingTheHistoryFreshStillRingsAndIsInTheNewHistory() = runBlocking<Unit> {
        val key = snoozedBeforeAReset()
        corrupt(ReminderEventStore.FILE_NAME)
        assertEquals(setOf(NotebookPart.REMINDER_HISTORY), recovery.startFresh(setOf(NotebookPart.REMINDER_HISTORY)))
        assertTheSnoozeStillRings(key)
    }

    @Test
    fun aSnoozeSetBeforeStartingOverStillRingsAndIsInTheNewHistory() = runBlocking<Unit> {
        val key = snoozedBeforeAReset()
        keyAbsent = { true }
        crypto.failOpenWithKeyUnavailable = true
        assertTrue(recovery.startOver())
        crypto.failOpenWithKeyUnavailable = false
        assertTheSnoozeStillRings(key)
    }

    @Test
    fun theHistoryGoesAsideBeforeTheRemindersSoAStopBetweenLeavesThemDamaged() = runBlocking<Unit> {
        someOfEverything()
        corrupt(ReminderStore.FILE_NAME)
        val historyAtEachRemindersRead = mutableListOf<Boolean>()
        crypto.beforeOpen = { if (it.contains(ReminderStore.FILE_NAME)) historyAtEachRemindersRead += File(root, ReminderEventStore.FILE_NAME).exists() }

        assertEquals(setOf(NotebookPart.REMINDERS), recovery.startFresh(setOf(NotebookPart.REMINDERS)))

        assertFalse(historyAtEachRemindersRead.last())
        assertFalse(File(root, ReminderEventStore.FILE_NAME).exists())
    }

    @Test
    fun foldersAndTagsAreNotStartedFreshFromAListThatCannotBeRead() = runBlocking<Unit> {
        someOfEverything()
        corrupt(NoteStore.FOLDERS_FILE)
        notes.forgetCachedIndexForTest()
        crypto.failOpenWhenAadContains = NoteStore.META_FILE
        assertFailsWith<TransientCryptoException> { recovery.startFresh(setOf(NotebookPart.FOLDERS)) }
        assertEquals(0, quarantined(NoteStore.FOLDERS_FILE))

        crypto.failOpenWhenAadContains = null
        assertTrue(recovery.startFresh(setOf(NotebookPart.FOLDERS)).isNotEmpty())
        corrupt(NoteStore.FOLDER_TAGS_FILE)
        crypto.failOpenWhenAadContains = NoteStore.FOLDERS_FILE
        assertFailsWith<TransientCryptoException> { recovery.startFresh(setOf(NotebookPart.FOLDER_TAGS)) }
        assertEquals(0, quarantined(NoteStore.FOLDER_TAGS_FILE))
    }

    @Test
    fun aPartThatReadsAgainIsLeftAsItIs() = runBlocking<Unit> {
        someOfEverything()
        assertEquals(emptySet(), recovery.startFresh(NotebookPart.entries.toSet()))
        assertEquals(1, reminders.readAll(strict = true).size)
        assertEquals(1, events.readAll(strict = true).size)
        assertEquals(1, completions.readAll(strict = true).size)
        assertFalse(quarantine.exists())
        assertTrue(resets.isEmpty())
    }

    @Test
    fun aDamagedHistoryGoesAloneAndTheRemindersKeepTheirNumbers() = runBlocking<Unit> {
        someOfEverything()
        val before = reminders.readFile(strict = true)
        unparseable(ReminderEventStore.FILE_NAME)
        val revision = events.revision.value
        assertEquals(setOf(NotebookPart.REMINDER_HISTORY), recovery.startFresh(setOf(NotebookPart.REMINDER_HISTORY)))
        assertTrue(events.revision.value > revision, "screens that watch the history re-read it")
        assertEquals(before, reminders.readFile(strict = true))
        assertTrue(events.readAll(strict = true).isEmpty())
        assertEquals(1, quarantined(ReminderEventStore.FILE_NAME))
    }

    @Test
    fun freshFoldersListTheDefaultsAndEveryFolderHoldingANoteAndFreshTagsSeedFromThem() = runBlocking<Unit> {
        someOfEverything()
        notes.createNote("home", "list.md", "milk")
        notes.moveNote(notes.createNote("old", "done.md", "done"), ARCHIVE_FOLDER)
        corrupt(NoteStore.FOLDERS_FILE)
        corrupt(NoteStore.FOLDER_TAGS_FILE)

        val done = recovery.startFresh(setOf(NotebookPart.FOLDER_TAGS, NotebookPart.FOLDERS))

        assertEquals(setOf(NotebookPart.FOLDERS, NotebookPart.FOLDER_TAGS), done)
        assertEquals(DEFAULT_FOLDERS + listOf("home", "work"), notes.readFolders(strict = true))
        assertEquals(mapOf("home" to seedFolderTags("home", ""), "work" to seedFolderTags("work", "")), notes.readFolderTags(strict = true))
        assertEquals(1, quarantined(NoteStore.FOLDERS_FILE))
        assertEquals(1, quarantined(NoteStore.FOLDER_TAGS_FILE))
    }

    @Test
    fun completionsStartEmpty() = runBlocking<Unit> {
        someOfEverything()
        unparseable(VoiceCompletionStore.FILE_NAME)
        assertEquals(setOf(NotebookPart.COMPLETIONS), recovery.startFresh(setOf(NotebookPart.COMPLETIONS)))
        assertTrue(completions.readAll(strict = true).isEmpty())
        assertEquals(1, reminders.readAll(strict = true).size)
    }

    @Test
    fun aTransientFailureLeavesTheDamagedFileWhereItIs() = runBlocking<Unit> {
        someOfEverything()
        val stored = File(root, ReminderStore.FILE_NAME).readBytes()
        crypto.failOpenWhenAadContains = ReminderStore.FILE_NAME
        assertFailsWith<TransientCryptoException> { recovery.startFresh(setOf(NotebookPart.REMINDERS)) }
        crypto.failOpenWhenAadContains = null
        assertContentEquals(stored, File(root, ReminderStore.FILE_NAME).readBytes())
        assertEquals(1, events.readAll(strict = true).size)
        assertFalse(quarantine.exists())
        assertTrue(resets.isEmpty())
    }

    @Test
    fun theNoteListAndTheRecordingsListAreRebuiltFromWhatRemains() = runBlocking<Unit> {
        someOfEverything()
        queue.enqueue(QueuedCapture("cap2", "cap2.m4a", NOW), byteArrayOf(4, 5))
        notes.forgetCachedIndexForTest()
        corrupt(NoteStore.META_FILE)
        corrupt(QueueStore.QUEUE_META_FILE)

        assertEquals(setOf(NotebookPart.NOTE_LIST, NotebookPart.RECORDINGS), recovery.startFresh(setOf(NotebookPart.NOTE_LIST, NotebookPart.RECORDINGS)))

        assertEquals(listOf("plan.md"), notes.readMetadata(strict = true).values.map { it.name })
        assertEquals(setOf("cap1", "cap2"), queue.readQueue(strict = true).map { it.id }.toSet())
        assertNull(recovery.diagnose())
    }

    @Test
    fun repairRebuildsOnlyWhatNeedsItAndReportsOnlyAChange() = runBlocking<Unit> {
        someOfEverything()
        recovery.repair()
        assertTrue(resets.isEmpty())
        corrupt(QueueStore.QUEUE_META_FILE)
        recovery.repair()
        assertEquals(listOf("cap1"), queue.readQueue(strict = true).map { it.id })
        assertEquals(listOf(false), resets)
    }

    @Test
    fun startingOverDeletesEveryFileAndTheKeyAndKeepsTheRecordingsThatWaitUnsealed() = runBlocking<Unit> {
        someOfEverything()
        val pending = File(root, "${QueueStore.PENDING_DIR}/late~clip.m4a").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(9)) }
        val staged = File(root, "${BackupStaging.DIR}/archive-x.tmp").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(8)) }
        assertTrue(root.list()!!.any { it !in NotebookFiles.UNSEALED_DIRS })
        val setAsideBefore = File(quarantine, "${ReminderStore.FILE_NAME}.$NOW").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(7)) }
        startOverFolder(1)
        crypto.failOpenWithKeyUnavailable = true

        assertTrue(recovery.startOver())

        crypto.failOpenWithKeyUnavailable = false
        assertEquals(1, keyDeleted)
        assertEquals(listOf(true), resets)
        assertEquals(listOf(setAsideBefore.name), quarantine.list()!!.toList())
        assertTrue(pending.isFile)
        assertTrue(staged.isFile)
        assertEquals(NotebookFiles.UNSEALED_DIRS.filter { File(root, it).exists() }.toSet(), root.listFiles()!!.map { it.name }.toSet())
        assertTrue(reminders.create { reminder(it) }.id >= ReminderStore.FIRST_ID_FROM)
        assertTrue(notes.readMetadata(strict = true).isEmpty())
        assertNull(recovery.diagnose())
    }

    @Test
    fun theLeftoverPassDeletesStartOverFoldersAndLeavesOtherSetAsideFiles() = runBlocking<Unit> {
        val left = listOf(startOverFolder(1), startOverFolder(NOW), File(quarantine, "start-over-file").apply { writeBytes(byteArrayOf(5)) })
        val copy = File(quarantine, "${ReminderStore.FILE_NAME}.$NOW").apply { writeBytes(byteArrayOf(3)) }
        val otherFolder = File(quarantine, "kept/inside").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(4)) }

        recovery.removeStartOverLeftovers()

        left.forEach { assertFalse(it.exists(), it.name) }
        assertTrue(copy.isFile)
        assertTrue(otherFolder.isFile)
    }

    @Test
    fun aLinkInAStartOverFolderIsDeletedAndItsTargetLeftAlone() = runBlocking<Unit> {
        val outside = Files.createTempDirectory("notebook-recovery-outside").toFile()
        try {
            val target = File(outside, "kept").apply { writeBytes(byteArrayOf(1)) }
            val folder = startOverFolder(1)
            Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath())
            val linked = Files.createSymbolicLink(File(quarantine, "start-over-2").toPath(), outside.toPath())
            val dangling = Files.createSymbolicLink(File(quarantine, "start-over-3").toPath(), File(outside, "absent").toPath())

            recovery.removeStartOverLeftovers()

            assertFalse(folder.exists())
            assertFalse(Files.exists(linked, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(dangling, LinkOption.NOFOLLOW_LINKS))
            assertTrue(target.isFile)
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun whatStartOverCannotDeleteLeavesItDoneWithTheRestDeletedAndGoesAtALaterPass() = runBlocking<Unit> {
        someOfEverything()
        // The same in two folders that each hold a file of the Notebook's: whichever the walk meets
        // second lies wholly after the first entry that cannot be deleted and the first that cannot be read.
        val dirs = listOf(NoteStore.NOTES_DIR, QueueStore.QUEUE_DIR)
        val locked = dirs.map { File(root, "$it/locked").apply { mkdirs(); File(this, "inside").writeBytes(byteArrayOf(1)) } }
        val unread = dirs.map { File(root, "$it/unread").apply { mkdirs(); File(this, "inside").writeBytes(byteArrayOf(2)) } }
        val folder = File(quarantine, "start-over-$NOW")
        val moved = (locked + unread).map { File(folder, it.relativeTo(root).path) }
        crypto.failOpenWithKeyUnavailable = true
        try {
            locked.forEach { it.setWritable(false) }
            unread.forEach { it.setReadable(false) }
            assumeTrue("needs a user that file permissions bind", !Files.isWritable(locked[0].toPath()) && !Files.isReadable(unread[0].toPath()))

            assertTrue(recovery.startOver())

            assertEquals(listOf(true), resets)
            assertEquals(NotebookFiles.UNSEALED_DIRS.filter { File(root, it).exists() }.toSet(), root.list()!!.toSet())
        } finally {
            (locked + unread + moved).forEach { it.setWritable(true); it.setReadable(true) }
        }
        assertEquals(moved.map { File(it, "inside") }.toSet(), quarantine.walk().filter(File::isFile).toSet())

        recovery.removeStartOverLeftovers()
        assertTrue(quarantine.list()!!.isEmpty())
    }

    @Test
    fun aStartOverWhoseLastStepThrowsStillDeletesItsFolder() = runBlocking<Unit> {
        someOfEverything()
        crypto.failOpenWithKeyUnavailable = true
        duringReset = { throw IllegalStateException("injected") }

        assertFailsWith<IllegalStateException> { recovery.startOver() }

        assertTrue(quarantine.list()!!.isEmpty())
        assertEquals(NotebookFiles.UNSEALED_DIRS.filter { File(root, it).exists() }.toSet(), root.list()!!.toSet())
    }

    @Test
    fun theLeftoverPassLeavesTheFolderOfAStartOverUnderWayWhichDeletesItLast() = runBlocking<Unit> {
        someOfEverything()
        crypto.failOpenWithKeyUnavailable = true
        val folder = File(quarantine, "start-over-$NOW")
        val seen = mutableListOf<Boolean>()
        duringReset = {
            seen += folder.isDirectory
            recovery.removeStartOverLeftovers()
            seen += folder.isDirectory
        }

        assertTrue(withTimeout(30_000) { recovery.startOver() })

        assertEquals(listOf(true, true), seen)
        assertFalse(folder.exists())
    }

    @Test
    fun aStartOverStoppedMidwayIsFinishedByTheLeftoverPassAndASecondStartOver() = runBlocking<Unit> {
        someOfEverything()
        crypto.failOpenWithKeyUnavailable = true
        // A name already taken in the folder stops the move of the root's entry of that name.
        File(quarantine, "start-over-$NOW/${ReminderStore.FILE_NAME}").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }

        assertFailsWith<TransientCryptoException> { recovery.startOver() }
        assertTrue(File(root, ReminderStore.FILE_NAME).isFile)
        assertTrue(resets.isEmpty())

        recovery.removeStartOverLeftovers()
        assertTrue(quarantine.list()!!.isEmpty())
        assertEquals(StorageTrouble.KeyGone, recovery.diagnose())

        assertTrue(withTimeout(30_000) { recovery.startOver() })
        assertTrue(quarantine.list()!!.isEmpty())
        assertEquals(NotebookFiles.UNSEALED_DIRS.filter { File(root, it).exists() }.toSet(), root.list()!!.toSet())
        crypto.failOpenWithKeyUnavailable = false
        assertNull(recovery.diagnose())
    }

    @Test
    fun startOverDoesNothingWhileTheKeyStillOpensTheFiles() = runBlocking<Unit> {
        someOfEverything()
        corrupt(ReminderStore.FILE_NAME)
        assertFalse(recovery.startOver())
        assertEquals(0, keyDeleted)
        assertFalse(quarantine.exists())
        assertTrue(resets.isEmpty())
    }

    @Test
    fun aPlanRunsItsStep() = runBlocking<Unit> {
        someOfEverything()
        corrupt(VoiceCompletionStore.FILE_NAME)
        recovery.prepare(RecoveryPlan.StartFresh(setOf(NotebookPart.COMPLETIONS)))
        assertTrue(completions.readAll(strict = true).isEmpty())
        crypto.failOpenWithKeyUnavailable = true
        recovery.prepare(RecoveryPlan.StartOver)
        assertEquals(1, keyDeleted)
    }

    @Test
    fun aMissingKeyBesideAFileThatOpenedIsNotAGoneKey() = runBlocking<Unit> {
        someOfEverything()
        crypto.keyUnavailableWhenAadContains = ReminderStore.FILE_NAME
        assertEquals(StorageTrouble.Unreadable, recovery.diagnose())
        assertFailsWith<TransientCryptoException> { recovery.startOver() }
        assertEquals(0, keyDeleted)
        assertFalse(quarantine.exists())
    }

    @Test
    fun aKeyAndroidStillHasIsKeptUntilAStartOverIsForced() = runBlocking<Unit> {
        someOfEverything()
        crypto.failOpenWithKeyUnavailable = true
        reset = KeyReset.KEPT
        assertFailsWith<KeyStoreFailureException> { recovery.startOver() }
        assertFalse(quarantine.exists())
        assertTrue(File(root, ReminderStore.FILE_NAME).isFile)
        assertTrue(resets.isEmpty())

        assertTrue(recovery.startOver(force = true))
        assertEquals(1, keyDeleted)
        assertFalse(File(root, ReminderStore.FILE_NAME).exists())
    }

    @Test
    fun aFailedReplaceLeavesTheDamagedFileInPlace() = runBlocking<Unit> {
        someOfEverything()
        notes.createNote("home", "list.md", "milk")
        // The tags go first, while the folder list they are seeded from still reads.
        for ((name, part) in listOf(NoteStore.FOLDER_TAGS_FILE to NotebookPart.FOLDER_TAGS, QueueStore.QUEUE_META_FILE to NotebookPart.RECORDINGS, NoteStore.FOLDERS_FILE to NotebookPart.FOLDERS)) {
            corrupt(name)
            val damaged = File(root, name).readBytes()
            crypto.failSealWhenAadContains = name
            assertFailsWith<TransientCryptoException> { recovery.startFresh(setOf(part)) }
            assertContentEquals(damaged, File(root, name).readBytes(), name)
        }
        crypto.failSealWhenAadContains = null
        assertEquals(StorageTrouble.Damaged(setOf(NotebookPart.FOLDERS, NotebookPart.FOLDER_TAGS, NotebookPart.RECORDINGS)), recovery.diagnose())
        assertTrue(File(root, "${QueueStore.QUEUE_DIR}/cap1${QueueStore.BLOB_EXTENSION}").isFile)
    }

    @Test
    fun noKeyFromAndroidIsNotAGoneKeyWhileTheFilesOpen() = runBlocking<Unit> {
        someOfEverything()
        notes.forgetCachedIndexForTest()
        keyAbsent = { true }
        assertNull(recovery.diagnose())
    }

    @Test
    fun aStartOverThatCannotListTheFilesLeavesTheKey() = runBlocking<Unit> {
        someOfEverything()
        crypto.failOpenWithKeyUnavailable = true
        try {
            root.setReadable(false)
            assumeTrue("needs a user that file permissions bind", root.listFiles() == null)
            assertFailsWith<TransientCryptoException> { recovery.startOver() }
        } finally {
            root.setReadable(true)
        }
        assertEquals(0, keyDeleted)
        assertTrue(resets.isEmpty())
    }

    @Test
    fun aForcedStartOverWhoseDeleteFailsMovesNothing() = runBlocking<Unit> {
        someOfEverything()
        crypto.failOpenWithKeyUnavailable = true
        reset = KeyReset.FAILED
        assertFailsWith<TransientCryptoException> { recovery.startOver(force = true) }
        assertFalse(quarantine.exists())
        assertTrue(resets.isEmpty())
    }

    @Test
    fun aKeyThatWorksAgainIsNotStartedOver() = runBlocking<Unit> {
        someOfEverything()
        crypto.failOpenWithKeyUnavailable = true
        reset = KeyReset.KEY_WORKS
        assertFailsWith<TransientCryptoException> { recovery.startOver() }
        assertFalse(quarantine.exists())
    }

    @Test
    fun aStartOverPlanThatFindsDamageInsteadStartsTheDamagedPartsFresh() = runBlocking<Unit> {
        someOfEverything()
        corrupt(VoiceCompletionStore.FILE_NAME)
        recovery.prepare(RecoveryPlan.StartOver)
        assertEquals(0, keyDeleted)
        assertTrue(completions.readAll(strict = true).isEmpty())
        assertEquals(1, reminders.readAll(strict = true).size)
        assertNull(recovery.diagnose())
    }

    @Test
    fun aReplaceThatKeepsFailingKeepsOneCopy() = runBlocking<Unit> {
        someOfEverything()
        corrupt(QueueStore.QUEUE_META_FILE)
        crypto.failSealWhenAadContains = QueueStore.QUEUE_META_FILE
        repeat(3) { assertFailsWith<TransientCryptoException> { recovery.startFresh(setOf(NotebookPart.RECORDINGS)) } }
        assertEquals(1, quarantined(QueueStore.QUEUE_META_FILE))
    }

    private companion object {
        const val NOW = 1_790_089_200_000L
    }
}
