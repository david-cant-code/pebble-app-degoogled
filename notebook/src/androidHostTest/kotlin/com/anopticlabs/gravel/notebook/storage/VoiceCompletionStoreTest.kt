package com.anopticlabs.gravel.notebook.storage

import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceCompletionStoreTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val fake = FakeCryptographer(softwareCrypto())
    private val store = VoiceCompletionStore(root, fake)
    private val files = EncryptedFiles(root, fake)
    private val file = File(root, VoiceCompletionStore.FILE_NAME)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun done(id: String, transcript: String = "t-$id", capturedAt: Long = 0L) = VoiceCompletion(id, transcript, capturedAt)

    private fun quarantined() = files.quarantineDir.listFiles().orEmpty().map { it.name }

    @Test
    fun saveReadGetAndRemoveRoundTrip() = runBlocking {
        assertFalse(store.remove("missing"))
        assertFalse(file.exists())
        assertEquals(emptyList(), store.save(done("a", "alpha", 1_000L)))
        store.save(done("b", "beta", 2_000L))
        assertEquals("voice_completions", files.locationFor(file).decodeToString())

        assertEquals(listOf("a", "b"), VoiceCompletionStore(root, fake).readAll(strict = true).map { it.completionId })
        assertEquals("alpha", store.get("a")?.transcript)
        assertNull(store.get("missing"))

        assertTrue(store.remove("a"))
        assertFalse(store.remove("a"))
        assertEquals(listOf("b"), store.readAll().map { it.completionId })
        assertEquals(done("b", "beta", 2_000L), store.take("b"))
        assertNull(store.take("b"))
        assertEquals(emptyList(), store.readAll(strict = true))
    }

    @Test
    fun theRevisionCountsSavesAndTakesButNotFailedWrites() = runBlocking {
        assertEquals(0L, store.revision.value)
        store.save(VoiceCompletion("a", "t", 1L))
        store.take("a")
        assertEquals(2L, store.revision.value)
        fake.failSealWhenAadContains = VoiceCompletionStore.FILE_NAME
        assertFailsWith<TransientCryptoException> { store.save(VoiceCompletion("b", "t", 1L)) }
        fake.failSealWhenAadContains = null
        assertEquals(2L, store.revision.value)
    }

    @Test
    fun aSealFailureDuringSaveLeavesTheFileAsItWas() = runBlocking {
        store.save(done("a"))
        val before = file.readBytes()
        fake.failSealWhenAadContains = VoiceCompletionStore.FILE_NAME
        assertFailsWith<TransientCryptoException> { store.save(done("b")) }
        fake.failSealWhenAadContains = null
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(listOf("a"), store.readAll(strict = true).map { it.completionId })
    }

    @Test
    fun saveMovesAReSavedIdToTheTailAndDropsTheOldestPastTheCap() = runBlocking {
        store.save(done("a", "first"))
        store.save(done("b"))
        store.save(done("a", "second"))
        assertEquals(listOf(done("b"), done("a", "second")), store.readAll(strict = true))

        for (i in 2 until VoiceCompletionStore.MAX_COMPLETIONS) store.save(done("c-$i", capturedAt = 1_000_000L + i))
        assertEquals(listOf("b"), store.save(done("fresh", capturedAt = 1L)))
        val ids = store.readAll(strict = true).map { it.completionId }
        assertEquals(VoiceCompletionStore.MAX_COMPLETIONS, ids.size)
        assertEquals("fresh", ids.last())
        assertEquals("a", ids.first())
    }

    @Test
    fun anInvalidIdIsRefusedBeforeAnythingIsReadOrWritten() = runBlocking {
        fake.failOpenWithTransient = true
        for (id in listOf("", "../x", "a b")) assertFailsWith<IllegalArgumentException> { store.save(done(id)) }
        assertFalse(file.exists())
    }

    @Test
    fun aTransientFailureOrAMissingKeyDuringSaveWritesNothingAndQuarantinesNothing() = runBlocking {
        store.save(done("a")); store.save(done("b"))
        val before = file.readBytes()

        fake.failOpenWithTransient = true
        assertFailsWith<TransientCryptoException> { store.save(done("c")) }
        assertEquals(emptyList(), store.readAll())
        assertNull(store.get("a"))
        assertFailsWith<TransientCryptoException> { store.get("a", strict = true) }
        fake.failOpenWithTransient = false
        fake.failOpenWithKeyUnavailable = true
        assertFailsWith<KeyUnavailableException> { store.remove("a") }
        assertFailsWith<KeyUnavailableException> { store.readAll(strict = true) }
        fake.failOpenWithKeyUnavailable = false

        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(emptyList(), quarantined())
        assertEquals(listOf("a", "b"), store.readAll(strict = true).map { it.completionId })
    }

    @Test
    fun aCorruptOrMalformedFileFailsEveryMutatorClosedAndIsNeverQuarantined() = runBlocking {
        store.save(done("a"))
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertEquals(emptyList(), store.readAll())
        assertFailsWith<CorruptFileException> { store.save(done("b")) }
        assertFailsWith<CorruptFileException> { store.remove("a") }
        assertTrue(bytes.contentEquals(file.readBytes()))

        fun entry(id: String) = """{"completionId":"$id","transcript":"t","capturedAt":1}"""
        files.writeText(file, "[${entry("a")},${entry("a")}]")
        assertFailsWith<PersistedFormatException> { store.save(done("b")) }
        files.writeText(file, (0..VoiceCompletionStore.MAX_COMPLETIONS).joinToString(",", "[", "]") { entry("c$it") })
        assertFailsWith<PersistedFormatException> { store.readAll(strict = true) }
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun twoInstancesSavingConcurrentlyOverTheCapSettleAtTheCapWithNoLoss() = runBlocking {
        val other = VoiceCompletionStore(root, fake)
        val n = VoiceCompletionStore.MAX_COMPLETIONS + 20
        coroutineScope {
            repeat(n) { i -> launch(Dispatchers.IO) { (if (i % 2 == 0) store else other).save(done("c-$i", capturedAt = i.toLong())) } }
        }
        val ids = VoiceCompletionStore(root, fake).readAll(strict = true).map { it.completionId }
        assertEquals(VoiceCompletionStore.MAX_COMPLETIONS, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(emptyList(), quarantined())
        assertTrue(files.stagingDir.listFiles().orEmpty().isEmpty())
    }
}
