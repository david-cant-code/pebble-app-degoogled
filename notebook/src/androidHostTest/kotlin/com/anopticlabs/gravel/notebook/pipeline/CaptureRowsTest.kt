package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureRowsTest {

    private val rows = CaptureRows()

    @Test
    fun aSettledRowCanBeDismissed() {
        rows.queued("a", 1L)
        rows.settle("a", CaptureQueueItem.Status.NOTE_CREATED, CaptureQueueItem.Kind.NOTE, "milk")
        rows.removeSettled("a")
        assertTrue(rows.items.value.isEmpty())
    }

    @Test
    fun aRowTakenUpAgainStaysWhenDismissed() {
        rows.queued("a", 1L)
        rows.settle("a", CaptureQueueItem.Status.FAILED, null, "tried again later")
        rows.queued("a", 1L)
        rows.removeSettled("a")
        assertEquals(listOf(CaptureQueueItem.Status.QUEUED), rows.items.value.map { it.status })
    }
}
