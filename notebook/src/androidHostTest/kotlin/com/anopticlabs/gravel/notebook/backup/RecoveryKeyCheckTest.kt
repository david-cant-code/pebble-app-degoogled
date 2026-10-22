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
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** [RecoveryKeyCheck] over a real runner, store, checks and setup, with a [FakeFolder]. */
class RecoveryKeyCheckTest {

    private var now = 1_700_000_000_000L
    private val key = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(2).take(64)
    private val newKey = "0123456789abcdefghijklmnopqrstuvwxyz".repeat(2).take(64)
    private val root: File = Files.createTempDirectory("notebook-keycheck").toFile()
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
    private val scheduler = FakeAutoScheduler()
    private val runner = AutoBackupRunner(backup, store, folder, AutoBackupRunNote(File(root, "note")), BackupGate(), notices, clock = { now }, zone = { TimeZone.UTC })
    private val checks = AutoBackupChecks(store, notices, scheduler, FakeCheckAlarm(), clock = { now })
    private val setup = AutoBackupSetup(runner, store, folder, scheduler, PickedFolders(File(root, "picked")), refresh = {}, screenLocked = { false }, newKey = { newKey.toCharArray() })
    private val check = RecoveryKeyCheck(runner, checks, store, setup, clock = { now })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun setUp() {
        notes.createNote("work", "a.md", "alpha")
        assertIs<AutoRunResult.Saved>(runner.firstBackup("tree:a", key.toCharArray(), SetupPurpose.NEW_KEY))
    }

    private suspend fun next(): KeyCheckState = withTimeout(10_000) { check.state.first { it != KeyCheckState.Idle && it != KeyCheckState.Checking } }

    private suspend fun open(): KeyCheckState.Enter {
        check.start()
        return assertIs<KeyCheckState.Enter>(next())
    }

    @Test
    fun aRightKeyPassesAndMovesTheNextReminder() = runBlocking<Unit> {
        setUp()
        now += 3 * DAY
        val entry = open()
        assertEquals(KeyCheckState.Enter(wrong = false, notNow = false), entry, "nothing due, so no Not now")
        val typed = RecoveryKey.display(key.toCharArray()).toCharArray()
        check.submit(typed)
        assertEquals(KeyCheckState.Passed(NewestOpened.OPENED), next())
        assertEquals('\u0000', typed.first(), "the typed key is wiped")
        val k = store.readState(strict = true).key
        assertEquals(now, k.checkedAtMillis)
        assertEquals(1, k.checks)
        check.close()
        assertEquals(KeyCheckState.Idle, check.state.value)
    }

    @Test
    fun aKeyThatCannotBeCheckedSaysTheSettingsCouldNotBeRead() = runBlocking<Unit> {
        setUp()
        open()
        crypto.failOpenWhenAadContains = AutoBackupStore.STATE_FILE
        check.submit(key.toCharArray())
        assertEquals(true, assertIs<KeyCheckState.NotChecked>(next()).settings)
    }

    @Test
    fun aWrongKeyAsksAgainAndCountsNothing() = runBlocking<Unit> {
        setUp()
        open()
        check.submit(newKey.toCharArray())
        assertEquals(KeyCheckState.Enter(wrong = true, notNow = false), withTimeout(10_000) { check.state.first { it is KeyCheckState.Enter } })
        assertNull(store.readState(strict = true).key.checkedAtMillis)
    }

    @Test
    fun notNowIsOfferedOnceWhileAReminderIsDue() = runBlocking<Unit> {
        setUp()
        now += 14 * DAY
        assertEquals(true, open().notNow)
        check.notNow()
        assertEquals(KeyCheckState.Idle, check.state.value)
        withTimeout(10_000) { while (store.readState(strict = true).key.notNowPointMillis == null) kotlinx.coroutines.delay(10) }
        now += DAY
        assertEquals(false, open().notNow)
        check.notNow()
        assertIs<KeyCheckState.Enter>(check.state.value, "Not now is not offered twice")
    }

    @Test
    fun withoutTheKeySetupMakesANewOneForTheSameFolder() = runBlocking<Unit> {
        setUp()
        open()
        check.noKey()
        assertEquals(KeyCheckState.NewKey, check.state.value)
        check.makeNewKey()
        val shown = withTimeout(10_000) { setup.state.first { it is SetupState.ShowKey } }
        assertEquals(RecoveryKey.display(newKey.toCharArray()), assertIs<SetupState.ShowKey>(shown).display)
        assertEquals(KeyCheckState.Idle, check.state.value)
        setup.keyNoted()
        setup.typedBack(newKey.toCharArray())
        val done = assertIs<SetupState.Done>(withTimeout(10_000) { setup.state.first { it is SetupState.Done || it is SetupState.Failed } })
        assertEquals(SetupPurpose.REPLACE_KEY, done.purpose)
        assertEquals(newKey, store.readKey()?.chars?.concatToString())
        assertEquals("tree:a", store.readState(strict = true).folder)
        assertFalse("tree:a" in folder.released, "the folder's access is kept")
    }

    private companion object {
        const val DAY = 24 * 3_600_000L
    }
}
