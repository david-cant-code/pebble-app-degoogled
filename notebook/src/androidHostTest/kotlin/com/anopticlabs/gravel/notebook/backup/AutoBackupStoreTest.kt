package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.KeyUnavailableException
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.storage.TransientCryptoException
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoBackupStoreTest {

    private val root: File = Files.createTempDirectory("notebook-autostore").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val store = AutoBackupStore(root, crypto)
    private val key = "0123456789abcdefghijklmnopqrstuvwxyz".repeat(2).take(64)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun theKeyIsStoredInItsOwnSealedFileAsParsedAndStateWritesLeaveItAlone() = runBlocking<Unit> {
        assertNull(store.readKey())
        store.saveKey(RecoveryKey.display(key.toCharArray()).toCharArray(), 1_234L)
        assertEquals(key, store.readKey()?.chars?.concatToString())
        assertEquals(1_234L, store.readKey()?.createdAtMillis)
        val keyFile = File(root, AutoBackupStore.KEY_FILE)
        val sealed = keyFile.readBytes()
        assertTrue(!sealed.decodeToString().contains(key))
        store.updateState { it.copy(enabled = true, folder = "tree:a") }
        assertTrue(sealed.contentEquals(keyFile.readBytes()))
        assertTrue(store.readState(strict = true).setUp)
        assertFailsWith<IllegalArgumentException> { store.saveKey("short".toCharArray(), 1L) }
    }

    @Test
    fun aKeyFromElsewhereIsTakenOnlyWithNothingSetUpOrStored() = runBlocking<Unit> {
        assertTrue(store.canAdoptKey())
        store.updateState { it.copy(folder = "tree:a") }
        assertFalse(store.canAdoptKey(), "set up")
        store.updateState { it.copy(folder = null) }
        store.saveKey(key.toCharArray(), 1L)
        assertFalse(store.canAdoptKey(), "a key stored")
        File(root, AutoBackupStore.KEY_FILE).delete()
        assertTrue(store.canAdoptKey())
        crypto.failOpenWhenAadContains = AutoBackupStore.STATE_FILE
        assertFalse(store.canAdoptKey(), "state unreadable")
    }

    @Test
    fun aKeyThatCannotBeOpenedThrowsInsteadOfReadingAsNone() = runBlocking<Unit> {
        store.saveKey(key.toCharArray(), 1L)
        crypto.failOpenWithKeyUnavailable = true
        assertFailsWith<KeyUnavailableException> { store.readKey() }
    }

    @Test
    fun theReminderStoresCountTheirWritesAndNotTheWritesThatChangeNothing() = runBlocking<Unit> {
        val reminders = ReminderStore(root, crypto)
        val events = ReminderEventStore(root, crypto)
        val r0 = reminders.revision.value
        val made = reminders.create { ReminderItem(it, 1L, "a") }
        assertEquals(r0 + 1, reminders.revision.value)
        reminders.remove(999)
        assertEquals(r0 + 1, reminders.revision.value)
        assertEquals(r0 + 1, ReminderStore(root, crypto).revision.value, "process-wide per file")
        val e0 = events.revision.value
        events.appendFire(ReminderEvent(made.id, 10L, "a", 11L))
        assertEquals(e0 + 1, events.revision.value)
    }

    @Test
    fun theRunNoteHoldsOneStepAndReadsNothingOnceCleared() {
        val note = AutoBackupRunNote(File(root, "run"))
        assertNull(note.read())
        for (step in listOf(AutoRunStep.Creating("a.part"), AutoRunStep.Writing("content://x/1"), AutoRunStep.Renaming("content://x/2", "a.gnbk"))) {
            assertTrue(note.write(step))
            assertEquals(step, AutoBackupRunNote(File(root, "run")).read())
        }
        note.clear()
        assertNull(note.read())
        File(root, "run").writeText("garbage")
        assertNull(note.read())
    }

    private fun entry(uri: String, keyAt: Long, damaged: Boolean = false) =
        AutoBackupEntry(uri, "$uri.gnbk", 10L, 1L, "h", 0, 0, 0, "f", keyCreatedAtMillis = keyAt, damaged = damaged)

    @Test
    fun theNewestUndamagedBackupOfTheStoredKeyIsTheOneToRestore() = runBlocking<Unit> {
        assertNull(store.newestRestorable())
        store.saveKey(key.toCharArray(), 7L)
        store.updateState { it.copy(folder = "tree:a", backups = listOf(entry("old", 7L), entry("newer", 7L), entry("bad", 7L, damaged = true), entry("other", 3L))) }
        val (newest, stored) = assertNotNull(store.newestRestorable())
        assertEquals("newer", newest.uri)
        assertEquals(key, stored.chars.concatToString())

        store.updateState { it.copy(folder = null) }
        assertNull(store.newestRestorable())
    }

    @Test
    fun noBackupToRestoreWithoutAReadableKeyOrState() = runBlocking<Unit> {
        store.updateState { it.copy(folder = "tree:a", backups = listOf(entry("a", 7L))) }
        assertNull(store.newestRestorable())
        store.saveKey(key.toCharArray(), 7L)
        File(root, AutoBackupStore.STATE_FILE).writeBytes(ByteArray(96) { 7 })
        assertNull(store.newestRestorable())
    }

    @Test
    fun onlyDamagedOrLostSettingsNeedANewSetup() = runBlocking<Unit> {
        assertFalse(store.needsNewSetup())
        store.saveKey(key.toCharArray(), 7L)
        store.updateState { it.copy(folder = "tree:a") }
        assertFalse(store.needsNewSetup())

        crypto.failOpenWithTransient = true
        assertFalse(store.needsNewSetup())
        crypto.failOpenWithTransient = false
        crypto.failOpenWithKeyUnavailable = true
        assertFalse(store.needsNewSetup())
        crypto.failOpenWithKeyUnavailable = false

        val keyFile = File(root, AutoBackupStore.KEY_FILE)
        keyFile.writeBytes(ByteArray(96) { 7 })
        assertTrue(store.needsNewSetup())
        keyFile.delete()
        assertTrue(store.needsNewSetup())
        store.updateState { it.copy(folder = null) }
        assertFalse(store.needsNewSetup())
        File(root, AutoBackupStore.STATE_FILE).writeBytes(ByteArray(96) { 7 })
        assertTrue(store.needsNewSetup())
    }

    @Test
    fun aNewSetupStartsWithTheKeyFileDeletedAndTheStateFileSetAside() = runBlocking<Unit> {
        store.saveKey(key.toCharArray(), 7L)
        store.updateState { it.copy(folder = "tree:a") }
        assertFalse(store.setAsideForNewSetup())
        assertTrue(store.readState(strict = true).setUp)

        File(root, AutoBackupStore.STATE_FILE).writeBytes(ByteArray(96) { 7 })
        assertTrue(store.setAsideForNewSetup())
        assertFalse(File(root, AutoBackupStore.STATE_FILE).exists())
        assertFalse(File(root, AutoBackupStore.KEY_FILE).exists())
        assertEquals(listOf(AutoBackupStore.STATE_FILE), File(root, ".quarantine").list()!!.map { it.substringBeforeLast('.') })
        assertFalse(store.readState(strict = true).setUp)
        assertTrue(store.canAdoptKey())
    }

    @Test
    fun aNewSetupStartsFromEachStateThatNeedsOne() = runBlocking<Unit> {
        val keyFile = File(root, AutoBackupStore.KEY_FILE)
        val stateFile = File(root, AutoBackupStore.STATE_FILE)
        val damage = ByteArray(96) { 7 }
        val states = mapOf<String, suspend () -> Unit>(
            "state damaged, key stored" to { store.saveKey(key.toCharArray(), 7L); stateFile.writeBytes(damage) },
            "state damaged, no key" to { stateFile.writeBytes(damage) },
            "set up, no key" to { store.updateState { it.copy(folder = "tree:a") } },
            "set up, key damaged" to { store.updateState { it.copy(folder = "tree:a") }; keyFile.writeBytes(damage) },
        )
        for ((name, arrange) in states) {
            arrange()
            assertTrue(store.setAsideForNewSetup(), name)
            assertFalse(keyFile.exists(), name)
            assertFalse(stateFile.exists(), name)
            assertFalse(store.needsNewSetup(), name)
        }
    }

    @Test
    fun theNewestBackupTimeComesFromTheStateWithoutReadingTheKey() = runBlocking<Unit> {
        val held = mutableListOf("tree:a")
        assertNull(store.newestBackupAt { held })
        store.updateState {
            it.copy(
                folder = "tree:a", key = KeyChecks(createdAtMillis = 7L),
                backups = listOf(entry("old", 7L).copy(createdAtMillis = 10L), entry("bad", 7L, damaged = true).copy(createdAtMillis = 20L), entry("other", 3L).copy(createdAtMillis = 30L)),
            )
        }
        assertNull(store.newestBackupAt { held }, "no key file")
        store.saveKey(key.toCharArray(), 3L)
        crypto.keyUnavailableWhenAadContains = AutoBackupStore.KEY_FILE
        assertEquals(10L, store.newestBackupAt { held })
        held.clear()
        assertNull(store.newestBackupAt { held }, "folder access gone")
        held += "tree:a"
        store.updateState { it.copy(folder = null) }
        assertNull(store.newestBackupAt { held })
    }

    @Test
    fun aKeyFileThatCannotBeDeletedLeavesTheStateForARetry() = runBlocking<Unit> {
        store.updateState { it.copy(folder = "tree:a") }
        File(root, AutoBackupStore.STATE_FILE).writeBytes(ByteArray(96) { 7 })
        // A directory with a file in it stands in for a key file the delete fails on.
        File(root, "${AutoBackupStore.KEY_FILE}/inside").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertFailsWith<TransientCryptoException> { store.setAsideForNewSetup() }
        assertTrue(File(root, AutoBackupStore.STATE_FILE).exists())
        assertFalse(File(root, ".quarantine").exists())
        assertTrue(store.needsNewSetup())
    }

    @Test
    fun aStateFileThatCannotBeSetAsideStaysForARetryWithTheKeyFileGone() = runBlocking<Unit> {
        store.saveKey(key.toCharArray(), 7L)
        store.updateState { it.copy(folder = "tree:a") }
        File(root, AutoBackupStore.STATE_FILE).writeBytes(ByteArray(96) { 7 })
        File(root, ".quarantine").writeText("not a directory")
        assertFailsWith<TransientCryptoException> { store.setAsideForNewSetup() }
        assertFalse(File(root, AutoBackupStore.KEY_FILE).exists())
        assertTrue(File(root, AutoBackupStore.STATE_FILE).exists())
        assertTrue(store.needsNewSetup())

        File(root, ".quarantine").delete()
        assertTrue(store.setAsideForNewSetup())
        assertFalse(File(root, AutoBackupStore.STATE_FILE).exists())
    }
}
