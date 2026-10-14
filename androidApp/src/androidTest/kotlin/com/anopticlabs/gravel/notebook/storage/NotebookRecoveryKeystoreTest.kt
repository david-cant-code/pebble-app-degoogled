package com.anopticlabs.gravel.notebook.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Starting over against the real Android Keystore, under a throwaway alias and root: a lost key
 * is reported and no key is made while the old files remain, a start over deletes them, and
 * the next write makes a new key that the stores then use. One case runs the leftover pass over a
 * link, on Android's own file tree walk.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.storage.NotebookRecoveryKeystoreTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class NotebookRecoveryKeystoreTest {
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "notebook-recovery-test").apply { deleteRecursively() }
    private val keystore = NotebookKeystore(ALIAS)
    private val crypto = NotebookFiles.crypto(keystore, root)
    private val notes = NoteStore(root, crypto)
    private val reminders = ReminderStore(root, crypto)
    private val completions = VoiceCompletionStore(root, crypto)
    private val recovery = NotebookRecovery(
        root, notes, QueueStore(root, crypto), reminders, ReminderEventStore(root, crypto),
        completions,
        resetKey = keystore::resetKey,
        keyAbsent = { keystore.existingKey() == null },
        afterReset = {},
    )
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After
    fun cleanUp() {
        keystore.deleteKey()
        root.deleteRecursively()
    }

    private fun reminder(id: Int) = ReminderItem(id, System.currentTimeMillis() + 3_600_000L, "water the plants")

    @Test
    fun aLostKeyIsReportedUntilStartingOverMakesANewOne() = runBlocking<Unit> {
        notes.createNote("work", "plan.md", "the plan")
        reminders.create { reminder(it) }
        ks.deleteEntry(ALIAS)
        keystore.invalidateKeyCacheForTest()

        assertEquals(StorageTrouble.KeyGone, recovery.diagnose())
        assertFailsWith<KeyUnavailableException> { completions.save(VoiceCompletion("c1", "took the meds", System.currentTimeMillis())) }
        assertFalse(ks.containsAlias(ALIAS))

        assertTrue(recovery.startOver())

        assertNull(recovery.diagnose())
        assertTrue(reminders.create { reminder(it) }.id >= ReminderStore.FIRST_ID_FROM)
        assertTrue(ks.containsAlias(ALIAS))
        assertTrue(notes.readMetadata(strict = true).isEmpty())
        assertTrue(File(root, EncryptedFiles.QUARANTINE_DIR).list()!!.isEmpty())
        assertFalse(File(root, NoteStore.NOTES_DIR).exists())
    }

    @Test
    fun aLinkInALeftoverStartOverFolderIsDeletedAndItsTargetLeftAlone() = runBlocking<Unit> {
        val outside = File(root.parentFile, "notebook-recovery-test-outside").apply { deleteRecursively(); mkdirs() }
        try {
            val target = File(outside, "kept").apply { writeBytes(byteArrayOf(1)) }
            val folder = File(root, "${EncryptedFiles.QUARANTINE_DIR}/start-over-1").apply { mkdirs() }
            File(folder, "old").writeBytes(byteArrayOf(2))
            Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath())

            recovery.removeStartOverLeftovers()

            assertFalse(folder.exists())
            assertTrue(target.isFile)
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun aResetKeepsAKeyThatWorksAndClearsOneThatIsGone() {
        keystore.getOrCreateKey { true }
        assertEquals(KeyReset.KEY_WORKS, keystore.resetKey(force = true))
        assertTrue(ks.containsAlias(ALIAS))
        ks.deleteEntry(ALIAS)
        assertEquals(KeyReset.DELETED, keystore.resetKey(force = false))
        assertFalse(ks.containsAlias(ALIAS))
        assertNull(keystore.existingKey())
    }

    @Test
    fun deletingTheKeyLetsTheNextSealMakeAnother() {
        val first = keystore.getOrCreateKey { true }
        keystore.deleteKey()
        assertFalse(ks.containsAlias(ALIAS))
        keystore.deleteKey()
        val second = keystore.getOrCreateKey { true }
        assertTrue(ks.containsAlias(ALIAS))
        assertTrue(first !== second)
    }

    private companion object {
        const val ALIAS = "gravel_notebook_recovery_test"
    }
}
