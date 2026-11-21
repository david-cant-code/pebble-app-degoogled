package com.anopticlabs.gravel.accessibility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.window.Dialog
import com.anopticlabs.gravel.notebook.sources.RecorderState
import com.anopticlabs.gravel.notebook.ui.CaptureBar
import com.anopticlabs.gravel.notebook.ui.DayCell
import com.anopticlabs.gravel.notebook.ui.DayMarks
import com.anopticlabs.gravel.notebook.ui.FolderTile
import com.anopticlabs.gravel.notebook.ui.NotebookSection
import com.anopticlabs.gravel.notebook.ui.ReminderEditorField
import com.anopticlabs.gravel.notebook.ui.SectionRow
import com.anopticlabs.gravel.notebook.ui.ServerCheckButtons
import com.anopticlabs.gravel.ui.SetupButtonRow
import com.anopticlabs.gravel.ui.SetupGuideFrame
import kotlinx.datetime.LocalDate
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Notebook pieces laid out again so their text fits at the largest font size: the record bar, the server-check buttons,
 * the section switch, the folder tiles, the reminder editor's rows, the setup guide's frame and buttons, and the
 * calendar's day marks. Sizes said to hold at font 1.0 are checked in the contrast run when the phone is at 1.0.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.accessibility.NotebookFontPiecesDrawnTextTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(Parameterized::class)
class NotebookFontPiecesDrawnTextTest(private val check: DrawnCheck) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun checks() = DrawnCheck.entries
    }

    @get:Rule
    val drawn = DrawnText(check)

    // The Notebook tab's pieces sit inside its 16 dp side padding.
    private val page = Modifier.padding(horizontal = 16.dp)

    @Test
    fun captureBar() {
        drawn.setContent {
            Box(page) {
                CaptureBar(
                    recordingSupported = true,
                    state = RecorderState.Idle,
                    notice = null,
                    micProblem = null,
                    onRecord = {},
                    onStop = {},
                    onNewNote = {},
                    onOpenSettings = {},
                )
            }
        }
        drawn.assertReadable()
        // New note stays at the bar's end, on its own line at the largest font.
        val bar = drawn.compose.onRoot().getBoundsInRoot()
        val newNote = drawn.compose.onNode(hasText("New note") and hasClickAction()).getBoundsInRoot()
        assertEquals((bar.right - 16.dp).value, newNote.right.value, 0.5f, "New note's end")
        if (check == DrawnCheck.FONT) {
            val record = drawn.compose.onNode(hasContentDescription("Record") and hasClickAction()).getBoundsInRoot()
            assertTrue(newNote.top >= record.bottom, "New note under the record button")
        }
    }

    @Test
    fun serverCheckButtons() {
        drawn.setContent { Box(page) { ServerCheckButtons(openSpeechSettings = {}, tryAgain = {}) } }
        drawn.assertReadable()
    }

    @Test
    fun sectionSwitch() {
        drawn.setContent { Box(page) { SectionRow(NotebookSection.REMINDERS) {} } }
        drawn.assertReadable()
    }

    // The selected label shows whole when it fits: here at font 1.0 on a 360 dp-wide phone.
    @Test
    fun sectionSwitchOnANarrowPhone() {
        drawn.setContent { Box(Modifier.width(360.dp).then(page)) { SectionRow(NotebookSection.REMINDERS) {} } }
        assumeTrue("font 1.0 only", atFontOne())
        val label = drawn.compose.onNodeWithText("Reminders", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        label.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse(layouts.single().isLineEllipsized(0), "Reminders is shortened")
    }

    // Two tiles side by side, as the folder grid places them; their icons are decoration, drawn here as blank space.
    @Test
    fun folderTiles() {
        val icon = ImageVector.Builder("blank", 24.dp, 24.dp, 24f, 24f).build()
        drawn.setContent {
            Row(page, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { FolderTile(icon, "Unclassified", "12 notes", onOpen = {}) }
                Box(Modifier.weight(1f)) { FolderTile(icon, "New folder", null, onOpen = {}) }
            }
        }
        drawn.assertReadable()
        val folder = drawn.compose.onNode(hasText("Unclassified") and hasClickAction()).getBoundsInRoot()
        val newFolder = drawn.compose.onNode(hasText("New folder") and hasClickAction()).getBoundsInRoot()
        assertEquals(folder.height.value, newFolder.height.value, 0.5f, "tiles sharing a row")
        if (atFontOne()) {
            assertEquals(96f, folder.height.value, 0.5f, "tile height at font 1.0")
            val label = drawn.compose.onNodeWithText("New folder", useUnmergedTree = true).getBoundsInRoot()
            assertEquals((newFolder.bottom - 12.dp).value, label.bottom.value, 0.5f, "New folder's label at the tile's bottom")
        }
    }

    // In a popup like the reminder editor, at its width.
    @Test
    fun reminderEditorRows() {
        drawn.setContent {
            Dialog(onDismissRequest = {}) {
                Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                    Column(Modifier.padding(24.dp)) {
                        ReminderEditorField("Date", "Wed, Nov 18, 2026") {}
                        ReminderEditorField("Time", "8:59\u202FPM") {}
                    }
                }
            }
        }
        drawn.assertReadable()
    }

    // Welcome's buttons, the widest row the guide shows.
    @Test
    fun setupGuide() {
        drawn.setContent {
            SetupGuideFrame(
                step = "Step 2 of 9",
                onClose = {},
                pageKey = 2,
                buttons = {
                    SetupButtonRow(onBack = {}) {
                        OutlinedButton(onClick = {}) { Text("Restore from a backup") }
                        Button(onClick = {}) { Text("Start") }
                    }
                },
            ) {
                Text("Set up the Notebook", style = MaterialTheme.typography.headlineSmall)
                Text("The Notebook keeps notes and reminders you record or type.")
            }
        }
        drawn.assertReadable()
        drawn.compose.onNodeWithText("Step 2 of 9").assertLeftPositionInRootIsEqualTo(16.dp)
        drawn.compose.onNode(hasText("Back") and hasClickAction()).assertLeftPositionInRootIsEqualTo(16.dp)
        if (check == DrawnCheck.FONT) {
            val page = drawn.compose.onRoot().getBoundsInRoot()
            val start = drawn.compose.onNode(hasText("Start") and hasClickAction()).getBoundsInRoot()
            assertEquals((page.right - 16.dp).value, start.right.value, 0.5f, "a wrapped button's end")
        }
    }

    // At a month grid cell's width on this phone: in a week holding a count, a two-digit count, a one-digit count and a
    // day without marks; and a day without marks in a week without counts.
    @Test
    fun dayMarks() {
        drawn.setContent {
            Row {
                marks(24, 13, countLine = true, "two digits")
                marks(25, 5, countLine = true, "counted")
                marks(26, 0, countLine = true, "plain")
                marks(27, 0, countLine = false, "uncounted week")
                marks(28, 1, countLine = false, "uncounted dot")
            }
        }
        drawn.assertReadable()
        val counted = drawn.compose.onNodeWithTag("counted", useUnmergedTree = true).getBoundsInRoot()
        val plain = drawn.compose.onNodeWithTag("plain", useUnmergedTree = true).getBoundsInRoot()
        val uncounted = drawn.compose.onNodeWithTag("uncounted week", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(plain.height.value, counted.height.value, 0.5f, "marks with a one-line count and without one")
        val dot = drawn.compose.onNodeWithTag("uncounted dot", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(uncounted.height < plain.height, "a week without counts keeps no count line")
        assertEquals(uncounted.height.value, dot.height.value, 0.5f, "days with and without dots in a week without counts")
    }

    @Composable
    private fun marks(day: Int, count: Int, countLine: Boolean, tag: String) = Box(Modifier.width(50.dp)) {
        DayMarks(day, today = false, inMonth = true, dots = List(count) { MaterialTheme.colorScheme.outline }, countLine, Modifier.testTag(tag))
    }

    // A week as the month grid lays it out, one day with five phone events; under it the same marks drawn alone in a
    // slot of the cell's width less its 2 dp padding. The cell's node is the area inside that padding.
    @Test
    fun dayCellHoldsItsMarks() {
        drawn.setContent {
            Column {
                Row(page.fillMaxWidth()) {
                    for (day in 22..28) {
                        DayCell(
                            LocalDate(2026, 11, day),
                            inMonth = true,
                            today = false,
                            selected = false,
                            rows = emptyList(),
                            phoneEvents = if (day == 25) 5 else 0,
                            label = "day $day",
                            modifier = Modifier.weight(1f),
                            countLine = true,
                        ) {}
                    }
                }
                Row(page.fillMaxWidth()) {
                    for (day in 22..28) {
                        Box(Modifier.weight(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (day == 25) {
                                DayMarks(25, today = false, inMonth = true, dots = List(5) { MaterialTheme.colorScheme.outline }, countLine = true, Modifier.testTag("marks"))
                            }
                        }
                    }
                }
            }
        }
        if (check == DrawnCheck.CONTRAST) assumeTrue("font 1.0 only", atFontOne())
        val cell = drawn.compose.onNode(hasContentDescription("day 25")).getBoundsInRoot()
        val marks = drawn.compose.onNodeWithTag("marks", useUnmergedTree = true).getBoundsInRoot()
        if (atFontOne()) {
            assertEquals(cell.width.value, cell.height.value, 0.5f, "a day cell at font 1.0 is square")
        } else if (check == DrawnCheck.FONT) {
            assertTrue(cell.height >= marks.height, "the day cell is ${cell.height} tall, its marks need ${marks.height}")
        }
    }

    private fun atFontOne() = check == DrawnCheck.CONTRAST && drawn.compose.density.fontScale == 1f
}
