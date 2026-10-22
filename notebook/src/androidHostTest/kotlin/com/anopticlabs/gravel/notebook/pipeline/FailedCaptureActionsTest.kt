package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.MAX_QUEUE_ATTEMPTS
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FailedCaptureActionsTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val queue = QueueStore(root, FakeCryptographer(softwareCrypto()))
    private var running: String? = null
    private var runs = 0
    private val actions = FailedCaptureActions(queue, processing = { it == running }, requestAgain = { runs++ })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun flagged(id: String) {
        queue.enqueue(QueuedCapture(id, "$id.wav", 1L, MAX_QUEUE_ATTEMPTS, true, failure = "no_speech"), byteArrayOf(1))
    }

    private fun attempts(id: String) = queue.readQueue().single { it.id == id }.attempts

    @Test
    fun theCaptureBeingProcessedIsNeitherRetriedNorDeleted() = runBlocking<Unit> {
        flagged("last")
        running = "last"

        actions.retry(setOf("last"))
        assertEquals(QueueStore.FailedRemoval.NOT_FLAGGED, actions.delete("last"))

        assertEquals(MAX_QUEUE_ATTEMPTS, attempts("last"))
        assertEquals(0, runs)
    }

    @Test
    fun aRetryAsksForARunOnlyWhenItChangedSomething() = runBlocking<Unit> {
        flagged("a")
        flagged("b")

        actions.retry(setOf("a"))
        assertEquals(1, runs)
        assertEquals(0, attempts("a"))
        assertEquals(MAX_QUEUE_ATTEMPTS, attempts("b"))

        actions.retry(setOf("a"))
        assertEquals(1, runs)
    }

    @Test
    fun aDeleteRemovesAFlaggedCaptureThatIsNotRunning() = runBlocking<Unit> {
        flagged("a")
        running = "other"
        assertEquals(QueueStore.FailedRemoval.REMOVED, actions.delete("a"))
        assertTrue(queue.readQueue().isEmpty())
    }
}
