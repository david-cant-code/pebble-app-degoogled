package com.anopticlabs.gravel.notebook.backup

import kotlin.test.Test
import kotlin.test.assertEquals

/** The worker's slot and result mapping, and Settings' status line precedence. */
class AutoBackupWorkTest {

    @Test
    fun aChangeWhileTheChangeSlotRunsAsksTheOtherSlot() {
        assertEquals(AutoBackupRunner.SLOT_CHANGE, changeSlotFor(null))
        assertEquals(AutoBackupRunner.SLOT_NEXT, changeSlotFor(AutoBackupRunner.SLOT_CHANGE))
        assertEquals(AutoBackupRunner.SLOT_CHANGE, changeSlotFor(AutoBackupRunner.SLOT_NEXT))
        assertEquals(AutoBackupRunner.SLOT_CHANGE, changeSlotFor(AutoBackupRunner.SLOT_PERIODIC))
        assertEquals(AutoBackupRunner.SLOT_CHANGE, changeSlotFor(AutoBackupRunner.SLOT_NOW))
    }

    @Test
    fun aRunReadsItsSlotFromItsTag() {
        assertEquals("next", slotOf(setOf("AutoBackupWorker", WorkAutoBackupScheduler.SLOT_TAG + "next")))
        assertEquals(AutoBackupRunner.SLOT_PERIODIC, slotOf(setOf("AutoBackupWorker")))
        assertEquals(AutoBackupRunner.SLOT_CHECK, slotOf(setOf(WorkAutoBackupScheduler.SLOT_TAG + AutoBackupRunner.SLOT_CHECK)))
    }

    @Test
    fun onlyAFailureTheUserWasToldOfEndsTheWork() {
        assertEquals(WorkOutcome.SUCCESS, outcomeOf(AutoRunResult.Off))
        assertEquals(WorkOutcome.SUCCESS, outcomeOf(AutoRunResult.Confirmed))
        assertEquals(WorkOutcome.RETRY, outcomeOf(AutoRunResult.Busy))
        assertEquals(WorkOutcome.RETRY, outcomeOf(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = true)))
        assertEquals(WorkOutcome.FAILURE, outcomeOf(AutoRunResult.Failed(AutoBackupProblem.NOT_SAVED, retry = false)))
    }

    private val entry = AutoBackupEntry("u", "n.gnbk", 100L, 1L, "h", notes = 2, reminders = 1, events = 0, fingerprint = "f", keyCreatedAtMillis = 1L)
    private val on = AutoBackupStatus(loaded = true, setUp = true, enabled = true, newest = entry)

    @Test
    fun theStatusLineShowsTheMostPressingStateFirst() {
        val stopped = AutoBackupFailure(AutoBackupProblem.FOLDER_GONE, 5L, tries = 1, reported = true)
        val failed = AutoBackupFailure(AutoBackupProblem.NOT_SAVED, 5L, tries = 4, reported = true)
        assertEquals(AutoBackupLine.Unreadable, autoBackupLine(on.copy(unreadable = true, running = true)))
        assertEquals(AutoBackupLine.Running, autoBackupLine(on.copy(running = true, failure = stopped)))
        assertEquals(AutoBackupLine.Stopped(AutoBackupProblem.FOLDER_GONE), autoBackupLine(on.copy(failure = stopped, enabled = false)))
        assertEquals(AutoBackupLine.LastFailed(AutoBackupProblem.NOT_SAVED), autoBackupLine(on.copy(failure = failed)))
        assertEquals(AutoBackupLine.Off, autoBackupLine(on.copy(enabled = false, stale = true)))
        assertEquals(AutoBackupLine.LastFailed(AutoBackupProblem.NOT_SAVED), autoBackupLine(on.copy(failure = failed, stale = true, lastConfirmedAtMillis = 100L)))
        assertEquals(AutoBackupLine.Stale(100L), autoBackupLine(on.copy(stale = true, lastConfirmedAtMillis = 100L)))
        assertEquals(AutoBackupLine.NoBackup, autoBackupLine(on.copy(newest = null)))
        assertEquals(AutoBackupLine.Last(entry, null), autoBackupLine(on.copy(lastConfirmedAtMillis = 101L)), "the shown backup's own check time")
        val checked = entry.copy(checkedAtMillis = 101L)
        assertEquals(AutoBackupLine.Last(checked, 101L), autoBackupLine(on.copy(newest = checked)))
    }
}
