package com.anopticlabs.gravel.accessibility

import android.content.res.Resources
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.anopticlabs.gravel.debug.LARGEST_FONT_SCALE
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DrawnText]'s font check, shown failing on pieces drawn to fail it and passing on pieces drawn to pass.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.accessibility.DrawnFontCheckTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class DrawnFontCheckTest {

    @get:Rule
    val drawn = DrawnText(DrawnCheck.FONT)

    // Wider than 110 dp at the largest font size.
    private val word = "Reminders"
    private val sized = TextStyle(fontSize = 16.sp)

    /** The two tests below show the activity's font size only when the phone's own is smaller. */
    private fun assertPhoneFontBelowLargest() =
        assertTrue(Resources.getSystem().configuration.fontScale < LARGEST_FONT_SCALE, "set the phone's font size below the largest for this test")

    private fun assertOneFailure(expected: String, failures: List<String>) {
        assertEquals(1, failures.size, failures.joinToString("\n"))
        assertTrue(expected in failures.single(), failures.single())
    }

    @Test
    fun textThatFitsPasses() {
        drawn.setContent { Text("Fine") }
        assertEquals(emptyList(), drawn.fontFailures())
    }

    @Test
    fun theCheckDrawsAtTheLargestFont() {
        assertPhoneFontBelowLargest()
        drawn.setContent { Box(Modifier.width(110.dp)) { Text(word, style = sized, softWrap = false, overflow = TextOverflow.Clip) } }
        assertOneFailure("cut off", drawn.fontFailures())
    }

    @Test
    fun aPopupIsDrawnAtTheLargestFont() {
        assertPhoneFontBelowLargest()
        drawn.setContent {
            Dialog(onDismissRequest = {}) {
                Box(Modifier.width(110.dp)) { Text(word, style = sized, softWrap = false, overflow = TextOverflow.Clip) }
            }
        }
        assertOneFailure("cut off", drawn.fontFailures())
    }

    @Test
    fun textDrawnAtAnotherFontSizeFails() {
        drawn.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1f)) { Text("Small") }
        }
        assertOneFailure("drawn at font 1.0", drawn.fontFailures())
    }

    @Test
    fun anUnwrappedLineCutAtTheEdgeFails() {
        drawn.setContent { Box(Modifier.width(60.dp)) { Text("Unclassified", softWrap = false, overflow = TextOverflow.Clip) } }
        assertOneFailure("cut off", drawn.fontFailures())
    }

    @Test
    fun anUnwrappedStyledLineCutAtTheEdgeFails() {
        drawn.setContent {
            Box(Modifier.width(60.dp)) { Text(AnnotatedString("Unclassified"), softWrap = false, overflow = TextOverflow.Clip) }
        }
        assertOneFailure("cut off", drawn.fontFailures())
    }

    @Test
    fun anUnwrappedEllipsizedLinePasses() {
        drawn.setContent { Box(Modifier.width(60.dp)) { Text("Unclassified", softWrap = false, overflow = TextOverflow.Ellipsis) } }
        assertEquals(emptyList(), drawn.fontFailures())
    }

    @Test
    fun anUnwrappedLineShortenedInTheMiddlePasses() {
        drawn.setContent { Box(Modifier.width(60.dp)) { Text("Unclassified", softWrap = false, overflow = TextOverflow.MiddleEllipsis) } }
        assertEquals(emptyList(), drawn.fontFailures())
    }

    @Test
    fun anEllipsizedLinePasses() {
        drawn.setContent { Box(Modifier.width(60.dp)) { Text("Unclassified", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        assertEquals(emptyList(), drawn.fontFailures())
    }

    @Test
    fun linesDroppedWithoutEllipsisFail() {
        drawn.setContent { Box(Modifier.width(60.dp)) { Text("Two words", maxLines = 1, overflow = TextOverflow.Clip) } }
        assertOneFailure("lines past its last are dropped", drawn.fontFailures())
    }

    @Test
    fun anEllipsizedLineTooShortForItsHeightFails() {
        drawn.setContent { Text("Unclassified folder", Modifier.height(24.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        assertOneFailure("sliced", drawn.fontFailures())
    }

    @Test
    fun aWordBrokenAcrossLinesFails() {
        drawn.setContent { Box(Modifier.width(80.dp)) { Text(word) } }
        assertOneFailure("broken inside a word", drawn.fontFailures())
    }

    // One character wider than its box.
    @Test
    fun aWrappedLineWiderThanItsBoxFails() {
        drawn.setContent { Box(Modifier.width(8.dp).clipToBounds()) { Text("W") } }
        assertOneFailure("cut off", drawn.fontFailures())
    }

    // Narrower than "5%" and wider than each of its two characters at the largest font size.
    @Test
    fun aNumberBrokenFromItsSignFails() {
        drawn.setContent { Box(Modifier.width(24.dp)) { Text("5%", style = sized) } }
        assertOneFailure("broken inside a word", drawn.fontFailures())
    }

    @Test
    fun textClippedByItsHolderFails() {
        drawn.setContent {
            Box(Modifier.height(20.dp).clipToBounds()) {
                Text("Two lines\nof text", Modifier.wrapContentHeight(Alignment.Top, unbounded = true))
            }
        }
        assertOneFailure("partly hidden", drawn.fontFailures())
    }

    @Test
    fun textBeyondTheWindowFails() {
        drawn.setContent { Box(Modifier.offset(y = 5000.dp)) { Text("Off screen") } }
        assertOneFailure("partly hidden", drawn.fontFailures())
    }

    @Test
    fun textBeyondAPopupsWindowFails() {
        drawn.setContent { Dialog(onDismissRequest = {}) { Box(Modifier.offset(y = 5000.dp)) { Text("Off screen") } } }
        assertOneFailure("partly hidden", drawn.fontFailures())
    }

    @Test
    fun aParagraphTallerThanItsScrollingAreaPasses() {
        drawn.setContent {
            Column(Modifier.height(100.dp).verticalScroll(rememberScrollState())) {
                Text("A paragraph long enough to need several lines at any font size, so that it is taller than this list.")
            }
        }
        assertEquals(emptyList(), drawn.fontFailures())
    }

    @Test
    fun aScrollingAreaTooShortForTwoLinesFails() {
        drawn.setContent {
            Column(Modifier.height(10.dp).verticalScroll(rememberScrollState())) {
                Text("A paragraph long enough to need several lines at any font size, in a list too short to read it.")
            }
        }
        assertOneFailure("partly hidden", drawn.fontFailures())
    }

    @Test
    fun aLineWiderThanItsSidewaysScrollingAreaPasses() {
        drawn.setContent {
            Row(Modifier.width(100.dp).horizontalScroll(rememberScrollState())) {
                Text("A line read by scrolling sideways", softWrap = false)
            }
        }
        assertEquals(emptyList(), drawn.fontFailures())
    }

    @Test
    fun aSidewaysScrollingAreaTooNarrowFails() {
        drawn.setContent {
            Row(Modifier.width(4.dp).horizontalScroll(rememberScrollState())) {
                Text("A line read by scrolling sideways", softWrap = false)
            }
        }
        assertOneFailure("partly hidden", drawn.fontFailures())
    }

    @Test
    fun textDrawnOverTextFails() {
        drawn.setContent {
            Box {
                Text("Over")
                Text("Under")
            }
        }
        assertOneFailure("overlaps", drawn.fontFailures())
    }

    @Test
    fun textUnderAnIconFails() {
        drawn.setContent {
            Box {
                Text("Covered")
                Box(Modifier.size(24.dp).semantics { contentDescription = "Icon" })
            }
        }
        assertOneFailure("\"Covered\" overlaps \"Icon\"", drawn.fontFailures())
    }

    @Test
    fun textUnderAControlFails() {
        drawn.setContent {
            Column {
                Box {
                    Text("Under a button")
                    Box(Modifier.size(24.dp).semantics { role = Role.Button })
                }
                Box {
                    Text("Under a switch")
                    Box(Modifier.size(24.dp).semantics { toggleableState = ToggleableState.On })
                }
                Box {
                    Text("Under a spinner")
                    Box(Modifier.size(24.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate })
                }
            }
        }
        val failures = drawn.fontFailures()
        assertEquals(3, failures.size, failures.joinToString("\n"))
        assertTrue(failures.all { "overlaps" in it }, failures.joinToString("\n"))
    }

    @Test
    fun textInsideAButtonDoesNotOverlapIt() {
        drawn.setContent { Button(onClick = {}) { Text("Save") } }
        assertEquals(emptyList(), drawn.fontFailures())
    }

    @Test
    fun textInAPopupIsNotComparedWithTextOnThePage() {
        drawn.setContent {
            Column { repeat(30) { Text("Page row $it") } }
            Dialog(onDismissRequest = {}) { Box(Modifier.padding(top = 200.dp)) { Text("Popup text") } }
        }
        assertTrue(drawn.fontFailures().none { "overlaps" in it })
    }

    @Test
    fun textBelowTheFoldOfAScrollingListIsCheckedInView() {
        drawn.setContent {
            Column(Modifier.height(100.dp).verticalScroll(rememberScrollState())) {
                repeat(20) { Text("Row $it") }
            }
        }
        assertEquals(emptyList(), drawn.fontFailures())
    }

    @Test
    fun aPieceWithNoTextFails() {
        drawn.setContent { Box {} }
        assertOneFailure("no text to check", drawn.fontFailures())
    }
}
