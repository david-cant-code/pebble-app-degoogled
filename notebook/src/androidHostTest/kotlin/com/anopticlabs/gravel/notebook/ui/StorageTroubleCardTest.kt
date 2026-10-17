package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.storage.NotebookPart
import com.anopticlabs.gravel.notebook.storage.RecoveryPlan
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageTroubleCardTest {
    private val idle = RecoveryRunState()

    private fun damaged(vararg parts: NotebookPart) = StorageTrouble.Damaged(parts.toSet())

    private fun texts(trouble: StorageTrouble, run: RecoveryRunState = idle, at: Long? = null) = troubleLines(trouble, run, at).map { it.text }

    @Test
    fun aGoneKeyOffersStartOverAndRestoreUntilTheKeyStoreRefusesAStartOver() {
        assertEquals(listOf(TroubleAction.START_OVER, TroubleAction.RESTORE), troubleActions(StorageTrouble.KeyGone, idle))
        assertEquals(listOf(TroubleText.KEY_GONE, TroubleText.KEY_GONE_CHOICES), texts(StorageTrouble.KeyGone))

        val refused = RecoveryRunState(failed = RecoveryFailure.KEY_STORE, failedOn = StorageTrouble.KeyGone, runs = 1)
        assertEquals(listOf(TroubleAction.START_OVER_ANYWAY), troubleActions(StorageTrouble.KeyGone, refused))
        assertEquals(listOf(TroubleText.KEY_GONE, TroubleText.KEY_STORE), texts(StorageTrouble.KeyGone, refused))

        val notFinished = RecoveryRunState(failed = RecoveryFailure.NOT_FINISHED, failedOn = StorageTrouble.KeyGone, runs = 1)
        assertEquals(listOf(TroubleAction.START_OVER, TroubleAction.RESTORE), troubleActions(StorageTrouble.KeyGone, notFinished))
        assertEquals(listOf(TroubleText.KEY_GONE, TroubleText.KEY_GONE_CHOICES, TroubleText.FAILED), texts(StorageTrouble.KeyGone, notFinished))
    }

    @Test
    fun anUnreadableNotebookOffersOnlyTryAgain() {
        assertEquals(listOf(TroubleAction.TRY_AGAIN), troubleActions(StorageTrouble.Unreadable, idle))
        assertEquals(listOf(TroubleText.UNREADABLE), texts(StorageTrouble.Unreadable))
    }

    @Test
    fun aDamagedPartGetsARebuildAStartFreshOrBothAStartFreshAndARestore() {
        assertEquals(listOf(TroubleAction.REBUILD), troubleActions(damaged(NotebookPart.NOTE_LIST), idle))
        assertEquals(listOf(TroubleText.DAMAGED, TroubleText.REBUILD), texts(damaged(NotebookPart.NOTE_LIST)))
        assertEquals(TroubleText.REBUILD_MANY, texts(damaged(NotebookPart.NOTE_LIST, NotebookPart.RECORDINGS)).last())

        assertEquals(listOf(TroubleAction.START_FRESH), troubleActions(damaged(NotebookPart.COMPLETIONS), idle))
        assertEquals(listOf(TroubleText.DAMAGED, TroubleText.NOT_IN_BACKUPS), texts(damaged(NotebookPart.COMPLETIONS), at = 5L))
        val mixed = damaged(NotebookPart.REMINDERS, NotebookPart.COMPLETIONS)
        assertEquals(listOf(TroubleAction.START_FRESH, TroubleAction.RESTORE), troubleActions(mixed, idle))
        assertEquals(TroubleText.DAMAGED_FILE, texts(mixed)[1])

        val trouble = damaged(NotebookPart.RECORDINGS, NotebookPart.REMINDERS)
        assertEquals(listOf(TroubleAction.START_FRESH, TroubleAction.RESTORE), troubleActions(trouble, idle))
        assertEquals(
            listOf(
                TroubleLine(TroubleText.DAMAGED, listOf(NotebookPart.REMINDERS, NotebookPart.RECORDINGS)),
                TroubleLine(TroubleText.DAMAGED_AUTOMATIC, at = 5L),
                TroubleLine(TroubleText.ALSO_REBUILT, listOf(NotebookPart.RECORDINGS)),
            ),
            troubleLines(trouble, idle, 5L),
        )
        assertEquals(TroubleText.DAMAGED_FILE, texts(trouble)[1])
    }

    @Test
    fun aFailureLineShowsOnlyBesideTheKindOfTroubleItWasFor() {
        val failed = RecoveryRunState(failed = RecoveryFailure.NOT_FINISHED, failedOn = damaged(NotebookPart.REMINDERS), runs = 1)
        assertEquals(TroubleText.FAILED, texts(damaged(NotebookPart.FOLDERS), failed).last())
        assertFalse(TroubleText.FAILED in texts(StorageTrouble.Unreadable, failed))
    }

    @Test
    fun theRemindersSentenceCoversTheirHistory() {
        val all = setOf(NotebookPart.REMINDER_HISTORY, NotebookPart.COMPLETIONS, NotebookPart.REMINDERS)
        assertEquals(listOf(NotebookPart.REMINDERS, NotebookPart.COMPLETIONS), outcomeParts(all))
        assertEquals(listOf(NotebookPart.REMINDER_HISTORY), outcomeParts(setOf(NotebookPart.REMINDER_HISTORY)))
    }

    @Test
    fun onlyARestoreThatLeavesTheRemindersSaysTheyKeepTheirTimes() {
        assertTrue(null.keepsReminders())
        assertTrue(RecoveryPlan.StartFresh(setOf(NotebookPart.FOLDERS)).keepsReminders())
        assertFalse(RecoveryPlan.StartFresh(setOf(NotebookPart.REMINDERS)).keepsReminders())
        assertFalse(RecoveryPlan.StartOver.keepsReminders())
    }
}
