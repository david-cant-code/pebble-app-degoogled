package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AutoBackupSetup] over a real runner and stores, a [FakeFolder] and a fixed new key. */
class AutoBackupSetupTest {

    private val now = 1_700_000_000_000L
    private val newKey = "0123456789abcdefghijklmnopqrstuvwxyz".repeat(2).take(64)
    private val restoredKey = "zyxwvutsrqponmlkjihgfedcba9876543210".repeat(2).take(64)
    private val root: File = Files.createTempDirectory("notebook-setup").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val notes = NoteStore(root, crypto) { now }
    private val reminders = ReminderStore(root, crypto)
    private val events = ReminderEventStore(root, crypto)
    private val refreshSignal = ReminderRefreshSignal()
    private val backup = NotebookBackup(
        notes, QueueStore(root, crypto) { now }, reminders, events,
        ReminderAlarms(reminders, events, FakeScheduler(), FakeNotifier(), FakeSettings(), refreshSignal, { now }, { TimeZone.UTC }),
        refreshSignal, BackupStaging(root), clock = { now },
    )
    private val store = AutoBackupStore(root, crypto)
    private val folder = FakeFolder()
    private val scheduler = FakeAutoScheduler()
    private val runner = AutoBackupRunner(backup, store, folder, AutoBackupRunNote(File(root, "note")), BackupGate(), FakeNotices(), clock = { now }, zone = { TimeZone.UTC })
    @Volatile private var refreshes = 0
    private val picked = PickedFolders(File(root, "picked"))
    private val setup = AutoBackupSetup(runner, store, folder, scheduler, picked, refresh = { refreshes++ }, screenLocked = { false }, newKey = { newKey.toCharArray() })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun pick(tree: String?) {
        assertTrue(setup.claimPicker())
        setup.folderChosen(tree)
    }

    private suspend fun settled(): SetupState = withTimeout(10_000) { setup.state.first { it !is SetupState.Saving } }

    @Test
    fun aNewKeyIsShownOnceTypedBackThenStoredWithTheFirstBackup() = runBlocking<Unit> {
        setup.startNew()
        assertEquals(SetupState.Explain, setup.state.value)
        setup.explained()
        pick("tree:a")
        val shown = assertIs<SetupState.ShowKey>(setup.state.value)
        assertEquals(RecoveryKey.display(newKey.toCharArray()), shown.display)
        assertTrue("tree:a" in folder.held)

        var copied: String? = null
        setup.copyAfterWarning { copied = it }
        assertNull(copied, "no copy before the warning")
        setup.askToCopy()
        setup.copyAfterWarning { copied = it }
        assertEquals(shown.display, copied)

        setup.keyNoted()
        setup.typedBack(newKey.reversed().toCharArray())
        assertEquals(SetupState.TypeBack(wrong = true), setup.state.value)
        assertNull(store.readKey(), "nothing stored before the key is typed back")
        setup.showKeyAgain()
        assertIs<SetupState.ShowKey>(setup.state.value)
        setup.keyNoted()
        val typed = shown.display.lowercase().toCharArray()
        setup.typedBack(typed)
        assertTrue(typed.all { it == '\u0000' })

        val done = assertIs<SetupState.Done>(settled())
        assertEquals(newKey, store.readKey()?.chars?.concatToString())
        assertEquals("tree:a", store.readState(strict = true).folder)
        assertTrue(done.entry.uri in folder.docs)
        assertEquals(1, scheduler.periodic)
        assertTrue(refreshes > 0)
        assertTrue(folder.released.isEmpty())
        setup.close()
        assertEquals(SetupState.Idle, setup.state.value)
        assertTrue("tree:a" in folder.held)
    }

    @Test
    fun aKeyFromARestoreGoesStraightToTheFolderAndIsStored() = runBlocking<Unit> {
        val given = restoredKey.toCharArray()
        setup.startWithKey(given)
        assertEquals(SetupState.PickFolder(opened = false), setup.state.value)
        pick("tree:a")
        assertIs<SetupState.Done>(settled())
        assertEquals(restoredKey, store.readKey()?.chars?.concatToString())
        setup.close()
        assertTrue(given.all { it == '\u0000' })
    }

    @Test
    fun backingOutOfTheFirstPickerEndsSetupAndAStartWhileRunningIsIgnored() = runBlocking<Unit> {
        setup.startNew()
        val ignored = restoredKey.toCharArray()
        setup.startWithKey(ignored)
        assertTrue(ignored.all { it == '\u0000' })
        assertEquals(SetupState.Explain, setup.state.value)
        setup.explained()
        pick(null)
        assertEquals(SetupState.Idle, setup.state.value)
        setup.startNew()
        setup.explained()
        assertTrue(setup.claimPicker())
        setup.returned()
        assertEquals(SetupState.Idle, setup.state.value)
    }

    @Test
    fun aFailedFirstBackupKeepsTheKeyForTryAgainOrAnotherFolderAndReleasesTheOneLeft() = runBlocking<Unit> {
        setup.startWithKey(restoredKey.toCharArray())
        folder.access["tree:a"] = FolderAccess.GONE
        pick("tree:a")
        assertEquals(SetupState.Failed(AutoBackupProblem.FOLDER_GONE, SetupPurpose.RESTORED_KEY), settled())
        assertNull(store.readKey())
        folder.access["tree:a"] = FolderAccess.UNAVAILABLE
        setup.tryAgain()
        assertEquals(SetupState.Failed(AutoBackupProblem.NOT_SAVED, SetupPurpose.RESTORED_KEY), settled())

        setup.pickAnother()
        pick(null)
        assertEquals(SetupState.Failed(AutoBackupProblem.NOT_SAVED, SetupPurpose.RESTORED_KEY), setup.state.value, "backing out returns to the failure")
        setup.pickAnother()
        pick("tree:b")
        assertIs<SetupState.Done>(settled())
        withTimeout(5_000) { while (folder.released.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("tree:a"), folder.released.toList())
        assertEquals(restoredKey, store.readKey()?.chars?.concatToString())
    }

    @Test
    fun aNewFolderWhileOffStaysOffAndSchedulesNothing() = runBlocking<Unit> {
        setup.startWithKey(restoredKey.toCharArray())
        pick("tree:a")
        assertIs<SetupState.Done>(settled())
        setup.close()
        store.updateState { it.copy(enabled = false) }
        val scheduled = scheduler.periodic
        setup.startChangeFolder()
        pick("tree:b")
        assertIs<SetupState.Done>(settled())
        assertFalse(store.readState(strict = true).enabled)
        assertEquals(scheduled, scheduler.periodic)
    }

    @Test
    fun aNewFolderWithAKeyThatCannotBeReadFailsOnWhatWentWrong() = runBlocking<Unit> {
        setup.startWithKey(restoredKey.toCharArray())
        pick("tree:a")
        assertIs<SetupState.Done>(settled())
        setup.close()
        File(root, AutoBackupStore.KEY_FILE).writeBytes(ByteArray(80) { 3 })
        setup.startChangeFolder()
        pick("tree:b")
        assertEquals(SetupState.Failed(AutoBackupProblem.DAMAGED, SetupPurpose.CHANGE_FOLDER), settled())
    }

    @Test
    fun aFolderPickedIsListedUntilKeptOrLetGo() = runBlocking<Unit> {
        setup.startWithKey(restoredKey.toCharArray())
        folder.access["tree:a"] = FolderAccess.GONE
        pick("tree:a")
        assertIs<SetupState.Failed>(settled())
        assertEquals(listOf("tree:a"), picked.all())
        setup.pickAnother()
        pick("tree:b")
        assertIs<SetupState.Done>(settled())
        withTimeout(5_000) { while (picked.all().isNotEmpty()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun closingANewFolderSetupOnTheActiveFolderKeepsItsAccess() = runBlocking<Unit> {
        setup.startWithKey(restoredKey.toCharArray())
        pick("tree:a")
        assertIs<SetupState.Done>(settled())
        setup.close()
        setup.startChangeFolder()
        folder.access["tree:a"] = FolderAccess.GONE
        pick("tree:a")
        assertIs<SetupState.Failed>(settled())
        setup.close()
        kotlinx.coroutines.delay(200)
        assertTrue(folder.released.isEmpty())
    }

    @Test
    fun closingSetupReleasesTheFolderPickedButNotKept() = runBlocking<Unit> {
        setup.startNew()
        setup.explained()
        pick("tree:a")
        setup.close()
        withTimeout(5_000) { while (folder.released.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("tree:a"), folder.released.toList())
        assertNull(store.readKey())
    }

    @Test
    fun aFolderWhoseAccessCannotBeKeptFailsBeforeTheKeyIsShown() = runBlocking<Unit> {
        setup.startNew()
        setup.explained()
        folder.holdFails = true
        pick("tree:a")
        assertEquals(SetupState.Failed(AutoBackupProblem.NO_ACCESS, SetupPurpose.NEW_KEY), setup.state.value)
    }

    @Test
    fun aNewFolderUsesTheStoredKeyAndReleasesTheOldFolder() = runBlocking<Unit> {
        setup.startWithKey(restoredKey.toCharArray())
        pick("tree:a")
        assertIs<SetupState.Done>(settled())
        setup.close()

        setup.startChangeFolder()
        assertEquals(SetupState.PickFolder(opened = false), setup.state.value)
        pick("tree:b")
        assertEquals(SetupPurpose.CHANGE_FOLDER, assertIs<SetupState.Done>(settled()).purpose)
        assertEquals("tree:b", store.readState(strict = true).folder)
        assertEquals(restoredKey, store.readKey()?.chars?.concatToString())
        assertEquals(listOf("tree:a"), folder.released.toList())
        assertFalse("tree:a" in folder.held)
    }

    private var locked = true
    private var lockThrows = false
    private var keysMade = 0
    private val gated = AutoBackupSetup(
        runner, store, folder, scheduler, picked, refresh = {},
        screenLocked = { if (lockThrows) throw SecurityException("keyguard") else locked }, newKey = { keysMade++; newKey.toCharArray() },
    )

    private fun answer(answer: LockAnswer) = gated.lockAnswered(assertNotNull(gated.claimLockPrompt()), answer)

    @Test
    fun withAScreenLockEverySetupWaitsForItAndACancelEndsIt() {
        gated.startNew()
        assertEquals(SetupState.ConfirmLock(opened = false, purpose = SetupPurpose.NEW_KEY), gated.state.value)
        gated.explained()
        assertIs<SetupState.ConfirmLock>(gated.state.value, "nothing goes on before the lock")
        val prompt = assertNotNull(gated.claimLockPrompt())
        assertNull(gated.claimLockPrompt(), "one prompt per wait")
        gated.lockAnswered(prompt, LockAnswer.CANCELED)
        assertEquals(SetupState.Idle, gated.state.value)
        gated.startChangeFolder()
        assertEquals(SetupState.ConfirmLock(opened = false, purpose = SetupPurpose.CHANGE_FOLDER), gated.state.value)
        answer(LockAnswer.CONFIRMED)
        assertEquals(SetupState.PickFolder(opened = false), gated.state.value)
    }

    @Test
    fun aPromptThatFailedSaysSoAndChangesNothing() {
        gated.startReplaceKey("tree:a")
        answer(LockAnswer.FAILED)
        assertEquals(SetupState.LockFailed, gated.state.value)
        assertEquals(0, keysMade)
        gated.close()
        assertEquals(SetupState.Idle, gated.state.value)
    }

    @Test
    fun aNewKeyIsMadeOnlyOnceTheScreenLockIsConfirmed() {
        gated.startReplaceKey("tree:a")
        assertEquals(SetupState.ConfirmLock(opened = false, purpose = SetupPurpose.REPLACE_KEY), gated.state.value)
        assertEquals(0, keysMade)
        answer(LockAnswer.CONFIRMED)
        assertEquals(1, keysMade)
        assertEquals(RecoveryKey.display(newKey.toCharArray()), assertIs<SetupState.ShowKey>(gated.state.value).display)
    }

    @Test
    fun aRestoredKeyTurnedAwayAtTheScreenLockIsWiped() {
        val chars = restoredKey.toCharArray()
        gated.startWithKey(chars)
        answer(LockAnswer.CANCELED)
        assertTrue(chars.all { it == '\u0000' })
        assertEquals(SetupState.Idle, gated.state.value)
    }

    @Test
    fun aPromptThatNeverAnsweredGivesWayToANewStartAndItsLateAnswerCountsForNothing() {
        gated.startNew()
        gated.startChangeFolder()
        assertEquals(SetupPurpose.NEW_KEY, assertIs<SetupState.ConfirmLock>(gated.state.value).purpose, "a waiting prompt is not replaced")
        val stale = assertNotNull(gated.claimLockPrompt())
        gated.startReplaceKey("tree:a")
        assertEquals(SetupState.ConfirmLock(opened = false, purpose = SetupPurpose.REPLACE_KEY), gated.state.value)
        gated.lockAnswered(stale, LockAnswer.CONFIRMED)
        assertIs<SetupState.ConfirmLock>(gated.state.value, "the earlier prompt's answer")
        assertEquals(0, keysMade)
        val current = assertNotNull(gated.claimLockPrompt())
        gated.lockAnswered(stale, LockAnswer.CONFIRMED)
        assertEquals(0, keysMade)
        gated.lockAnswered(current, LockAnswer.CONFIRMED)
        assertEquals(1, keysMade)
    }

    @Test
    fun aLockThatCannotBeReadCountsAsOne() {
        lockThrows = true
        gated.startChangeFolder()
        assertIs<SetupState.ConfirmLock>(gated.state.value)
    }

    @Test
    fun aNewKeyThatFailsOffersNoOtherFolder() = runBlocking<Unit> {
        setup.startWithKey(restoredKey.toCharArray())
        pick("tree:a")
        assertIs<SetupState.Done>(settled())
        setup.close()
        folder.access["tree:a"] = FolderAccess.GONE
        setup.startReplaceKey("tree:a")
        setup.keyNoted()
        setup.typedBack(newKey.toCharArray())
        assertEquals(SetupState.Failed(AutoBackupProblem.FOLDER_GONE, SetupPurpose.REPLACE_KEY), settled())
        setup.pickAnother()
        assertIs<SetupState.Failed>(setup.state.value)
    }

    @Test
    fun withoutAScreenLockSetupAsksNothingAndAStrayAnswerChangesNothing() {
        locked = false
        gated.startNew()
        assertEquals(SetupState.Explain, gated.state.value)
        assertNull(gated.claimLockPrompt())
        gated.lockAnswered(1, LockAnswer.CONFIRMED)
        gated.lockAnswered(1, LockAnswer.FAILED)
        assertEquals(SetupState.Explain, gated.state.value)
    }

    @Test
    fun aNewSetupSetsDamagedFilesAsideOnlyWhenItSavesAfterTheLock() = runBlocking<Unit> {
        store.saveKey(restoredKey.toCharArray(), 5L)
        store.updateState { it.copy(enabled = true, folder = "tree:old") }
        File(root, AutoBackupStore.STATE_FILE).writeBytes(ByteArray(96) { 7 })
        var setAsides = 0
        val locked = AutoBackupSetup(
            runner, store, folder, scheduler, picked, refresh = {}, screenLocked = { true }, newKey = { newKey.toCharArray() },
            setAsideFirst = { if (store.setAsideForNewSetup()) setAsides++ },
        )
        locked.startNew()
        locked.lockAnswered(assertNotNull(locked.claimLockPrompt()), LockAnswer.CANCELED)
        assertEquals(0, setAsides)
        assertTrue(store.needsNewSetup())

        locked.startNew()
        locked.lockAnswered(assertNotNull(locked.claimLockPrompt()), LockAnswer.CONFIRMED)
        locked.explained()
        assertTrue(locked.claimPicker())
        locked.folderChosen("tree:new")
        val shown = assertIs<SetupState.ShowKey>(locked.state.value)
        locked.keyNoted()
        assertEquals(0, setAsides)
        locked.typedBack(shown.display.toCharArray())
        assertIs<SetupState.Done>(withTimeout(10_000) { locked.state.first { it !is SetupState.Saving } })
        assertEquals(1, setAsides)
        assertEquals("tree:new", store.readState(strict = true).folder)
        assertEquals(newKey, store.readKey()?.chars?.concatToString())
    }

    @Test
    fun tryAgainAfterAFolderWhoseAccessCouldNotBeKeptPicksAgainBeforeAnyKeyOrSave() = runBlocking<Unit> {
        store.saveKey(restoredKey.toCharArray(), 5L)
        store.updateState { it.copy(enabled = true, folder = "tree:old") }
        File(root, AutoBackupStore.STATE_FILE).writeBytes(ByteArray(96) { 7 })
        var setAsides = 0
        val fresh = AutoBackupSetup(
            runner, store, folder, scheduler, picked, refresh = {}, screenLocked = { false }, newKey = { keysMade++; newKey.toCharArray() },
            setAsideFirst = { if (store.setAsideForNewSetup()) setAsides++ },
        )
        fresh.startNew()
        fresh.explained()
        folder.holdFails = true
        assertTrue(fresh.claimPicker())
        fresh.folderChosen("tree:new")
        assertEquals(SetupState.Failed(AutoBackupProblem.NO_ACCESS, SetupPurpose.NEW_KEY), fresh.state.value)
        fresh.tryAgain()
        // A save would run on the setup's scope, so what it would have done is read once the state has settled.
        val after = withTimeout(10_000) { fresh.state.first { it !is SetupState.Saving } }
        assertEquals(0, setAsides)
        assertTrue(File(root, AutoBackupStore.KEY_FILE).exists())
        assertEquals(0, keysMade)
        assertEquals(SetupState.PickFolder(opened = false), after)
        folder.holdFails = false
        assertTrue(fresh.claimPicker())
        fresh.folderChosen("tree:new")
        assertIs<SetupState.ShowKey>(fresh.state.value)
        assertEquals(1, keysMade)
    }

    @Test
    fun tryAgainBeforeTheKeyIsShownWritesNoBackupUnderAKeyLeftStored() = runBlocking<Unit> {
        store.saveKey(restoredKey.toCharArray(), 5L)
        setup.startNew()
        setup.explained()
        folder.holdFails = true
        pick("tree:a")
        setup.tryAgain()
        val after = settled()
        assertTrue(folder.docs.isEmpty())
        assertFalse(store.readState(strict = true).setUp)
        assertEquals(SetupState.PickFolder(opened = false), after)
    }

    @Test
    fun tryAgainAfterAFolderChangeWhoseAccessCouldNotBeKeptPicksAgainAndSavesOnceAFolderIsHeld() = runBlocking<Unit> {
        setup.startWithKey(restoredKey.toCharArray())
        pick("tree:a")
        assertIs<SetupState.Done>(settled())
        setup.close()
        setup.startChangeFolder()
        folder.holdFails = true
        pick("tree:b")
        assertEquals(SetupState.Failed(AutoBackupProblem.NO_ACCESS, SetupPurpose.CHANGE_FOLDER), setup.state.value)
        setup.tryAgain()
        assertEquals(SetupState.PickFolder(opened = false), settled())
        assertEquals("tree:a", store.readState(strict = true).folder)
        pick(null)
        assertEquals(SetupState.Failed(AutoBackupProblem.NO_ACCESS, SetupPurpose.CHANGE_FOLDER), setup.state.value, "backed out of the picker")
        setup.tryAgain()
        assertEquals(SetupState.PickFolder(opened = false), settled())
        folder.holdFails = false
        folder.access["tree:b"] = FolderAccess.UNAVAILABLE
        pick("tree:b")
        assertEquals(SetupState.Failed(AutoBackupProblem.NOT_SAVED, SetupPurpose.CHANGE_FOLDER), settled())
        folder.access.remove("tree:b")
        setup.tryAgain()
        assertEquals(SetupPurpose.CHANGE_FOLDER, assertIs<SetupState.Done>(settled()).purpose)
        assertEquals("tree:b", store.readState(strict = true).folder)
    }

    @Test
    fun tryAgainAfterATypedBackKeysSecondFolderCouldNotBeKeptPicksAgain() = runBlocking<Unit> {
        setup.startNew()
        setup.explained()
        folder.access["tree:a"] = FolderAccess.UNAVAILABLE
        pick("tree:a")
        val shown = assertIs<SetupState.ShowKey>(setup.state.value)
        setup.keyNoted()
        setup.typedBack(shown.display.toCharArray())
        assertEquals(SetupState.Failed(AutoBackupProblem.NOT_SAVED), settled())
        setup.pickAnother()
        folder.holdFails = true
        pick("tree:b")
        assertEquals(SetupState.Failed(AutoBackupProblem.NO_ACCESS), setup.state.value)
        setup.tryAgain()
        assertEquals(SetupState.PickFolder(opened = false), settled())
        assertTrue(folder.docs.isEmpty())
    }

    @Test
    fun aFolderNotKeptInOneSetupDoesNotSendTheNextSetupsTryAgainToThePicker() = runBlocking<Unit> {
        setup.startWithKey(restoredKey.toCharArray())
        pick("tree:a")
        assertIs<SetupState.Done>(settled())
        setup.close()
        setup.startChangeFolder()
        folder.holdFails = true
        pick("tree:b")
        setup.close()
        folder.access["tree:a"] = FolderAccess.UNAVAILABLE
        setup.startReplaceKey("tree:a")
        val shown = assertIs<SetupState.ShowKey>(setup.state.value)
        setup.keyNoted()
        setup.typedBack(shown.display.toCharArray())
        assertEquals(SetupState.Failed(AutoBackupProblem.NOT_SAVED, SetupPurpose.REPLACE_KEY), settled())
        folder.access.remove("tree:a")
        setup.tryAgain()
        assertEquals(SetupPurpose.REPLACE_KEY, assertIs<SetupState.Done>(settled()).purpose)
    }
}
