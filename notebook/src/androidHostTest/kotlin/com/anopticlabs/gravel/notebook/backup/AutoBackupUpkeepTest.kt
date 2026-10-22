package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What [AutoBackupRunner] does around its backups: read-backs, thinning, the far-fewer notice, key changes and the key check. */
class AutoBackupUpkeepTest {

    private var now = 1_700_000_000_000L
    private val key = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(2).take(64)
    private val otherKey = "zyxwvutsrqponmlkjihgfedcba9876543210".repeat(2).take(64)
    private val tree = "tree:a"
    private val root: File = Files.createTempDirectory("notebook-upkeep").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val notes = NoteStore(root, crypto) { now }
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val refresh = ReminderRefreshSignal()
    private val backup = NotebookBackup(
        notes, QueueStore(root, crypto) { now }, reminders, events,
        ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), refresh, { now }, { TimeZone.UTC }),
        refresh, BackupStaging(root), clock = { now },
    )
    private val store = AutoBackupStore(root, crypto)
    private val folder = FakeFolder()
    private val notices = FakeNotices()
    private val runner = AutoBackupRunner(backup, store, folder, AutoBackupRunNote(File(root, "note")), BackupGate(), notices, clock = { now }, zone = { TimeZone.UTC })
    private var edits = 0

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun setUp(): AutoBackupEntry {
        notes.createNote("work", "a.md", "alpha", modifiedAtMillis = 1L)
        return assertIs<AutoRunResult.Saved>(runner.firstBackup(tree, key.toCharArray(), SetupPurpose.NEW_KEY)).entry
    }

    private suspend fun run(force: Boolean = false) = runner.run(AutoBackupRunner.SLOT_CHANGE, force = force)

    /** A change, then a run that saves a backup of it. */
    private suspend fun changeAndSave(): AutoBackupEntry {
        notes.createNote("work", "n${edits++}.md", "note")
        return assertIs<AutoRunResult.Saved>(run()).entry
    }

    private fun state() = store.readState(strict = true)

    private fun damage(entry: AutoBackupEntry) {
        val doc = folder.docs.getValue(entry.uri)
        doc.bytes = doc.bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
    }

    @Test
    fun aRunThinsOlderBackupsDeletingOnlyFilesItRecorded() = runBlocking<Unit> {
        val first = setUp()
        val day15 = (1..4).map { now += 6 * HOUR; changeAndSave() }
        now += 6 * HOUR
        val day16 = changeAndSave()
        folder.put(tree, "gravel-notebook-auto-2023-11-15-000000.gnbk", byteArrayOf(1))
        now += 3 * DAY
        val latest = changeAndSave()
        // Nov 14 keeps its one backup and Nov 15 its newest; the 16th's is the newest earlier one.
        val kept = listOf(first, day15.last(), day16, latest).map { it.uri }
        assertEquals(kept, state().backups.map { it.uri })
        assertTrue(state().deleting.isEmpty())
        day15.dropLast(1).forEach { assertFalse(it.uri in folder.docs, "thinned file deleted") }
        assertEquals(1, folder.named("gravel-notebook-auto-2023-11-15-000000.gnbk").size, "a file the record does not list stays")
    }

    @Test
    fun aDamagedEarlierBackupHoldsThinningIsToldOnceAndMakesTheNextRunWrite() = runBlocking<Unit> {
        setUp()
        repeat(4) { now += 6 * HOUR; changeAndSave() }
        now += 6 * HOUR
        val earlier = changeAndSave()
        damage(earlier)
        now += 3 * DAY
        changeAndSave()
        assertEquals(7, state().backups.size, "nothing thinned")
        assertTrue(state().backups.single { it.uri == earlier.uri }.damaged)
        assertEquals(listOf(1), notices.damaged)
        assertTrue(state().writeNext)
        now += HOUR
        assertIs<AutoRunResult.Saved>(run(), "unchanged, but a fresh backup")
        assertFalse(state().writeNext)
        assertTrue(earlier.uri in folder.docs, "a damaged backup is never deleted")
        assertEquals(listOf(1), notices.damaged, "told once")
    }

    @Test
    fun aBackupGoneFromTheFolderIsToldOnceDroppedAndTheNextRunWrites() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        changeAndSave()
        folder.docs.remove(first.uri)
        now += HOUR
        assertEquals(AutoRunResult.Confirmed, run())
        assertEquals(listOf(1), notices.missing)
        assertFalse(state().backups.any { it.uri == first.uri })
        assertTrue(state().writeNext)
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        assertEquals(listOf(1), notices.missing)
    }

    @Test
    fun aReadThatFailsThisTimeIsNeitherFlaggedNorToldAndHoldsThinning() = runBlocking<Unit> {
        setUp()
        repeat(4) { now += 6 * HOUR; changeAndSave() }
        now += 6 * HOUR
        val earlier = changeAndSave()
        folder.failRead += earlier.uri
        now += 3 * DAY
        changeAndSave()
        assertEquals(7, state().backups.size)
        assertFalse(state().backups.any { it.damaged })
        assertTrue(notices.damaged.isEmpty() && notices.missing.isEmpty())
        assertFalse(state().writeNext)
    }

    @Test
    fun bytesAddedAfterTheArchiveCountAsDamage() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        changeAndSave()
        folder.docs.getValue(first.uri).let { it.bytes = it.bytes + byteArrayOf(0) }
        now += HOUR
        run()
        assertTrue(state().backups.single { it.uri == first.uri }.damaged)
    }

    @Test
    fun aKeptBackupUnreadForAWeekIsReadAgain() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        changeAndSave()
        now += HOUR
        changeAndSave()
        damage(first)
        now += DAY
        assertEquals(AutoRunResult.Confirmed, run())
        assertTrue(notices.damaged.isEmpty(), "read only once a week")
        now += 7 * DAY
        assertEquals(AutoRunResult.Confirmed, run())
        assertEquals(listOf(1), notices.damaged)
        assertTrue(state().backups.single { it.uri == first.uri }.damaged)
    }

    @Test
    fun aThinnedFileRenamedOrRewrittenSinceIsLeftAndADeleteThatFailsIsTriedAgain() = runBlocking<Unit> {
        setUp()
        val day15 = (1..4).map { now += 6 * HOUR; changeAndSave() }
        now += 6 * HOUR
        changeAndSave()
        folder.docs.getValue(day15[0].uri).name = "mine.gnbk"
        folder.docs.getValue(day15[2].uri).let { it.bytes = it.bytes + byteArrayOf(0) }
        folder.failDelete = true
        now += 3 * DAY
        changeAndSave()
        assertEquals(setOf(day15[1].uri), state().deleting.map { it.uri }.toSet(), "the renamed and the rewritten one are let go")
        folder.failDelete = false
        now += HOUR
        changeAndSave()
        assertTrue(state().deleting.isEmpty())
        assertFalse(day15[1].uri in folder.docs)
        assertTrue(day15[0].uri in folder.docs)
        assertTrue(day15[2].uri in folder.docs)
    }

    @Test
    fun farFewerNotesAreToldAndTheBackupBeforeIsKeptForGood() = runBlocking<Unit> {
        setUp()
        val ids = (1..7).map { notes.createNote("work", "x$it.md", "x") }
        now += HOUR
        val before = assertIs<AutoRunResult.Saved>(run()).entry
        ids.take(6).forEach { notes.deleteNote(it) }
        now += HOUR
        val after = assertIs<AutoRunResult.Saved>(run()).entry
        assertEquals(listOf(NoticeOf(before.uri, after.uri, notes = true, remindersGone = 0)), notices.farFewer.map { NoticeOf(it.before.uri, it.after.uri, it.notes, it.remindersGone) })
        assertTrue(state().backups.single { it.uri == before.uri }.keep)
        now += 60 * DAY
        changeAndSave()
        assertTrue(before.uri in folder.docs)
    }

    private suspend fun folderChange(target: String) =
        runner.firstBackup(target, key.toCharArray(), SetupPurpose.CHANGE_FOLDER, store.readKey()!!.createdAtMillis)

    @Test
    fun aFolderPickedAgainIsComparedWithTheBackupBeforeAndKeepsItAfterADrop() = runBlocking<Unit> {
        setUp()
        val ids = (1..7).map { notes.createNote("work", "x$it.md", "x") }
        now += HOUR
        val before = assertIs<AutoRunResult.Saved>(run()).entry
        ids.take(6).forEach { notes.deleteNote(it) }
        now += HOUR
        val after = assertIs<AutoRunResult.Saved>(folderChange(tree)).entry
        assertEquals(listOf(NoticeOf(before.uri, after.uri, notes = true, remindersGone = 0)), notices.farFewer.map { NoticeOf(it.before.uri, it.after.uri, it.notes, it.remindersGone) })
        assertEquals(listOf(before.uri), state().backups.filter { it.keep }.map { it.uri })
        now += 60 * DAY
        changeAndSave()
        assertTrue(before.uri in folder.docs)
    }

    @Test
    fun aDropAtAFolderPickedAgainWhoseRecordCannotBeWrittenIsToldAndToldAgain() = runBlocking<Unit> {
        setUp()
        val ids = (1..7).map { notes.createNote("work", "x$it.md", "x") }
        now += HOUR
        val before = assertIs<AutoRunResult.Saved>(run()).entry
        ids.take(6).forEach { notes.deleteNote(it) }
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        now += HOUR
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_RECORDED, retry = false), folderChange(tree))
        assertEquals(1, notices.farFewer.size, "told before the write")
        crypto.failSealWhenAadContains = null
        assertFalse(state().backups.single { it.uri == before.uri }.keep)
        now += HOUR
        assertIs<AutoRunResult.Saved>(folderChange(tree))
        assertEquals(2, notices.farFewer.size)
        assertTrue(state().backups.single { it.uri == before.uri }.keep)
    }

    @Test
    fun aFolderPickedAgainIsComparedByItsUpcomingRemindersToo() = runBlocking<Unit> {
        setUp()
        val made = (1..8).map { reminders.create { id -> ReminderItem(id, now + DAY, "r$it") } }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        made.forEach { reminders.remove(it.id) }
        now += HOUR
        assertIs<AutoRunResult.Saved>(folderChange(tree))
        assertEquals(listOf(8), notices.farFewer.map { it.remindersGone })
    }

    @Test
    fun aBackupUnderAReplacedKeyOrIntoAnotherFolderStartsTheRecordOverAndTellsOfNoDrop() = runBlocking<Unit> {
        setUp()
        val ids = (1..7).map { notes.createNote("work", "x$it.md", "x") }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        ids.take(6).forEach { notes.deleteNote(it) }
        now += HOUR
        assertIs<AutoRunResult.Saved>(runner.firstBackup(tree, otherKey.toCharArray(), SetupPurpose.REPLACE_KEY))
        assertTrue(notices.farFewer.isEmpty())
        assertEquals(1, state().backups.size)
        val more = (1..7).map { notes.createNote("work", "y$it.md", "y") }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        more.forEach { notes.deleteNote(it) }
        now += HOUR
        assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:b", otherKey.toCharArray(), SetupPurpose.CHANGE_FOLDER, store.readKey()!!.createdAtMillis))
        assertTrue(notices.farFewer.isEmpty())
        assertEquals(1, state().backups.size)
    }

    /** An eight-note backup, [change]d in the record, then six notes deleted and the stored folder picked again. */
    private suspend fun pickAgainOverAnEightNoteBackup(change: (AutoBackupEntry) -> AutoBackupEntry) {
        setUp()
        val ids = (1..7).map { notes.createNote("work", "x$it.md", "x") }
        now += HOUR
        val large = assertIs<AutoRunResult.Saved>(run()).entry
        store.updateState { s -> s.copy(backups = s.backups.map { if (it.uri == large.uri) change(it) else it }) }
        ids.take(6).forEach { notes.deleteNote(it) }
        now += HOUR
        assertIs<AutoRunResult.Saved>(folderChange(tree))
    }

    @Test
    fun aFolderPickedAgainIsNotComparedWithABackupFoundDamaged() = runBlocking<Unit> {
        pickAgainOverAnEightNoteBackup { it.copy(damaged = true) }
        assertTrue(notices.farFewer.isEmpty(), "two notes after the one-note backup, the newest not found damaged")
    }

    @Test
    fun aFolderPickedAgainIsNotComparedWithABackupSealedUnderAnotherKey() = runBlocking<Unit> {
        pickAgainOverAnEightNoteBackup { it.copy(keyCreatedAtMillis = it.keyCreatedAtMillis - 1) }
        assertTrue(notices.farFewer.isEmpty(), "two notes after the one-note backup, the newest of the stored key")
    }

    @Test
    fun aFolderPickedAgainKeepsTheDeletionList() = runBlocking<Unit> {
        val first = setUp()
        store.updateState { it.copy(deleting = listOf(first.copy(uri = "elsewhere"))) }
        now += HOUR
        assertIs<AutoRunResult.Saved>(folderChange(tree))
        assertEquals(listOf("elsewhere"), state().deleting.map { it.uri })
    }

    @Test
    fun remindersThatWentOffOrHistoryThatAgedOutAreNotADropButRemindersGoneWithoutGoingOffAre() = runBlocking<Unit> {
        setUp()
        val made = (1..8).map { reminders.create { id -> ReminderItem(id, now + DAY, "r$it") } }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        made.take(5).forEach {
            reminders.remove(it.id)
            events.appendFire(ReminderEvent(it.id, now, it.message, now))
        }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        assertTrue(notices.farFewer.isEmpty(), "8 upcoming, then 3 and 5 that went off")
        events.pruneBefore(Long.MAX_VALUE)
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        assertTrue(notices.farFewer.isEmpty(), "history aging out is not a drop")
        made.drop(5).forEach { reminders.remove(it.id) }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        assertEquals(listOf(3), notices.farFewer.map { it.remindersGone }, "none left of 3, none went off")
        assertFalse(notices.farFewer.single().notes)
    }

    @Test
    fun remindersThatFireAgainOrComeAndGoBetweenBackupsDoNotHideOnesGoneWithoutGoingOff() = runBlocking<Unit> {
        setUp()
        val made = (1..20).map { reminders.create { id -> ReminderItem(id, now + DAY, "r$it") } }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        made.take(6).forEach { reminders.remove(it.id) }
        // One that stays upcoming goes off four times, and a quick one is made and goes off in between.
        (1..4).forEach { events.appendFire(ReminderEvent(made[10].id, now + it, "again", now + it)) }
        val quick = reminders.create { id -> ReminderItem(id, now + 10, "quick") }
        reminders.remove(quick.id)
        events.appendFire(ReminderEvent(quick.id, now + 10, "quick", now + 10))
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        assertEquals(listOf(6), notices.farFewer.map { it.remindersGone })
    }

    @Test
    fun aDropInNotesAndRemindersIsOneNoticeAndAnUnreadableLogLeavesRemindersOut() = runBlocking<Unit> {
        setUp()
        val ids = (1..7).map { notes.createNote("work", "x$it.md", "x") }
        val made = (1..8).map { reminders.create { id -> ReminderItem(id, now + DAY, "r$it") } }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        ids.forEach { notes.deleteNote(it) }
        made.forEach { reminders.remove(it.id) }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        assertEquals(listOf(true to 8), notices.farFewer.map { it.notes to it.remindersGone })
        val more = (1..8).map { reminders.create { id -> ReminderItem(id, now + DAY, "s$it") } }
        events.appendFire(ReminderEvent(999, now, "elsewhere", now))
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        more.forEach { reminders.remove(it.id) }
        // The run reads the log for its fingerprint, the archive and the fingerprint again; the fourth read is the drop check's.
        var logReads = 0
        crypto.beforeOpen = { if (it.contains(ReminderEventStore.FILE_NAME) && ++logReads == 4) throw IllegalStateException("log unreadable") }
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        assertEquals(4, logReads)
        assertEquals(1, notices.farFewer.size, "the history log could not be read, so reminders are not compared")
    }

    @Test
    fun damageWhoseRecordCannotBeWrittenIsToldAgain() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        changeAndSave()
        damage(first)
        // The confirm's write lands; the one recording the damage does not.
        var stateWrites = 0
        crypto.beforeSeal = { if (it.contains(AutoBackupStore.STATE_FILE) && ++stateWrites == 2) throw IllegalStateException("not written") }
        now += HOUR
        run()
        assertEquals(listOf(1), notices.damaged, "told before the write")
        assertFalse(state().backups.single { it.uri == first.uri }.damaged)
        crypto.beforeSeal = null
        now += HOUR
        run()
        assertEquals(listOf(1, 1), notices.damaged)
        assertTrue(state().backups.single { it.uri == first.uri }.damaged)
    }

    @Test
    fun theKeyCheckCallsANewestItCouldNotReadOrReachUnreadNotGone() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        val second = changeAndSave()
        folder.failRead += second.uri
        assertEquals(KeyCheckResult.Right(NewestOpened.NOT_READ, backupsOn = true), runner.checkKey(key.toCharArray()))
        assertFalse(state().backups.any { it.damaged })
        assertTrue(notices.damaged.isEmpty())
        assertFalse(state().writeNext)
        folder.failRead.clear()
        folder.docs.remove(second.uri)
        folder.access[tree] = FolderAccess.NO_ACCESS
        assertEquals(KeyCheckResult.Right(NewestOpened.NOT_READ, backupsOn = true), runner.checkKey(key.toCharArray()), "a folder it cannot reach is not a gone file")
        assertTrue(notices.missing.isEmpty())
        assertTrue(first.uri in folder.docs)
    }

    @Test
    fun aDropWhoseRecordCannotBeWrittenIsToldButNotMarked() = runBlocking<Unit> {
        setUp()
        val ids = (1..7).map { notes.createNote("work", "x$it.md", "x") }
        now += HOUR
        val before = assertIs<AutoRunResult.Saved>(run()).entry
        ids.forEach { notes.deleteNote(it) }
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        now += HOUR
        assertEquals(AutoBackupProblem.NOT_RECORDED, assertIs<AutoRunResult.Failed>(run()).problem)
        assertEquals(1, notices.farFewer.size, "told before the write")
        crypto.failSealWhenAadContains = null
        assertFalse(state().backups.single { it.uri == before.uri }.keep)
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        assertEquals(2, notices.farFewer.size, "the next run's comparison tells it again")
        assertTrue(state().backups.single { it.uri == before.uri }.keep)
    }

    @Test
    fun aKeyChangeWhoseProcessEndedBeforeTheRecordIsFinishedByTheNextRun() = runBlocking<Unit> {
        val old = setUp()
        store.saveKey(otherKey.toCharArray(), now + 5)
        now += HOUR
        assertIs<AutoRunResult.Saved>(run(), "nothing changed, but no backup under the stored key")
        val s = state()
        assertEquals(1, s.backups.size)
        assertEquals(now - HOUR + 5, s.backups.single().keyCreatedAtMillis)
        assertEquals(now - HOUR + 5, s.key.createdAtMillis)
        assertTrue(old.uri in folder.docs, "the earlier key's backup stays in the folder")
        assertTrue(notices.damaged.isEmpty())
    }

    @Test
    fun aReplacedKeyRestartsTheRecordAndItsRemindersAndIsRefusedWhenNotSetUp() = runBlocking<Unit> {
        val fresh = AutoBackupRunner(backup, AutoBackupStore(File(root, "other").apply { mkdirs() }, crypto), folder, AutoBackupRunNote(File(root, "note2")), BackupGate(), notices, clock = { now })
        assertEquals(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = false), fresh.firstBackup(tree, otherKey.toCharArray(), SetupPurpose.REPLACE_KEY))
        val old = setUp()
        store.updateState { it.copy(enabled = false, key = it.key.copy(checkedAtMillis = now, checks = 3)) }
        now += HOUR
        val made = assertIs<AutoRunResult.Saved>(runner.firstBackup(tree, otherKey.toCharArray(), SetupPurpose.REPLACE_KEY)).entry
        val s = state()
        assertEquals(listOf(made), s.backups)
        assertFalse(s.enabled, "the switch stays")
        assertEquals(KeyChecks(createdAtMillis = now), s.key)
        assertEquals(otherKey, store.readKey()?.chars?.concatToString())
        assertTrue(old.uri in folder.docs)
    }

    @Test
    fun aRestoredKeyCountsAsACheck() = runBlocking<Unit> {
        notes.createNote("work", "a.md", "alpha")
        assertIs<AutoRunResult.Saved>(runner.firstBackup(tree, key.toCharArray(), SetupPurpose.RESTORED_KEY))
        assertEquals(KeyChecks(createdAtMillis = now, checkedAtMillis = now, checks = 1), state().key)
    }

    @Test
    fun theKeyCheckOpensTheNewestBackupAndFallsBackToTheStoredKey() = runBlocking<Unit> {
        val first = setUp()
        assertEquals(KeyCheckResult.Right(NewestOpened.OPENED, backupsOn = true), runner.checkKey(RecoveryKey.display(key.toCharArray()).toCharArray()))
        assertEquals(KeyCheckResult.Wrong, runner.checkKey(otherKey.toCharArray()))
        assertEquals(KeyCheckResult.Wrong, runner.checkKey("not a key".toCharArray()))
        damage(first)
        assertEquals(KeyCheckResult.Right(NewestOpened.DAMAGED, backupsOn = true), runner.checkKey(key.toCharArray()))
        assertTrue(state().backups.single().damaged)
        assertEquals(listOf(1), notices.damaged)
        assertTrue(state().writeNext)
        assertEquals(KeyCheckResult.Right(NewestOpened.NONE, backupsOn = true), runner.checkKey(key.toCharArray()), "no undamaged backup left to open")
        crypto.failOpenWhenAadContains = AutoBackupStore.KEY_FILE
        assertEquals(false, assertIs<KeyCheckResult.NotChecked>(runner.checkKey(key.toCharArray())).settings, "the stored key could not be read")
        crypto.failOpenWhenAadContains = AutoBackupStore.STATE_FILE
        assertEquals(true, assertIs<KeyCheckResult.NotChecked>(runner.checkKey(key.toCharArray())).settings, "the settings could not be read")
    }

    @Test
    fun theKeyCheckTellsAGoneNewestFromADamagedOneAndSaysWhetherARunWillWrite() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        val second = changeAndSave()
        store.updateState { it.copy(enabled = false) }
        folder.docs.remove(second.uri)
        assertEquals(KeyCheckResult.Right(NewestOpened.MISSING, backupsOn = false), runner.checkKey(key.toCharArray()))
        assertEquals(listOf(1), notices.missing)
        assertEquals(false, notices.backupsOn)
        damage(first)
        assertEquals(KeyCheckResult.Right(NewestOpened.DAMAGED, backupsOn = false), runner.checkKey(key.toCharArray()))
        assertEquals(false, notices.backupsOn)
    }

    @Test
    fun aKeyCheckAfterAKeyChangeEndedWhileOffCountsOnlyTheNewKey() = runBlocking<Unit> {
        setUp()
        store.updateState { it.copy(enabled = false) }
        store.saveKey(otherKey.toCharArray(), now + 5)
        assertEquals(KeyCheckResult.Wrong, runner.checkKey(key.toCharArray()), "the replaced key no longer counts")
        assertEquals(KeyCheckResult.Right(NewestOpened.NONE, backupsOn = false), runner.checkKey(otherKey.toCharArray()))
        assertTrue(state().backups.isEmpty())
        assertTrue(notices.damaged.isEmpty())
    }

    @Test
    fun aBackupNotReadThisTimeHoldsThinningAndOneUnreadForAWeekCountsAsDamaged() = runBlocking<Unit> {
        setUp()
        val day15 = (1..4).map { now += 6 * HOUR; changeAndSave() }
        now += 6 * HOUR
        changeAndSave()
        now += 8 * DAY
        folder.failRead += day15[3].uri
        changeAndSave()
        assertTrue(day15.all { it.uri in folder.docs }, "a due read without an answer holds thinning")
        assertTrue(notices.damaged.isEmpty())
        now += 8 * DAY
        changeAndSave()
        assertEquals(listOf(1), notices.damaged, "a week without an answer counts as damage")
        assertTrue(state().backups.single { it.uri == day15[3].uri }.damaged)
        now += HOUR
        changeAndSave()
        assertFalse(day15[0].uri in folder.docs, "thinning goes on once the read is answered")
    }

    @Test
    fun theBackupARunWroteOrConfirmedIsNotThinnedAfterTheClockMovedBack() = runBlocking<Unit> {
        setUp()
        val start = now
        now = start + 3 * DAY + 5 * HOUR
        changeAndSave()
        // The clock goes back three hours, so the next backup is dated before the one just written, on the same day.
        now = start + 3 * DAY + 2 * HOUR
        val current = changeAndSave()
        now = start + 6 * DAY
        assertEquals(AutoRunResult.Confirmed, run())
        assertTrue(state().backups.any { it.uri == current.uri })
        assertTrue(current.uri in folder.docs)
    }

    @Test
    fun anUnreadStampLaterThanTheClockStillAgesIntoDamage() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        changeAndSave()
        store.updateState { s -> s.copy(backups = s.backups.map { if (it.uri == first.uri) it.copy(unreadSinceMillis = now + 30 * DAY) else it }) }
        folder.failRead += first.uri
        now += HOUR
        run()
        assertEquals(now, state().backups.single { it.uri == first.uri }.unreadSinceMillis)
        now += 8 * DAY
        run()
        assertTrue(state().backups.single { it.uri == first.uri }.damaged)
    }

    @Test
    fun aRunsWholeReadOfTheNewestBackupClearsItsUnreadStamp() = runBlocking<Unit> {
        val first = setUp()
        folder.failRead += first.uri
        assertEquals(KeyCheckResult.Right(NewestOpened.NOT_READ), runner.checkKey(key.toCharArray()))
        assertEquals(now, state().backups.single().unreadSinceMillis)
        folder.failRead.clear()
        now += DAY
        assertEquals(AutoRunResult.Confirmed, run())
        assertNull(state().backups.single().unreadSinceMillis)
        now += 8 * DAY
        folder.failRead += first.uri
        assertIs<AutoRunResult.Saved>(run())
        assertTrue(notices.damaged.isEmpty(), "one read without an answer, the stamp cleared a week before")
        assertFalse(state().backups.first().damaged)
    }

    @Test
    fun aConfirmingReadClearsOnlyTheNewestBackupsUnreadStamp() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        changeAndSave()
        store.updateState { s -> s.copy(backups = s.backups.map { it.copy(unreadSinceMillis = now) }) }
        folder.failRead += first.uri
        now += DAY
        assertEquals(AutoRunResult.Confirmed, run())
        assertEquals(listOf(now - DAY, null), state().backups.map { it.unreadSinceMillis })
    }

    @Test
    fun aKeyCheckThatOpensTheNewestBackupRecordsTheReadAndClearsItsUnreadStamp() = runBlocking<Unit> {
        val first = setUp()
        store.updateState { it.copy(enabled = false) }
        folder.failRead += first.uri
        assertEquals(KeyCheckResult.Right(NewestOpened.NOT_READ, backupsOn = false), runner.checkKey(key.toCharArray()))
        folder.failRead.clear()
        now += HOUR
        assertEquals(KeyCheckResult.Right(NewestOpened.OPENED, backupsOn = false), runner.checkKey(key.toCharArray()))
        assertNull(state().backups.single().unreadSinceMillis)
        assertEquals(now, state().backups.single().checkedAtMillis)
        crypto.failSealWhenAadContains = AutoBackupStore.STATE_FILE
        now += HOUR
        assertEquals(KeyCheckResult.Right(NewestOpened.OPENED, backupsOn = false), runner.checkKey(key.toCharArray()), "a read that cannot be recorded still counts")
        crypto.failSealWhenAadContains = null
        assertEquals(now - HOUR, state().backups.single().checkedAtMillis)
        now += HOUR
        assertEquals(KeyCheckResult.Right(NewestOpened.OPENED, backupsOn = false), runner.checkKey(key.toCharArray()))
        assertEquals(now, state().backups.single().checkedAtMillis, "recorded at a check of a backup with no stamp too")
        now += 8 * DAY
        folder.failRead += first.uri
        assertEquals(KeyCheckResult.Right(NewestOpened.NOT_READ, backupsOn = false), runner.checkKey(key.toCharArray()))
        assertTrue(notices.damaged.isEmpty())
    }

    @Test
    fun aBackupStampedLaterThanTheClockIsReadAgain() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        changeAndSave()
        now += HOUR
        changeAndSave()
        store.updateState { s -> s.copy(backups = s.backups.map { if (it.uri == first.uri) it.copy(checkedAtMillis = now + 30 * DAY) else it }) }
        damage(first)
        now += HOUR
        run()
        assertTrue(state().backups.single { it.uri == first.uri }.damaged)
    }

    @Test
    fun aThinnedFileTheFolderAppDoesNotDescribeStaysOnTheListUntilItsListingSaysGone() = runBlocking<Unit> {
        setUp()
        val day15 = (1..4).map { now += 6 * HOUR; changeAndSave() }
        now += 6 * HOUR
        changeAndSave()
        folder.failDescribe += day15[0].uri
        now += 3 * DAY
        changeAndSave()
        assertTrue(day15[0].uri in state().deleting.map { it.uri }, "still listed under its name")
        assertTrue(day15[0].uri in folder.docs)
        folder.docs.remove(day15[0].uri)
        now += HOUR
        changeAndSave()
        assertTrue(state().deleting.isEmpty())
    }

    @Test
    fun aDamagedBackupThatReadsWholeAgainLosesItsFlagAndDamageIsToldOnce() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        changeAndSave()
        now += HOUR
        changeAndSave()
        // Kept, so thinning cannot take it once it reads whole again.
        store.updateState { s -> s.copy(backups = s.backups.map { if (it.uri == first.uri) it.copy(keep = true) else it }) }
        val good = folder.docs.getValue(first.uri).bytes
        damage(first)
        now += 8 * DAY
        run()
        now += 8 * DAY
        run()
        assertEquals(listOf(1), notices.damaged)
        folder.docs.getValue(first.uri).bytes = good
        now += 8 * DAY
        run()
        assertFalse(state().backups.single { it.uri == first.uri }.damaged)
    }

    @Test
    fun aDamagedNewestFoundWhenNothingChangedIsFlaggedToldAndReplaced() = runBlocking<Unit> {
        val first = setUp()
        damage(first)
        now += HOUR
        assertIs<AutoRunResult.Saved>(run())
        assertTrue(state().backups.single { it.uri == first.uri }.damaged)
        assertEquals(listOf(1), notices.damaged)
        assertFalse(state().writeNext)
    }

    @Test
    fun aGoneFileIsNotCountedWhenTheListingShowsNoOtherRecordedFileOrStillShowsItsName() = runBlocking<Unit> {
        val first = setUp()
        now += HOUR
        val second = changeAndSave()
        folder.docs.getValue(first.uri).name = "renamed.gnbk"
        folder.failRead += first.uri
        now += HOUR
        run()
        assertTrue(notices.missing.isEmpty(), "the folder app still knows it, under another name")
        folder.failRead.clear()
        folder.docs.remove(first.uri)
        folder.docs.getValue(second.uri).name = "also renamed.gnbk"
        now += HOUR
        run()
        assertTrue(notices.missing.isEmpty(), "the listing shows no recorded file")
        assertTrue(state().backups.any { it.uri == first.uri })
    }

    @Test
    fun aFolderChangeAndANewKeyDropTheDeletionList() = runBlocking<Unit> {
        val first = setUp()
        store.updateState { it.copy(deleting = listOf(first.copy(uri = "elsewhere"))) }
        now += HOUR
        assertIs<AutoRunResult.Saved>(runner.firstBackup(tree, otherKey.toCharArray(), SetupPurpose.REPLACE_KEY))
        assertTrue(state().deleting.isEmpty())
        store.updateState { it.copy(deleting = listOf(first.copy(uri = "elsewhere"))) }
        now += HOUR
        assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:b", otherKey.toCharArray(), SetupPurpose.CHANGE_FOLDER, store.readKey()!!.createdAtMillis))
        assertTrue(state().deleting.isEmpty())
    }

    @Test
    fun aCheckWithTheClockBehindTheKeysTimeLeavesTheKeyRecordAndTheNewestBackupFound() = runBlocking<Unit> {
        val first = setUp()
        val checks = AutoBackupChecks(store, notices, FakeAutoScheduler(), FakeCheckAlarm(), clock = { now })
        checks.keyChecked()
        now -= HOUR
        checks.check()
        assertEquals(first.createdAtMillis, store.newestBackupAt { listOf(tree) })
        assertEquals(AutoRunResult.Confirmed, runner.run(AutoBackupRunner.SLOT_PERIODIC))
        assertEquals(1, state().key.checks)
        assertEquals(now, state().key.checkedAtMillis)
    }

    private data class NoticeOf(val before: String, val after: String, val notes: Boolean, val remindersGone: Int)

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
