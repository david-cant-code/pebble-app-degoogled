package com.anopticlabs.gravel.accessibility

import androidx.compose.material3.BottomAppBar
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onNodeWithText
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import com.anopticlabs.gravel.notebook.ui.PartProblem
import com.anopticlabs.gravel.notebook.ui.RecoveryRunState
import com.anopticlabs.gravel.notebook.ui.StorageProblem
import com.anopticlabs.gravel.notebook.ui.StorageTroubleCardContent
import coredevices.coreapp.ui.screens.ExportLogsButton
import coredevices.coreapp.ui.screens.SendReportButton
import coredevices.pebble.ui.AddPebbleButton
import coredevices.pebble.ui.CalendarEntryRow
import coredevices.pebble.ui.CalendarPermissionCard
import coredevices.pebble.ui.SendMessageButton
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The screen pieces whose colors the theme change had to follow: the Notebook's storage cards, Calendar's permission
 * card and not-synced notice, the send buttons (idle, and their spinners and check), and the Devices tab's add button.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.accessibility.ThemeFixPiecesDrawnTextTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(Parameterized::class)
class ThemeFixPiecesDrawnTextTest(check: DrawnCheck) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun checks() = DrawnCheck.entries
    }

    @get:Rule
    val drawn = DrawnText(check)

    private val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    @Test
    fun storageTroubleActions() {
        drawn.setContent { StorageTroubleCardContent(StorageTrouble.KeyGone, RecoveryRunState(), automaticBackupAt = null) {} }
        drawn.assertReadable()
    }

    @Test
    fun storageTroubleSpinner() {
        drawn.setContent { StorageTroubleCardContent(StorageTrouble.Unreadable, RecoveryRunState(busy = true), automaticBackupAt = null) {} }
        drawn.assertReadable(spinner)
    }

    @Test
    fun partProblemTryAgain() {
        drawn.setContent { PartProblem(R.string.notebook_folder_list_unreadable, StorageProblem.UNREADABLE) {} }
        drawn.assertReadable()
    }

    @Test
    fun calendarPermissionCard() {
        drawn.setContent { CalendarPermissionCard {} }
        drawn.assertReadable()
    }

    @Test
    fun calendarNotSynced() {
        val notSynced = CalendarEntity(
            platformId = "1", name = "Work", ownerName = "Owner", ownerId = "1", color = 0, enabled = true, syncEvents = false,
        )
        drawn.setContent { CalendarEntryRow(notSynced) {} }
        drawn.compose.onNodeWithText("Not synced by Android", substring = true).assertExists()
        drawn.assertReadable()
    }

    @Test
    fun exportLogsIdle() {
        drawn.setContent { BottomAppBar { ExportLogsButton(sending = false, enabled = true) {} } }
        drawn.assertReadable()
    }

    @Test
    fun sendReportIdle() {
        drawn.setContent { BottomAppBar { SendReportButton(sending = false, sent = false, enabled = true) {} } }
        drawn.assertReadable()
    }

    @Test
    fun contactDeveloperIdle() {
        drawn.setContent { SendMessageButton(sending = false, enabled = true) {} }
        drawn.assertReadable()
    }

    @Test
    fun exportLogsSpinner() {
        drawn.setContent { BottomAppBar { ExportLogsButton(sending = true, enabled = false) {} } }
        drawn.assertContrast(spinner)
    }

    @Test
    fun sendReportSpinner() {
        drawn.setContent { BottomAppBar { SendReportButton(sending = true, sent = false, enabled = false) {} } }
        drawn.assertContrast(spinner)
    }

    @Test
    fun sendReportSentCheck() {
        drawn.setContent { BottomAppBar { SendReportButton(sending = false, sent = true, enabled = false) {} } }
        drawn.assertReadable(hasContentDescription("Success"))
    }

    @Test
    fun contactDeveloperSpinner() {
        drawn.setContent { SendMessageButton(sending = true, enabled = false) {} }
        drawn.assertContrast(spinner)
    }

    @Test
    fun addButtonOpen() {
        drawn.setContent { AddPebbleButton(expanded = true) {} }
        drawn.assertContrast(hasContentDescription("Add a Pebble"))
    }

    @Test
    fun addButtonClosed() {
        drawn.setContent { AddPebbleButton(expanded = false) {} }
        drawn.assertContrast(hasContentDescription("Add a Pebble"))
    }
}
