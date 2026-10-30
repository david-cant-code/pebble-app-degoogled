package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
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

class ReminderEventStoreTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private val store = ReminderEventStore(root, fake)
    private val files = EncryptedFiles(root, fake)
    private val file = File(root, ReminderEventStore.FILE_NAME)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun event(id: Int = 1, original: Long = 100L, firedAt: Long = original, chain: Int = 0, completedAt: Long? = null) =
        ReminderEvent(id, original, "m$id", firedAt, snoozeChainCount = chain, completedAt = completedAt)

    private val key = ReminderEventKey(1, 100L)
    private val utc = TimeZone.UTC

    private fun quarantined() = files.quarantineDir.listFiles().orEmpty().map { it.name }

    @Test
    fun appendFireRoundTripsAndReportsAReplayedOccurrence() = runBlocking {
        assertTrue(store.appendFire(event(chain = 3)))
        assertEquals("reminder_events", files.locationFor(file).decodeToString())
        assertFalse(store.appendFire(event(chain = 0)))
        assertTrue(store.appendFire(event(original = 200L)))
        assertEquals(listOf(event(chain = 3), event(original = 200L)), ReminderEventStore(root, fake).readAll(strict = true))
    }

    @Test
    fun stampsReportWhetherTheOccurrenceIsLoggedAndAheadWritesPersist() = runBlocking {
        store.appendFire(event())
        assertTrue(store.markCompleted(key, 200L))
        assertTrue(store.markCompleted(key, 999L))
        assertFalse(store.markCancelled(ReminderEventKey(9, 9L), 1L))
        assertFalse(store.markSnoozeDelivered(ReminderEventKey(9, 9L), 1L))
        store.cancelAhead(ReminderEventKey(7, 5_000L), "meds", 900L)
        store.completeAhead(key, "ignored", 950L)

        assertEquals(
            listOf(event(completedAt = 200L), ReminderEvent(7, 5_000L, "meds", firedAt = 900L, cancelledAt = 900L)),
            store.readAll(strict = true),
        )
        assertTrue(store.markSnoozeDelivered(key, 170L))
        assertEquals(event(completedAt = 200L), store.readAll().first())
    }

    @Test
    fun recordSnoozeReturnsTheStoredEventAndNullWithoutWritingForAnUnknownKey() = runBlocking {
        store.appendFire(event(chain = 2))
        val bumped = store.recordSnooze(key, 170L, true, 0L, utc)
        assertEquals(event(chain = 3).copy(latestSnoozeFireAt = 170L), bumped)
        assertEquals(event(chain = 3).copy(latestSnoozeFireAt = 180L), store.recordSnooze(key, 180L, false, 0L, utc))
        assertEquals(listOf(event(chain = 3).copy(latestSnoozeFireAt = 180L)), store.readAll(strict = true))

        val before = file.readBytes()
        assertNull(store.recordSnooze(ReminderEventKey(2, 1L), 1L, true, 0L, utc))
        assertTrue(before.contentEquals(file.readBytes()))
        store.markSnoozeDelivered(key, 180L)
        assertEquals(180L, store.readAll().single().snoozeDeliveredAt)
    }

    @Test
    fun theCalendarSwitchAndDeleteWriteOnlyWhenTheyApply() = runBlocking {
        store.appendFire(event(completedAt = 150L))
        val before = file.readBytes()
        assertFalse(store.switchStatus(key, completed = true, 200L, utc))
        assertFalse(store.removeOccurrence(ReminderEventKey(9, 9L), 200L, utc))
        assertTrue(before.contentEquals(file.readBytes()))
        assertTrue(store.switchStatus(key, completed = false, 200L, utc))
        assertEquals(event().copy(cancelledAt = 200L, setInCalendar = true), ReminderEventStore(root, fake).readAll(strict = true).single())
        assertTrue(store.removeOccurrence(key, 200L, utc))
        assertEquals(emptyList(), store.readAll(strict = true))
    }

    @Test
    fun pruneBulkAddAndRemoveReportWhatChanged() = runBlocking {
        store.appendFire(event(1, firedAt = 100L))
        store.appendFire(event(2, firedAt = 200L))
        store.appendFire(event(3, firedAt = 300L))
        assertEquals(1, store.pruneBefore(200L))
        assertEquals(listOf(2, 3), store.readAll().map { it.reminderId })

        val bulk = store.addAllSkippingExistingKeys(listOf(event(2, completedAt = 5L), event(2, original = 200L), event(4), event(4, completedAt = 1L)))
        val added = bulk.added
        assertEquals(BulkAddResult(listOf(ReminderEventKey(2, 200L), ReminderEventKey(4, 100L)), 2, 0), bulk)
        assertNull(store.readAll().single { it.key == ReminderEventKey(2, 100L) }.completedAt)

        for (k in added) assertTrue(store.removeOccurrence(k, 0L, utc))
        assertFalse(store.removeOccurrence(ReminderEventKey(9, 9L), 0L, utc))
        assertEquals(listOf(2, 3), store.readAll(strict = true).map { it.reminderId })
        assertEquals(0, store.pruneBefore(0L))
    }

    @Test
    fun aSealFailureDuringAppendFireLeavesTheLogAsItWas() = runBlocking {
        store.appendFire(event(1))
        val before = file.readBytes()
        fake.failSealWhenAadContains = ReminderEventStore.FILE_NAME
        assertFailsWith<TransientCryptoException> { store.appendFire(event(2)) }
        fake.failSealWhenAadContains = null
        assertTrue(before.contentEquals(file.readBytes()))
        assertTrue(store.appendFire(event(2)))
    }

    @Test
    fun aTransientFailureDuringAppendFireWritesNothingAndQuarantinesNothing() = runBlocking {
        store.appendFire(event(1))
        store.appendFire(event(2))
        val before = file.readBytes()
        fake.failOpenWithTransient = true

        assertFailsWith<TransientCryptoException> { store.appendFire(event(3)) }
        assertFailsWith<TransientCryptoException> { store.markCompleted(key, 1L) }
        assertFailsWith<TransientCryptoException> { store.recordSnooze(key, 1L, true, 0L, utc) }
        assertFailsWith<TransientCryptoException> { store.pruneBefore(Long.MAX_VALUE) }
        assertEquals(emptyList(), store.readAll())

        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(emptyList(), quarantined())
        fake.failOpenWithTransient = false
        assertEquals(listOf(1, 2), store.readAll(strict = true).map { it.reminderId })
    }

    @Test
    fun aCorruptOrKeylessLogFailsEveryMutatorClosedAndIsNeverQuarantined() = runBlocking {
        store.appendFire(event(1))
        fake.failOpenWithKeyUnavailable = true
        assertFailsWith<KeyUnavailableException> { store.completeAhead(key, "x", 1L) }
        assertFailsWith<KeyUnavailableException> { store.readAll(strict = true) }
        fake.failOpenWithKeyUnavailable = false

        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertEquals(emptyList(), store.readAll())
        assertFailsWith<CorruptFileException> { store.appendFire(event(2)) }
        assertFailsWith<CorruptFileException> { store.markCancelled(key, 1L) }
        assertFailsWith<CorruptFileException> { store.removeOccurrence(key, 0L, utc) }
        assertTrue(bytes.contentEquals(file.readBytes()))
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun aRepeatedKeyAnOutOfRangeIdOrANegativeChainIsAFormatFailureOnReadAndRefusedOnWrite() = runBlocking {
        fun entry(id: Int, chain: Int = 0) = """{"reminderId":$id,"originalFireMillis":100,"message":"a","firedAt":100,"snoozeChainCount":$chain}"""
        for (json in listOf("[${entry(1)},${entry(1)}]", "[${entry(0)}]", "[${entry(ReminderFile.MAX_REMINDER_ID + 1)}]", "[${entry(1, -1)}]")) {
            files.writeText(file, json)
            assertEquals(emptyList(), store.readAll())
            assertFailsWith<PersistedFormatException> { store.readAll(strict = true) }
            assertFailsWith<PersistedFormatException> { store.appendFire(event(2)) }
        }
        assertEquals(emptyList(), quarantined())

        files.writeText(file, "[${entry(1)}]")
        assertFailsWith<IllegalArgumentException> { store.addAllSkippingExistingKeys(listOf(event(0))) }
        assertFailsWith<IllegalArgumentException> { store.appendFire(event(2, chain = -1)) }
        assertEquals(listOf(1), store.readAll(strict = true).map { it.reminderId })
        assertTrue(EncryptedJsonFile.LOG_FILE_BYTES > EncryptedJsonFile.SMALL_FILE_BYTES)
    }

    @Test
    fun twoInstancesFiringAndStampingConcurrentlyLoseNothing() = runBlocking {
        val receiver = ReminderEventStore(root, fake)
        repeat(10) { store.appendFire(event(it + 1)) }
        coroutineScope {
            repeat(40) { i ->
                launch(Dispatchers.IO) { store.appendFire(event(100 + i)) }
                launch(Dispatchers.IO) { assertTrue(receiver.markCompleted(ReminderEventKey(i % 5 + 1, 100L), 500L + i)) }
                launch(Dispatchers.IO) { assertNotNull(receiver.recordSnooze(ReminderEventKey(i % 5 + 6, 100L), 600L + i, true, 0L, utc)) }
            }
        }
        val log = ReminderEventStore(root, fake).readAll(strict = true)
        assertEquals(50, log.size)
        assertTrue(log.filter { it.reminderId in 1..5 }.all { it.completedAt != null && it.snoozeChainCount == 0 })
        assertTrue(log.filter { it.reminderId in 6..10 }.all { it.completedAt == null && it.snoozeChainCount == 8 })
        assertEquals(emptyList(), quarantined())
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aBulkAddMeasuresMultiByteTextInEncodedBytesAndDropsWhatWouldPassTheCap() = runBlocking<Unit> {
        val capped = ReminderEventStore(root, fake, maxBytes = 4_096L)
        val wide = (1..10).map { ReminderEvent(1, it * 1_000L, "\u00e9".repeat(500), firedAt = it * 1_000L) }
        val result = capped.addAllSkippingExistingKeys(wide)
        assertTrue(result.added.isNotEmpty() && result.dropped > 0, "added ${result.added.size}, dropped ${result.dropped}")
        assertEquals(result.added, capped.readAll(strict = true).map { it.key })
    }
}
