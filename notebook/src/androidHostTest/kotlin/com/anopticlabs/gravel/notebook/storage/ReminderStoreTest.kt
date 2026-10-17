package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.backup.MAX_RESTORED_REMINDER_ID
import com.anopticlabs.gravel.notebook.reminders.RecurrenceFrequency
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.RecurrenceRule
import com.anopticlabs.gravel.notebook.reminders.MAX_REMINDER_MESSAGE_CHARS
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReminderStoreTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private val store = ReminderStore(root, fake, EncryptedJsonFile.SMALL_FILE_BYTES) { 1 }
    private val files = EncryptedFiles(root, fake)
    private val file = File(root, ReminderStore.FILE_NAME)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun item(id: Int, message: String = "m$id", at: Long = id * 1000L, rule: RecurrenceRule? = null) = ReminderItem(id, at, message, rule)

    private suspend fun create(message: String, at: Long = 1L, rule: RecurrenceRule? = null) = store.create { ReminderItem(it, at, message, rule) }

    private fun quarantined() = files.quarantineDir.listFiles().orEmpty().map { it.name }

    private fun corruptLastByte() {
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
    }

    @Test
    fun createAllocatesTheNextIdAndNeverReusesADeletedOne() = runBlocking {
        assertEquals(item(1, "one", 1L), create("one"))
        assertEquals(item(2, "two", 1L), create("two"))
        assertEquals(listOf(item(1, "one", 1L), item(2, "two", 1L)), store.readAll(strict = true))
        assertEquals("reminders", files.locationFor(file).decodeToString())

        val events = ReminderEventStore(root, fake)
        events.completeAhead(ReminderEventKey(2, 5_000L), "two", 900L)
        assertTrue(store.remove(2))
        assertEquals(3, create("again", 5_000L).id)
        assertTrue(events.appendFire(ReminderEvent(3, 5_000L, "again", 5_000L)))
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun idsRunOutAtTheBoundInsteadOfWrappingAndARestoreBeyondItIsRefused() = runBlocking {
        assertFailsWith<IllegalArgumentException> { store.addAllSkippingExistingIds(listOf(item(Int.MAX_VALUE))) }
        assertFailsWith<IllegalArgumentException> { store.addAllSkippingExistingIds(listOf(item(ReminderFile.MAX_REMINDER_ID + 1))) }
        assertFalse(file.exists())
        assertEquals(listOf(ReminderFile.MAX_REMINDER_ID), store.addAllSkippingExistingIds(listOf(item(ReminderFile.MAX_REMINDER_ID))).added)
        val before = file.readBytes()
        assertFailsWith<ReminderIdsExhaustedException> { create("one more") }
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(listOf(ReminderFile.MAX_REMINDER_ID), store.readAll(strict = true).map { it.id })
    }

    @Test
    fun aBulkAddStopsAtTheFileCapAndReportsWhatItLeftOut() = runBlocking<Unit> {
        create("one")
        val big = (1..1200).map { item(10 + it, "x".repeat(MAX_REMINDER_MESSAGE_CHARS)) }
        val result = store.addAllSkippingExistingIds(big)
        assertTrue(result.added.size in 1000..1100, "added ${result.added.size}")
        assertEquals(0, result.existing)
        assertEquals(1200 - result.added.size, result.dropped)
        assertEquals(1 + result.added.size, store.readAll(strict = true).size)
        assertEquals(BulkAddResult<Int>(emptyList(), 0, 1), store.addAllSkippingExistingIds(big.takeLast(1)))
        assertFailsWith<IllegalArgumentException> { store.replace(item(11, "y".repeat(MAX_REMINDER_MESSAGE_CHARS + 1))) }
    }

    @Test
    fun aSealFailureDuringAMutationLeavesTheFileAsItWas() = runBlocking {
        create("one")
        val before = file.readBytes()
        fake.failSealWhenAadContains = ReminderStore.FILE_NAME
        assertFailsWith<TransientCryptoException> { create("two") }
        fake.failSealWhenAadContains = null
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(2, create("two").id)
    }

    @Test
    fun createRefusesAnItemBuiltWithAnotherIdAndWritesNothing() = runBlocking {
        assertFailsWith<IllegalArgumentException> { store.create { item(99) } }
        assertFalse(file.exists())
    }

    @Test
    fun replaceUpdatesOnlyTheMatchingIdAndReportsAMissingOne() = runBlocking {
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, 3, 2_000L)
        create("one"); create("two"); create("three")

        assertTrue(store.replace(item(2, "two", 9_999L, rule)))
        assertEquals(listOf(item(1, "one", 1L), item(2, "two", 9_999L, rule), item(3, "three", 1L)), store.readAll())

        val before = file.readBytes()
        assertFalse(store.replace(item(999, "ghost")))
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun removeReportsWhetherAnythingWentAndNeverCreatesTheFile() = runBlocking {
        assertFalse(store.remove(1))
        assertFalse(file.exists())
        create("one")
        assertFalse(store.remove(5))
        assertTrue(store.remove(1))
        assertEquals(emptyList(), store.readAll(strict = true))
    }

    @Test
    fun bulkAddSkipsStoredIdsMovesTheNextIdPastThemAndBulkRemoveReportsTheCount() = runBlocking {
        create("one")
        val added = store.addAllSkippingExistingIds(listOf(item(1, "clash"), item(5), item(9), item(5, "dup")))
        assertEquals(BulkAddResult(listOf(5, 9), 2, 0), added)
        assertEquals(listOf(item(1, "one", 1L), item(5), item(9)), store.readAll())

        assertEquals(2, store.removeByIds(listOf(5, 9, 77)))
        assertEquals(listOf(1), store.readAll().map { it.id })
        assertEquals(10, create("after").id)
        assertEquals(BulkAddResult<Int>(emptyList(), 0, 0), store.addAllSkippingExistingIds(emptyList()))
    }

    // Failure classes

    @Test
    fun aTransientFailureDuringAMutationWritesNothingAndQuarantinesNothing() = runBlocking {
        create("one"); create("two")
        val before = file.readBytes()
        fake.failOpenWithTransient = true

        assertFailsWith<TransientCryptoException> { create("three") }
        assertFailsWith<TransientCryptoException> { store.replace(item(1)) }
        assertFailsWith<TransientCryptoException> { store.remove(1) }
        assertFailsWith<TransientCryptoException> { store.readAll(strict = true) }
        assertEquals(emptyList(), store.readAll())

        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(emptyList(), quarantined())
        fake.failOpenWithTransient = false
        assertEquals(listOf("one", "two"), store.readAll(strict = true).map { it.message })
    }

    @Test
    fun aMissingKeyIsReportedByStrictReadsAndEveryMutator() = runBlocking {
        create("one")
        fake.failOpenWithKeyUnavailable = true

        assertEquals(emptyList(), store.readAll())
        assertFailsWith<KeyUnavailableException> { store.readAll(strict = true) }
        assertFailsWith<KeyUnavailableException> { create("two") }
        assertFailsWith<KeyUnavailableException> { store.addAllSkippingExistingIds(listOf(item(4))) }

        assertTrue(file.exists())
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun aCorruptFileFailsEveryMutatorClosedAndIsNeverQuarantined() = runBlocking {
        create("one")
        corruptLastByte()
        val onDisk = file.readBytes()

        assertEquals(emptyList(), store.readAll())
        assertFailsWith<CorruptFileException> { store.readAll(strict = true) }
        assertFailsWith<CorruptFileException> { create("two") }
        assertFailsWith<CorruptFileException> { store.remove(1) }
        assertFailsWith<CorruptFileException> { store.removeByIds(listOf(1)) }

        assertTrue(onDisk.contentEquals(file.readBytes()))
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun aFileOverTheCapIsCorruptWithoutBeingRead() = runBlocking {
        RandomAccessFile(file, "rw").use { it.setLength(EncryptedJsonFile.SMALL_FILE_BYTES + 1) }
        assertFailsWith<CorruptFileException> { store.readAll(strict = true) }
        assertFailsWith<CorruptFileException> { create("one") }
        assertTrue(file.exists())
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun aRepeatedOutOfRangeOrNotYetAllocatedIdIsAFormatFailureOnReadAndRefusedOnWrite() = runBlocking {
        fun entry(id: Int) = """{"id":$id,"remindAtMillis":1,"message":"m"}"""
        for (json in listOf(
            """{"nextId":2,"reminders":[${entry(1)},${entry(1)}]}""",
            """{"nextId":3,"reminders":[${entry(0)}]}""",
            """{"nextId":3,"reminders":[${entry(3)}]}""",
            """{"nextId":0,"reminders":[]}""",
        )) {
            files.writeText(file, json)
            assertEquals(emptyList(), store.readAll())
            assertFailsWith<PersistedFormatException> { store.readAll(strict = true) }
            assertFailsWith<PersistedFormatException> { create("x") }
        }
        assertEquals(emptyList(), quarantined())

        files.writeText(file, """{"nextId":4,"reminders":[${entry(3)}]}""")
        assertFailsWith<IllegalArgumentException> { store.addAllSkippingExistingIds(listOf(item(0))) }
        assertEquals(listOf(3), store.readAll(strict = true).map { it.id })
        assertEquals(4, create("next").id)
    }

    @Test
    fun theFirstWriteGivesTheStoreAnIdentityThatEveryLaterWriteKeeps() = runBlocking<Unit> {
        assertEquals("", store.readFile().storeId)
        assertFalse(store.remove(1))
        assertFalse(file.exists())

        create("one")
        val identity = store.readFile(strict = true).storeId
        assertTrue(isStoreIdentity(identity), identity)
        create("two")
        store.addAllSkippingExistingIds(listOf(item(5)))
        store.update(1) { it.copy(message = "changed") }
        assertEquals(identity, ReminderStore(root, fake, EncryptedJsonFile.SMALL_FILE_BYTES) { 1 }.identity())

        val other = Files.createTempDirectory("notebook-other").toFile()
        try {
            val elsewhere = ReminderStore(other, fake, EncryptedJsonFile.SMALL_FILE_BYTES) { 1 }.identity()
            assertTrue(isStoreIdentity(elsewhere))
            assertTrue(elsewhere != identity)
            assertEquals(elsewhere, ReminderStore(other, fake, EncryptedJsonFile.SMALL_FILE_BYTES) { 1 }.readFile(strict = true).storeId)
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun adoptionKeepsThisStoresOwnIdsAndGivesAnotherStoresIdsNewOnesOnce() = runBlocking<Unit> {
        create("one")
        create("two")
        val own = store.identity()
        val foreign = "0123456789abcdef0123456789abcdef"

        val first = store.adopt(listOf(ReminderOrigin(own, 1), ReminderOrigin(foreign, 1), ReminderOrigin(foreign, 5), ReminderOrigin(foreign, 1)))
        assertEquals(mapOf(ReminderOrigin(own, 1) to 1, ReminderOrigin(foreign, 1) to 3, ReminderOrigin(foreign, 5) to 4), first)
        assertEquals(mapOf(3 to ReminderOrigin(foreign, 1), 4 to ReminderOrigin(foreign, 5)), store.readFile(strict = true).adopted)

        val before = file.lastModified().also { file.setLastModified(it - 10_000) }
        assertEquals(mapOf(ReminderOrigin(foreign, 5) to 4), ReminderStore(root, fake, EncryptedJsonFile.SMALL_FILE_BYTES) { 1 }.adopt(listOf(ReminderOrigin(foreign, 5))))
        assertEquals(before - 10_000, file.lastModified())
        assertEquals(5, create("next").id)
        assertEquals(mapOf(3 to ReminderOrigin(foreign, 1), 4 to ReminderOrigin(foreign, 5)), store.readFile(strict = true).adopted)
    }

    @Test
    fun adoptionHandsOutNoIdPastTheCeiling() = runBlocking<Unit> {
        store.addAllSkippingExistingIds(listOf(item(ReminderFile.MAX_REMINDER_ID)))
        assertEquals(emptyMap(), store.adopt(listOf(ReminderOrigin("0123456789abcdef0123456789abcdef", 1))))
        assertEquals(emptyMap(), store.readFile(strict = true).adopted)
    }

    @Test
    fun newIdsGoPastThisStoresOwnIdsAndAnOwnIdAnAdoptionHoldsIsLeftOut() = runBlocking<Unit> {
        val own = store.identity()
        val foreign = "0123456789abcdef0123456789abcdef"
        assertEquals(
            mapOf(ReminderOrigin(own, 5) to 5, ReminderOrigin(foreign, 1) to 6),
            store.adopt(listOf(ReminderOrigin(own, 5), ReminderOrigin(foreign, 1))),
        )
        assertEquals(mapOf(ReminderOrigin(own, 7) to 7), store.adopt(listOf(ReminderOrigin(own, 6), ReminderOrigin(own, 7))))
        assertEquals(7, create("next").id)
    }

    @Test
    fun anOwnIdPastTheRestoreCapGetsNoLocalIdAndDoesNotMoveTheCounter() = runBlocking<Unit> {
        val own = store.identity()
        val foreign = "0123456789abcdef0123456789abcdef"
        assertEquals(
            mapOf(ReminderOrigin(own, MAX_RESTORED_REMINDER_ID) to MAX_RESTORED_REMINDER_ID, ReminderOrigin(foreign, 1) to MAX_RESTORED_REMINDER_ID + 1),
            store.adopt(
                listOf(
                    ReminderOrigin(own, ReminderFile.MAX_REMINDER_ID - 1),
                    ReminderOrigin(own, MAX_RESTORED_REMINDER_ID),
                    ReminderOrigin(own, MAX_RESTORED_REMINDER_ID + 1),
                    ReminderOrigin(foreign, 1),
                ),
            ),
        )
        assertEquals(MAX_RESTORED_REMINDER_ID + 2, create("next").id)
    }

    @Test
    fun adoptionLeavesASixteenthOfTheCapFreeSoAReminderStillFits() = runBlocking<Unit> {
        val small = ReminderStore(root, fake, 4096) { 1 }
        val foreign = "0123456789abcdef0123456789abcdef"
        val asked = (1..200).map { ReminderOrigin(foreign, it) }

        val got = small.adopt(asked)

        assertTrue(got.size in 30 until 200, "adopted ${got.size}")
        assertEquals(asked.take(got.size), got.keys.toList())
        val bytes = NotebookJson.encodeToString(ReminderFile.serializer(), small.readFile(strict = true)).encodeToByteArray().size
        assertTrue(bytes in (4096 - 4096 / 16 - 100)..(4096 - 4096 / 16), "file at $bytes bytes")
        small.create { ReminderItem(it, 1L, "still fits") }
    }

    @Test
    fun forgettingKeepsAdoptionsAReminderOrTheGivenIdsStillUse() = runBlocking<Unit> {
        val foreign = "0123456789abcdef0123456789abcdef"
        val ids = store.adopt((1..3).map { ReminderOrigin(foreign, it) })
        store.addAllSkippingExistingIds(listOf(item(ids.getValue(ReminderOrigin(foreign, 1)))))

        assertEquals(1, store.forgetAdoptions(setOf(ids.getValue(ReminderOrigin(foreign, 2)))))

        assertEquals(setOf(1, 2), store.readFile(strict = true).adopted.keys)
        assertEquals(0, store.forgetAdoptions(setOf(2)))
    }

    @Test
    fun aMalformedIdentityOrAdoptionIsAFormatFailure() = runBlocking<Unit> {
        val own = "00000000000000000000000000000000"
        val other = """{"storeId":"0123456789abcdef0123456789abcdef","id":1}"""
        for (json in listOf(
            """{"nextId":3,"storeId":"ABC"}""",
            """{"nextId":3,"storeId":"$own","adopted":{"3":$other}}""",
            """{"nextId":3,"storeId":"$own","adopted":{"1":{"storeId":"$own","id":1}}}""",
            """{"nextId":3,"storeId":"$own","adopted":{"1":$other,"2":$other}}""",
            """{"nextId":3,"storeId":"$own","adopted":{"1":{"storeId":"0123456789abcdef0123456789abcdef","id":0}}}""",
        )) {
            files.writeText(file, json)
            assertFailsWith<PersistedFormatException>(json) { store.readFile(strict = true) }
        }
    }

    @Test
    fun aRestoredIdSetIsNotAWriteWhenNothingChanges() = runBlocking {
        create("one")
        val before = file.lastModified().also { file.setLastModified(it - 10_000) }
        assertEquals(BulkAddResult<Int>(emptyList(), 1, 0), store.addAllSkippingExistingIds(listOf(item(1, "clash"))))
        assertEquals(0, store.removeByIds(listOf(9)))
        assertEquals(before - 10_000, file.lastModified())
    }

    // Two instances

    @Test
    fun twoInstancesCreatingConcurrentlyGetUniqueIdsAndLoseNothing() = runBlocking {
        val other = ReminderStore(root, fake, EncryptedJsonFile.SMALL_FILE_BYTES) { 1 }
        val created = coroutineScope {
            (1..50).map { i ->
                async(Dispatchers.IO) { (if (i % 2 == 0) store else other).create { ReminderItem(it, i.toLong(), "msg $i") } }
            }.awaitAll()
        }
        assertEquals(50, created.map { it.id }.toSet().size)
        assertEquals(created.sortedBy { it.id }, ReminderStore(root, fake, EncryptedJsonFile.SMALL_FILE_BYTES) { 1 }.readAll(strict = true).sortedBy { it.id })
        assertEquals(emptyList(), quarantined())
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun replaceUnderConcurrentCreatesKeepsTheRecurrenceRuleAndEveryCreate() = runBlocking {
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, 1, 1_000_000L)
        val recurring = create("daily", 1_000_000L, rule)
        val receiver = ReminderStore(root, fake, EncryptedJsonFile.SMALL_FILE_BYTES) { 1 }
        coroutineScope {
            repeat(20) { i ->
                launch(Dispatchers.IO) { store.create { ReminderItem(it, (i + 2).toLong(), "fg-$i") } }
                launch(Dispatchers.IO) { assertTrue(receiver.replace(recurring.copy(remindAtMillis = 1_000_000L + (i + 1) * 86_400_000L))) }
            }
        }
        val persisted = store.readAll(strict = true)
        assertEquals(21, persisted.size)
        assertEquals(rule, persisted.single { it.id == recurring.id }.recurrence)
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun anOverlongMessageOrAnUnusableRuleIsRefusedOnWriteAndOnRead() = runBlocking {
        val long = "x".repeat(MAX_REMINDER_MESSAGE_CHARS + 1)
        assertFailsWith<IllegalArgumentException> { store.create { ReminderItem(it, 1L, long) } }
        val zeroAnchor = RecurrenceRule(RecurrenceFrequency.DAILY, 1, 0L)
        assertFailsWith<IllegalArgumentException> { store.create { ReminderItem(it, 1L, "m", zeroAnchor) } }
        assertEquals(emptyList(), store.readAll(strict = true))
        create("ok")
        assertFailsWith<IllegalArgumentException> { store.replace(ReminderItem(1, 1L, "m", zeroAnchor.copy(interval = 0))) }
        assertEquals("ok", store.readAll(strict = true).single().message)
    }

    @Test
    fun updateTransformsUnderTheLockRemovesOnNullAndReportsAnAbsentId() = runBlocking {
        val one = create("one")
        assertEquals(one.copy(message = "two"), store.update(one.id) { it.copy(message = "two") })
        assertEquals("two", store.readAll(strict = true).single().message)
        assertNull(store.update(one.id) { null })
        assertEquals(emptyList(), store.readAll(strict = true))
        assertNull(store.update(one.id) { it.copy(message = "three") })
        val two = create("two")
        assertFailsWith<IllegalArgumentException> { store.update(two.id) { it.copy(id = 99) } }
        assertEquals(two, store.readAll(strict = true).single())
    }

    @Test
    fun aBulkAddMeasuresMultiByteTextInEncodedBytesAndDropsWhatWouldPassTheCap() = runBlocking<Unit> {
        val capped = ReminderStore(root, fake, maxBytes = 4_096L) { 1 }
        val result = capped.addAllSkippingExistingIds((1..10).map { item(it, "\u00e9".repeat(500)) })
        assertTrue(result.added.isNotEmpty() && result.dropped > 0, "added ${result.added.size}, dropped ${result.dropped}")
        assertEquals(result.added, capped.readAll(strict = true).map { it.id })
    }

    @Test
    fun aNewStoreNumbersFromARandomPointBelowTheRestoreCap() = runBlocking<Unit> {
        val random = ReminderStore(root, fake)
        val first = random.create { ReminderItem(it, 1L, "one") }.id
        assertTrue(first in ReminderStore.FIRST_ID_FROM until ReminderStore.FIRST_ID_FROM + ReminderStore.FIRST_ID_SPAN, "$first")
        assertEquals(first + 1, random.create { ReminderItem(it, 1L, "two") }.id)
        assertTrue(ReminderStore.FIRST_ID_FROM + ReminderStore.FIRST_ID_SPAN + (1 shl 24) <= MAX_RESTORED_REMINDER_ID)
        val identity = random.readFile(strict = true).storeId
        file.delete()
        val again = random.create { ReminderItem(it, 1L, "three") }.id
        assertTrue(again in ReminderStore.FIRST_ID_FROM until ReminderStore.FIRST_ID_FROM + ReminderStore.FIRST_ID_SPAN, "$again")
        assertNotEquals(identity, random.readFile(strict = true).storeId)
    }

    @Test
    fun aFileWithoutAnIdentityKeepsItsCounter() = runBlocking<Unit> {
        files.writeText(file, """{"nextId":7,"reminders":[]}""")
        assertEquals(7, ReminderStore(root, fake).create { ReminderItem(it, 1L, "next") }.id)
    }

    @Test
    fun aReminderFileThatReadsIsNotStartedFresh() = runBlocking<Unit> {
        create("kept")
        assertFalse(store.startFreshIfDamaged())
        assertEquals(listOf("kept"), store.readAll(strict = true).map { it.message })
        assertTrue(quarantined().isEmpty())
    }
}
