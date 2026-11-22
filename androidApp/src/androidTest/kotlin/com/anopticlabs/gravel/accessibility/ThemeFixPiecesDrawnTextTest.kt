package com.anopticlabs.gravel.accessibility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import com.anopticlabs.gravel.notebook.ui.PartProblem
import com.anopticlabs.gravel.notebook.ui.RecoveryRunState
import com.anopticlabs.gravel.notebook.ui.StorageProblem
import com.anopticlabs.gravel.notebook.ui.StorageTroubleCardContent
import coredevices.coreapp.ui.screens.AndroidAttachmentButton
import coredevices.coreapp.ui.screens.BugReportBar
import coredevices.coreapp.ui.screens.ExportLogsButton
import coredevices.coreapp.ui.screens.SendReportButton
import coredevices.pebble.ui.AddPebbleButton
import coredevices.pebble.ui.CalendarEntryRow
import coredevices.pebble.ui.CalendarPermissionCard
import coredevices.pebble.ui.SendMessageButton
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screen pieces whose colors the theme change had to follow: the Notebook's storage cards, Calendar's permission
 * card and not-synced notice, the send buttons (idle, and their spinners and check), the Export logs bar, and the
 * Devices tab's add button.
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
    fun exportLogsBar() = exportLogsBar(attached = 0)

    @Test
    fun exportLogsBarWithAttachments() = exportLogsBar(attached = 1)

    // The bar as the Export logs screen draws it on Android: Export logs goes under the attachment button at the
    // largest font size and shares its line at font 1.0.
    private fun exportLogsBar(attached: Int) {
        drawn.setContent {
            BugReportBar(attachments = { AndroidAttachmentButton(attached) {} }, send = { ExportLogsButton(sending = false, enabled = true) {} })
        }
        drawn.assertReadable()
        val attach = drawn.compose.onNode(hasText(if (attached > 0) "Remove $attached file(s)" else "Add Images") and hasClickAction())
            .getBoundsInRoot()
        val export = drawn.compose.onNode(hasText("Export logs") and hasClickAction()).getBoundsInRoot()
        if (drawn.check == DrawnCheck.FONT) {
            assertTrue(export.top >= attach.bottom, "Export logs under the attachment button")
        } else {
            assumeTrue("font 1.0 only", drawn.compose.density.fontScale == 1f)
            assertEquals(attach.top.value, export.top.value, 0.5f, "Export logs beside the attachment button at font 1.0")
        }
    }

    // At font 1.0 the bar lays out as the BottomAppBar it replaced, up to the gap rounding below, drawn under it as the
    // screen drew it.
    @Test
    fun exportLogsBarAtFontOneAsBefore() {
        val barGap = 3.dp // BugReportBar's spacing
        drawn.setContent {
            Column {
                Box(Modifier.testTag("bar")) {
                    BugReportBar(attachments = { AndroidAttachmentButton(0) {} }, send = { ExportLogsButton(sending = false, enabled = true) {} })
                }
                Box(Modifier.testTag("before")) {
                    BottomAppBar(windowInsets = BottomAppBarDefaults.windowInsets.union(WindowInsets.ime), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End)) {
                            AndroidAttachmentButton(0) {}
                            Spacer(Modifier.weight(1.0f))
                            ExportLogsButton(sending = false, enabled = true) {}
                        }
                    }
                }
            }
        }
        assumeTrue("font 1.0 only", drawn.check == DrawnCheck.CONTRAST && drawn.compose.density.fontScale == 1f)
        val barNode = drawn.compose.onNodeWithTag("bar", useUnmergedTree = true).fetchSemanticsNode()
        val bar = barNode.boundsInRoot
        val before = drawn.compose.onNodeWithTag("before", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before.size, bar.size, "the bar's size")
        // FlowRow sizes the Spacer with each gap rounded up and places the line with each gap rounded to the nearest
        // pixel (foundation-layout-android 1.11.2, FlowLayout.kt breakDownItems into RowColumnMeasurePolicy.kt measure;
        // Arrangement.SpacedAligned.arrange), so its end-aligned line starts later by the difference for both gaps.
        val gapPx = with(barNode.layoutInfo.density) { barGap.toPx() }
        val shift = 2 * (ceil(gapPx).toInt() - gapPx.roundToInt())
        for ((text, dx) in listOf("Add Images" to shift, "Export logs" to 0)) {
            val (now, then) = drawn.compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot }
            assertEquals(then.translate(-before.topLeft).translate(dx.toFloat(), 0f), now.translate(-bar.topLeft), "$text in the bar, $dx px right of before")
        }
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
