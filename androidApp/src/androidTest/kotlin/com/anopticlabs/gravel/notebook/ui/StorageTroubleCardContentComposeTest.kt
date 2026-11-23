package com.anopticlabs.gravel.notebook.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.storage.NotebookPart
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Each button on the Notebook's storage card reports the action its label names.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.notebook.ui.StorageTroubleCardContentComposeTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class StorageTroubleCardContentComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertButtons(trouble: StorageTrouble, run: RecoveryRunState, vararg buttons: Pair<Int, TroubleAction>) {
        val taps = mutableListOf<TroubleAction>()
        compose.setContent { StorageTroubleCardContent(trouble, run, automaticBackupAt = null) { taps += it } }
        for ((label, _) in buttons) compose.onNodeWithText(context.getString(label)).performClick()
        assertEquals(buttons.map { it.second }, taps)
    }

    @Test
    fun anUnreadablePartOffersTryAgain() =
        assertButtons(StorageTrouble.Unreadable, RecoveryRunState(), R.string.notebook_try_again to TroubleAction.TRY_AGAIN)

    @Test
    fun aLostKeyOffersStartOverAndRestore() = assertButtons(
        StorageTrouble.KeyGone,
        RecoveryRunState(),
        R.string.notebook_trouble_start_over to TroubleAction.START_OVER,
        R.string.notebook_restore to TroubleAction.RESTORE,
    )

    @Test
    fun aStartOverTheKeyStoreRefusedOffersStartOverAnyway() = assertButtons(
        StorageTrouble.KeyGone,
        RecoveryRunState(failed = RecoveryFailure.KEY_STORE, failedOn = StorageTrouble.KeyGone),
        R.string.notebook_trouble_start_over_anyway to TroubleAction.START_OVER_ANYWAY,
    )

    @Test
    fun damageThatCanBeRebuiltOffersRebuild() = assertButtons(
        StorageTrouble.Damaged(setOf(NotebookPart.NOTE_LIST)),
        RecoveryRunState(),
        R.string.notebook_trouble_rebuild to TroubleAction.REBUILD,
    )

    @Test
    fun damageABackupHoldsOffersStartFreshAndRestore() = assertButtons(
        StorageTrouble.Damaged(setOf(NotebookPart.FOLDERS)),
        RecoveryRunState(),
        R.string.notebook_trouble_start_fresh to TroubleAction.START_FRESH,
        R.string.notebook_restore to TroubleAction.RESTORE,
    )
}
