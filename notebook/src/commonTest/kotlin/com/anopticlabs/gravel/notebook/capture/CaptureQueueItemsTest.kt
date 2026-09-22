package com.anopticlabs.gravel.notebook.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureQueueItemsTest {

    private val q = CaptureQueueItem.Status.QUEUED
    private val p = CaptureQueueItem.Status.PROCESSING
    private val note = CaptureQueueItem.Status.NOTE_CREATED
    private val rem = CaptureQueueItem.Status.REMINDER_SET
    private val fail = CaptureQueueItem.Status.FAILED

    private fun item(id: String, status: CaptureQueueItem.Status, stage: CaptureStage? = null, createdAt: Long = 0L) =
        CaptureQueueItem(id, createdAt, status, stage = stage)

    @Test
    fun addIsIdempotentOnId() {
        assertEquals(listOf("a"), addCaptureItem(emptyList(), item("a", q)).map { it.id })
        val start = listOf(item("a", p, CaptureStage.TRANSCRIBING))
        assertEquals(start, addCaptureItem(start, item("a", q)))
    }

    @Test
    fun stageMovesAnInFlightRowOnly() {
        val started = withCaptureItemStage(listOf(item("a", q)), "a", CaptureStage.TRANSCRIBING).single()
        assertEquals(p, started.status)
        assertEquals(CaptureStage.TRANSCRIBING, started.stage)
        assertEquals(CaptureStage.THINKING, withCaptureItemStage(listOf(started), "a", CaptureStage.THINKING).single().stage)
        val settled = listOf(item("a", note))
        assertEquals(settled, withCaptureItemStage(settled, "a", CaptureStage.THINKING))
        val start = listOf(item("a", q))
        assertEquals(start, withCaptureItemStage(start, "ghost", CaptureStage.THINKING))
    }

    @Test
    fun requeueTurnsAnInFlightRowBackToQueuedAndLeavesASettledOneAlone() {
        val processing = CaptureQueueItem("a", 1, CaptureQueueItem.Status.PROCESSING, stage = CaptureStage.TRANSCRIBING)
        val settled = CaptureQueueItem("b", 2, CaptureQueueItem.Status.NOTE_CREATED, resultLabel = "Milk")
        assertEquals(
            listOf(CaptureQueueItem("a", 1, CaptureQueueItem.Status.QUEUED), settled),
            withRequeuedCaptureItem(listOf(processing, settled), "a").let { withRequeuedCaptureItem(it, "b") },
        )
    }

    @Test
    fun settleSetsTheTerminalFieldsAndClearsTheStage() {
        val row = withSettledCaptureItem(listOf(item("a", p, CaptureStage.CREATING_NOTE)), "a", note, CaptureQueueItem.Kind.NOTE, "Groceries", noteId = "0123456789abcdef0123456789abcdef").single()
        assertEquals(note, row.status)
        assertEquals(CaptureQueueItem.Kind.NOTE, row.kind)
        assertEquals("Groceries", row.resultLabel)
        assertEquals("0123456789abcdef0123456789abcdef", row.noteId)
        assertNull(row.stage)
        assertFalse(row.inFlight)
        val armed = withSettledCaptureItem(listOf(item("a", p, CaptureStage.SETTING_REMINDER)), "a", rem, CaptureQueueItem.Kind.REMINDER, "Reminder set for 3 PM", reminderId = 42).single()
        assertEquals(42, armed.reminderId)
        val other = withSettledCaptureItem(listOf(item("a", p, CaptureStage.TRANSCRIBING), item("b", q)), "a", note, CaptureQueueItem.Kind.NOTE, "T")
        assertEquals(item("b", q), other.first { it.id == "b" })
    }

    @Test
    fun removeDropsById() {
        assertEquals(listOf("b"), withoutCaptureItem(listOf(item("a", note), item("b", q)), "a").map { it.id })
    }

    @Test
    fun aStaleReminderRowLosesItsTapTarget() {
        val row = CaptureQueueItem("a", 0L, rem, kind = CaptureQueueItem.Kind.REMINDER, resultLabel = "Reminder set for 3 PM", reminderId = 5)
        val out = reconcileStaleReminderRows(listOf(row), storedReminderIds = setOf(1, 2)).single()
        assertNull(out.reminderId)
        assertEquals(rem, out.status)
        assertEquals("Reminder set for 3 PM", out.resultLabel)
        assertEquals(listOf(row), reconcileStaleReminderRows(listOf(row), setOf(5, 7)))
        val untouched = listOf(
            CaptureQueueItem("note", 0L, note, kind = CaptureQueueItem.Kind.NOTE, noteId = "0123456789abcdef0123456789abcdef"),
        )
        assertEquals(untouched, reconcileStaleReminderRows(untouched, emptySet()))
    }

    @Test
    fun batchCounterResetsWhenNothingIsInFlight() {
        assertEquals(0, captureBatchAfterSettle(listOf(item("a", note), item("b", rem), item("c", fail)), current = 3))
        assertEquals(3, captureBatchAfterSettle(listOf(item("a", note), item("b", p, CaptureStage.THINKING)), current = 3))
    }

    @Test
    fun progressCountsUpAsTheBatchDrains() {
        assertFalse(captureQueueProgress(emptyList(), 0).active)
        assertFalse(captureQueueProgress(listOf(item("a", note), item("b", rem), item("c", fail)), 0).active)
        val s1 = captureQueueProgress(listOf(item("a", p, CaptureStage.TRANSCRIBING), item("b", q), item("c", q)), 3)
        assertEquals(CaptureQueueProgress(1, 3, CaptureStage.TRANSCRIBING), s1)
        assertEquals(CaptureQueueProgress(2, 3, CaptureStage.THINKING), captureQueueProgress(listOf(item("a", note), item("b", p, CaptureStage.THINKING), item("c", q)), 3))
        assertEquals(CaptureQueueProgress(3, 3, CaptureStage.CREATING_NOTE), captureQueueProgress(listOf(item("a", note), item("b", rem), item("c", p, CaptureStage.CREATING_NOTE)), 3))
        assertFalse(captureQueueProgress(listOf(item("a", note), item("b", rem), item("c", note)), 3).active)
        assertEquals(CaptureQueueProgress(2, 3, null), captureQueueProgress(listOf(item("a", note), item("b", q), item("c", q)), 3))
        assertEquals(CaptureQueueProgress(1, 2, CaptureStage.TRANSCRIBING), captureQueueProgress(listOf(item("a", p, CaptureStage.TRANSCRIBING), item("b", q)), 0))
    }

    @Test
    fun progressTextCarriesTheStageLabel() {
        assertEquals("", captureProgressText(captureQueueProgress(emptyList(), 0)))
        assertEquals("Processing 1 of 3 \u00B7 Transcribing", captureProgressText(captureQueueProgress(listOf(item("a", p, CaptureStage.TRANSCRIBING), item("b", q), item("c", q)), 3)))
        assertEquals("Processing 1 of 1 \u00B7 Setting your reminder", captureProgressText(captureQueueProgress(listOf(item("a", p, CaptureStage.SETTING_REMINDER)), 1)))
        assertEquals("Processing 2 of 2", captureProgressText(captureQueueProgress(listOf(item("a", note), item("b", q)), 2)))
    }

    @Test
    fun rowLabels() {
        assertEquals("Waiting to process", captureQueueRowLabel(item("a", q)))
        assertEquals("Thinking", captureQueueRowLabel(item("a", p, CaptureStage.THINKING)))
        assertEquals("Working", captureQueueRowLabel(item("a", p)))
        assertEquals("Groceries list", captureQueueRowLabel(CaptureQueueItem("a", 0L, note, resultLabel = "Groceries list")))
        assertEquals("Note saved", captureQueueRowLabel(item("a", note)))
        assertEquals("Reminder set", captureQueueRowLabel(item("b", rem)))
        assertEquals("Couldn't process this recording", captureQueueRowLabel(item("c", fail)))
    }

    @Test
    fun inFlightIsQueuedOrProcessing() {
        assertTrue(item("a", q).inFlight)
        assertTrue(item("a", p).inFlight)
        for (s in listOf(note, rem, fail)) assertFalse(item("a", s).inFlight, s.name)
    }

    @Test
    fun classifyStagePrefersCompletion() {
        assertEquals(CaptureStage.COMPLETING_TASK, captureStageForClassify(isCompletion = true, isReminder = true))
        assertEquals(CaptureStage.SETTING_REMINDER, captureStageForClassify(isCompletion = false, isReminder = true))
        assertEquals(CaptureStage.CREATING_NOTE, captureStageForClassify(isCompletion = false, isReminder = false))
    }

    @Test
    fun aTwoCaptureBatchCountsUpThenResets() {
        var list = emptyList<CaptureQueueItem>()
        var batch = 0
        list = addCaptureItem(list, item("a", q, createdAt = 1)); batch += 1
        list = addCaptureItem(list, item("b", q, createdAt = 2)); batch += 1
        list = withCaptureItemStage(list, "a", CaptureStage.TRANSCRIBING)
        assertEquals("Processing 1 of 2 \u00B7 Transcribing", captureProgressText(captureQueueProgress(list, batch)))
        list = withSettledCaptureItem(list, "a", note, CaptureQueueItem.Kind.NOTE, "Note A")
        batch = captureBatchAfterSettle(list, batch)
        assertEquals(2, batch)
        list = withCaptureItemStage(list, "b", CaptureStage.SETTING_REMINDER)
        assertEquals("Processing 2 of 2 \u00B7 Setting your reminder", captureProgressText(captureQueueProgress(list, batch)))
        list = withSettledCaptureItem(list, "b", rem, CaptureQueueItem.Kind.REMINDER, "Reminder set for 5:00 PM")
        batch = captureBatchAfterSettle(list, batch)
        assertEquals(0, batch)
        assertFalse(captureQueueProgress(list, batch).active)
        assertEquals(listOf(note, rem), list.map { it.status })
    }
}
