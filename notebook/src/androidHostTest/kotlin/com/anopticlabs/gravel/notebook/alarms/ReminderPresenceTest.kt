package com.anopticlabs.gravel.notebook.alarms

import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.Cryptographer
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.EncryptedJsonFile
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderPresenceTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private val reminders = ReminderStore(root, fake, EncryptedJsonFile.SMALL_FILE_BYTES) { 1 }
    private val refresh = ReminderRefreshSignal()
    private val presence = ReminderPresence(reminders, refresh)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun presenceStartsFalseThenReadsTheStoreOnStartAndOnEveryTickAndDegradesToNone() = runBlocking {
        assertEquals(listOf(false, false), presence.hasReminders.take(2).toList())
        reminders.create { ReminderItem(it, 1L, "m") }
        assertEquals(listOf(false, true), presence.hasReminders.take(2).toList())

        val seen = mutableListOf<Boolean>()
        val collector = launch(Dispatchers.Default) { presence.hasReminders.take(3).collect { synchronized(seen) { seen += it } } }
        withTimeout(5_000L) { while (synchronized(seen) { seen.size } < 2) delay(10) }
        reminders.remove(1)
        // A tick emitted before the collector subscribes is dropped by design, so keep ticking until it completes.
        withTimeout(5_000L) { while (collector.isActive) { refresh.emit(); delay(20) } }
        assertEquals(listOf(false, true, false), seen)

        fake.failOpenWithTransient = true
        assertEquals(listOf(false, false), presence.hasReminders.take(2).toList())
    }

    @Test
    fun aReadThatThrowsCountsAsNoRemindersAndKeepsTheFlowAlive() = runBlocking {
        val broken = ReminderStore(root, object : Cryptographer by fake {
            override fun open(envelope: ByteArray, aad: ByteArray): ByteArray = throw IllegalStateException("injected")
        })
        reminders.create { ReminderItem(it, 1L, "m") }
        assertEquals(listOf(false, false), ReminderPresence(broken, refresh).hasReminders.take(2).toList())
        assertTrue(presence.hasReminders.take(2).toList().last())
    }
}
