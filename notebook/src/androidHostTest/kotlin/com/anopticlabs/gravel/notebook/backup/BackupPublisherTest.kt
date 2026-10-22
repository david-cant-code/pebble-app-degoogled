package com.anopticlabs.gravel.notebook.backup

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The publish sequence over an in-memory document store whose misbehaviors are switchable. */
class BackupPublisherTest {

    private val name = "gravel-notebook-2026-10-02-1430.gnbk"
    private val data = ByteArray(200_000) { (it * 13 + 5).toByte() }
    private val source = BackupSource(data.size.toLong()) { ByteArrayInputStream(data) }

    /** Stands in for the archive reader: the read-back must hold exactly [data]. */
    private val verifyBytes: (InputStream) -> Unit = { input ->
        if (!input.readBytes().contentEquals(data)) throw IllegalStateException("the read-back differs")
    }

    private class Doc(val name: String, var bytes: ByteArray)

    private class FakeOps(
        var failOpen: Boolean = false,
        var failWrite: Boolean = false,
        var failRead: Boolean = false,
        var failReadAfterWrite: Boolean = false,
        var readTransform: ((ByteArray) -> ByteArray)? = null,
        var deleteFails: Boolean = false,
        var describeReturnsNull: Boolean = false,
        var reportSize: Boolean = true,
        var reportedSize: Long? = null,
    ) : BackupDocumentOps<Int> {
        val docs = LinkedHashMap<Int, Doc>()
        private var nextId = 100
        val log = mutableListOf<String>()
        private var written = false

        fun create(name: String, bytes: ByteArray = ByteArray(0)): Int = nextId++.also { docs[it] = Doc(name, bytes.copyOf()) }

        /** The log with each run of writes collapsed to one entry. */
        fun steps(): List<String> = log.filterIndexed { i, entry -> !(entry == "write" && i > 0 && log[i - 1] == entry) }

        override fun describe(handle: Int): BackupDocumentInfo? {
            log += "describe"
            val doc = docs[handle]
            if (describeReturnsNull || doc == null) return null
            return BackupDocumentInfo(doc.name, if (reportSize) reportedSize ?: doc.bytes.size.toLong() else null)
        }

        override fun openWrite(handle: Int): OutputStream {
            val doc = docs[handle] ?: throw IOException("no such document #$handle")
            log += "open"
            if (failOpen) throw IOException("open refused")
            doc.bytes = ByteArray(0)
            written = true
            return object : OutputStream() {
                var count = 0
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    log += "write"
                    if (failWrite && count > 0) throw IOException("write failed mid-stream")
                    doc.bytes += b.copyOfRange(off, off + len)
                    count += len
                }
            }
        }

        override fun openRead(handle: Int): InputStream {
            val doc = docs[handle] ?: throw IOException("no such document #$handle")
            log += "read"
            if (failRead || (failReadAfterWrite && written)) throw IOException("read refused")
            return ByteArrayInputStream(readTransform?.takeIf { written }?.invoke(doc.bytes) ?: doc.bytes)
        }

        override fun delete(handle: Int): Boolean {
            log += "delete"
            if (deleteFails) return false
            return docs.remove(handle) != null
        }
    }

    private fun publish(ops: FakeOps, target: Int, verify: (InputStream) -> Unit = verifyBytes) = BackupPublisher.publish(ops, target, source, verify = verify)

    private fun publishFails(ops: FakeOps, target: Int, verify: (InputStream) -> Unit = verifyBytes): BackupPublishException =
        assertFailsWith { publish(ops, target, verify) }

    @Test
    fun anEmptyPickedDocumentGetsTheArchiveUnderItsNameAndIsReadBackThroughAFreshOpen() {
        val ops = FakeOps()
        val doc = ops.create(name)

        val outcome = publish(ops, doc)

        assertEquals(BackupPublishOutcome(name, data.size.toLong()), outcome)
        assertContentEquals(data, ops.docs.getValue(doc).bytes)
        assertEquals(listOf("describe", "read", "open", "write", "read", "describe"), ops.steps())
    }

    @Test
    fun aDocumentThatHoldsDataIsRefusedBeforeAnyByteIsWritten() {
        val old = byteArrayOf(1, 2, 3)
        for (ops in listOf(FakeOps(), FakeOps(reportSize = false), FakeOps(reportedSize = 0L))) {
            val doc = ops.create(name, old)

            val e = publishFails(ops, doc)

            assertEquals(PublishFailure.NOT_EMPTY, e.failure)
            assertEquals(name, e.fileName)
            assertNull(e.removed)
            assertContentEquals(old, ops.docs.getValue(doc).bytes)
            assertFalse("open" in ops.log || "delete" in ops.log, "${ops.log}")
            assertEquals(ops.reportSize && ops.reportedSize == null, "read" !in ops.log, "${ops.log}")
        }
    }

    @Test
    fun theWritingHookRunsOnceTheDocumentIsKnownEmptyAndAThrowStopsBeforeAnyByte() {
        val ops = FakeOps()
        val doc = ops.create(name)
        BackupPublisher.publish(ops, doc, source, onWriting = { ops.log += "writing" }, verify = verifyBytes)
        assertEquals(listOf("describe", "read", "writing", "open", "write", "read", "describe"), ops.steps())

        val full = ops.create(name, byteArrayOf(1))
        var called = false
        assertFailsWith<BackupPublishException> { BackupPublisher.publish(ops, full, source, onWriting = { called = true }, verify = verifyBytes) }
        assertFalse(called)

        val empty = ops.create(name)
        ops.log.clear()
        assertFailsWith<IllegalStateException> { BackupPublisher.publish(ops, empty, source, onWriting = { error("no note") }, verify = verifyBytes) }
        assertFalse("open" in ops.log)
        assertTrue(empty in ops.docs)
    }

    @Test
    fun aDocumentWhoseEmptinessCannotBeReadIsRefusedAndKept() {
        val ops = FakeOps(failRead = true)
        val doc = ops.create(name)

        val e = publishFails(ops, doc)

        assertEquals(PublishFailure.NOT_CHECKED, e.failure)
        assertNull(e.removed)
        assertTrue(doc in ops.docs)
        assertFalse("open" in ops.log)
    }

    @Test
    fun anEmptyDocumentWithoutASizeOrADescriptionIsWritten() {
        for (ops in listOf(FakeOps(reportSize = false), FakeOps(describeReturnsNull = true))) {
            val doc = ops.create(name)
            val outcome = publish(ops, doc)
            assertContentEquals(data, ops.docs.getValue(doc).bytes)
            assertEquals(if (ops.describeReturnsNull) null else name, outcome.displayName)
        }
    }

    @Test
    fun anEmptyDocumentThatWillNotOpenForWritingIsRemoved() {
        val ops = FakeOps(failOpen = true)
        val doc = ops.create(name)

        val e = publishFails(ops, doc)

        assertEquals(PublishFailure.NOT_OPENED, e.failure)
        assertEquals(true, e.removed)
        assertFalse(doc in ops.docs)
    }

    @Test
    fun aDocumentWrittenButNotVerifiedIsRemovedWhateverFailed() {
        fun failure(configure: FakeOps.() -> Unit, verify: (InputStream) -> Unit = verifyBytes): Pair<BackupPublishException, Boolean> {
            val ops = FakeOps().apply(configure)
            val doc = ops.create(name)
            return publishFails(ops, doc, verify) to (doc in ops.docs)
        }
        val corrupt: (ByteArray) -> ByteArray = { it.copyOf().also { b -> b[10] = (b[10] + 1).toByte() } }
        for ((case, expected) in listOf(
            failure({ failWrite = true }) to PublishFailure.NOT_WRITTEN,
            failure({ failReadAfterWrite = true }) to PublishFailure.NOT_READ_BACK,
            failure({ readTransform = corrupt }) to PublishFailure.NOT_VERIFIED,
            failure({ readTransform = { it.copyOf(it.size - 1) } }) to PublishFailure.NOT_VERIFIED,
            failure({}, verify = { throw IOException("read-back cut") }) to PublishFailure.NOT_READ_BACK,
        )) {
            val (e, kept) = case
            assertEquals(expected, e.failure)
            assertEquals(true, e.removed)
            assertFalse(kept)
        }
    }

    @Test
    fun aDeleteThatFailsIsReportedAndTheDocumentStays() {
        val ops = FakeOps(deleteFails = true, readTransform = { ByteArray(1) })
        val doc = ops.create(name)

        val e = publishFails(ops, doc)

        assertEquals(PublishFailure.NOT_VERIFIED, e.failure)
        assertEquals(false, e.removed)
        assertTrue(doc in ops.docs)
    }

    @Test
    fun anErrorInTheVerifyStepStillRemovesTheDocument() {
        val ops = FakeOps()
        val doc = ops.create(name)

        assertFailsWith<AssertionError> { publish(ops, doc) { throw AssertionError("boom") } }

        assertFalse(doc in ops.docs)
    }

    @Test
    fun aSourceShorterThanItsSizeIsAWriteFailure() {
        val ops = FakeOps()
        val doc = ops.create(name)
        val short = BackupSource(data.size + 10L) { ByteArrayInputStream(data) }

        val e = assertFailsWith<BackupPublishException> { BackupPublisher.publish(ops, doc, short, verify = verifyBytes) }

        assertEquals(PublishFailure.NOT_WRITTEN, e.failure)
        assertFalse(doc in ops.docs)
    }

    @Test
    fun anEmptyOrUnopenableSourceTouchesNoDocument() {
        val ops = FakeOps()
        val doc = ops.create(name)

        assertFailsWith<IllegalArgumentException> { BackupPublisher.publish(ops, doc, BackupSource(0L) { ByteArrayInputStream(ByteArray(0)) }, verify = verifyBytes) }
        assertFailsWith<IOException> { BackupPublisher.publish(ops, doc, BackupSource(5L) { throw IOException("staging gone") }, verify = verifyBytes) }

        assertEquals(emptyList(), ops.log)
        assertTrue(doc in ops.docs)
    }
}
