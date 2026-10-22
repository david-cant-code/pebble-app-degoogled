package com.anopticlabs.gravel.notebook.backup

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BackupDocuments] through the real `DocumentsContract` stack against [TestDocumentsProvider],
 * a provider in the test package's own process, reached only through URI grants. The host suite
 * pins the publish sequence on a fake; this pins the binding over a real cross-process provider.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.backup.BackupDocumentsContractTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class BackupDocumentsContractTest {

    private lateinit var docs: TestDocsClient
    private val finalName = "gravel-notebook-2026-10-02-1430.gnbk"
    private val data = ByteArray(200_000) { (it * 13 + 5).toByte() }

    private fun ops() = BackupDocuments(docs.resolver)

    private val source = BackupSource(data.size.toLong()) { ByteArrayInputStream(data) }

    /** Stands in for the archive reader: the read-back must hold exactly [data]. */
    private val verifyBytes: (InputStream) -> Unit = { input ->
        if (!input.readBytes().contentEquals(data)) throw IllegalStateException("the read-back differs")
    }

    private fun publish(uri: Uri) = BackupPublisher.publish(ops(), uri, source, verify = verifyBytes)

    @Before
    fun setUp() {
        docs = TestDocsClient(InstrumentationRegistry.getInstrumentation().targetContext)
        docs.connect()
    }

    @After
    fun tearDown() {
        docs.reset()
    }

    @Test
    fun describeReportsNameAndSizeFromTheRealCursor() {
        val uri = docs.createGranted(finalName)

        assertEquals(BackupDocumentInfo(finalName, 0L), ops().describe(uri))
        assertEquals(1234L, ops().describe(docs.createGranted("sized.gnbk", ByteArray(1234)))?.sizeBytes)
        docs.configure(reportSize = false)
        assertNull(ops().describe(uri)?.sizeBytes)

        val missing = TestDocumentsProvider.documentUri("does-not-exist")
        docs.grant(missing)
        assertNull(ops().describe(missing))
    }

    @Test
    fun publishLandsExactBytesInThePickedDocument() {
        val uri = docs.createGranted(finalName)

        assertEquals(BackupPublishOutcome(finalName, data.size.toLong()), publish(uri))
        assertEquals(listOf(finalName), docs.names())
        assertContentEquals(data, docs.bytesOf(finalName))
    }

    @Test
    fun aProviderThatLiesOnReadBackFailsVerificationAndTheFileIsRemoved() {
        docs.configure(corruptReads = true)
        val uri = docs.createGranted(finalName)

        val e = assertFailsWith<BackupPublishException> { publish(uri) }

        assertEquals(PublishFailure.NOT_VERIFIED, e.failure)
        assertEquals(true, e.removed)
        assertEquals(emptyList(), docs.names())
    }

    @Test
    fun aDocumentThatHoldsDataIsRefusedAndKept() {
        val old = ByteArray(5_000) { 0x42 }
        val existing = docs.createGranted(finalName, old)
        docs.configure(reportSize = false)

        val e = assertFailsWith<BackupPublishException> { publish(existing) }

        assertEquals(PublishFailure.NOT_EMPTY, e.failure)
        assertNull(e.removed)
        assertContentEquals(old, docs.bytesOf(finalName))
    }

    @Test
    fun aRefusedOpenRemovesThePickersEmptyPlaceholder() {
        docs.configure(refuseWrite = true)
        val placeholder = docs.createGranted(finalName)

        val e = assertFailsWith<BackupPublishException> { publish(placeholder) }

        assertEquals(PublishFailure.NOT_OPENED, e.failure)
        assertEquals(true, e.removed)
        assertEquals(emptyList(), docs.names())
    }

    @Test
    fun deleteRemovesThePickerCreatedEmptyFileOnce() {
        val uri = docs.createGranted(finalName)
        val ops = ops()

        assertTrue(ops.delete(uri))
        assertEquals(emptyList(), docs.names())
        assertFalse(ops.delete(uri))
    }

    @Test
    fun withoutAGrantTheAppCannotTouchTheDocument() {
        docs.control(TestDocumentsProvider.METHOD_CREATE, "ungranted.gnbk")
        val ops = ops()
        val uri = TestDocumentsProvider.documentUri("ungranted.gnbk")

        assertNull(ops.describe(uri))
        assertFailsWith<SecurityException> { ops.openWrite(uri) }
        assertFalse(ops.delete(uri))
        assertEquals(listOf("ungranted.gnbk"), docs.names())
    }

    @Test
    fun aHoldKeepsAPersistableGrantUntilReleasedAndFailsWithoutOne() {
        val persisted = { docs.resolver.persistedUriPermissions.map { it.uri } }
        val holdable = docs.createGranted(finalName, persistable = true)
        val plain = docs.createGranted("plain.gnbk")

        assertTrue(ops().hold(holdable))
        assertTrue(holdable in persisted())
        ops().release(holdable)
        assertFalse(holdable in persisted())

        assertFalse(ops().hold(plain))
        assertFalse(plain in persisted())
    }
}
