package com.anopticlabs.gravel.accessibility

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import theme.greyScheme
import theme.onboardingScheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DrawnText]'s contrast check, shown failing on pieces drawn to fail it and passing on pieces drawn to pass.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.accessibility.DrawnContrastCheckTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class DrawnContrastCheckTest {

    @get:Rule
    val drawn = DrawnText(DrawnCheck.CONTRAST)

    private val faint: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    private fun assertFailures(expected: Int, mention: String, failures: List<String>) {
        assertEquals(expected, failures.size, failures.joinToString("\n"))
        assertTrue(failures.all { mention in it }, failures.joinToString("\n"))
    }

    @Test
    fun theCommonestColorIsTheBackgroundAndAStrayPixelIsNotTheText() {
        val bitmap = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF333333.toInt())
        for (x in 10 until 30) for (y in 10 until 20) bitmap.setPixel(x, y, 0xFFDBB8F4.toInt())
        bitmap.setPixel(50, 30, 0xFFFFFFFF.toInt())
        assertEquals(DrawnColors(0xFF333333.toInt(), 0xFFDBB8F4.toInt()), drawnColors(bitmap))
    }

    @Test
    fun aRegionWhereNoColorDominatesHasNoColors() {
        val bitmap = Bitmap.createBitmap(10, 300, Bitmap.Config.ARGB_8888)
        for (y in 0 until 300) for (x in 0 until 10) bitmap.setPixel(x, y, 0xFF000000.toInt() or (y * 0x010101))
        assertEquals(null, drawnColors(bitmap))
    }

    @Test
    fun contrastFollowsTheWcagFormula() {
        assertEquals(21.0, contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(4.48, contrast(0xFF777777.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun plainTextPassesInEveryTheme() {
        drawn.setContent { Text("Plain") }
        assertEquals(emptyList(), drawn.contrastFailures())
    }

    @Test
    fun faintTextFailsInEveryTheme() {
        drawn.setContent { Text("Faint", color = faint) }
        assertFailures(3, "\"Faint\"", drawn.contrastFailures())
    }

    @Test
    fun aColorFailingOnlyInBlackFailsOnlyThere() {
        drawn.setContent {
            val black = MaterialTheme.colorScheme.background == Color.Black
            Text("Dim in Black", color = if (black) Color(0xFF333333) else MaterialTheme.colorScheme.onBackground)
        }
        assertFailures(1, "Black: \"Dim in Black\"", drawn.contrastFailures())
    }

    @Test
    fun textJustUnderTheFloorFailsAndJustOverPasses() {
        drawn.setContent {
            Column(Modifier.background(Color.White)) {
                Text("Under", color = Color(0xFF777777))
                Text("Over", color = Color(0xFF767676))
            }
        }
        assertFailures(3, "\"Under\" is 4.48:1", drawn.contrastFailures())
    }

    @Test
    fun aFaintSpanInPlainTextFails() {
        drawn.setContent {
            Text(buildAnnotatedString {
                append("Plain words then ")
                withStyle(SpanStyle(color = faint)) { append("faint words") }
            })
        }
        assertFailures(3, "part \"faint words\"", drawn.contrastFailures())
    }

    @Test
    fun aFaintRestBesideAPlainPartFails() {
        drawn.setContent {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("Plain part ") }
                    append("faint rest")
                },
                color = faint,
            )
        }
        assertFailures(3, "rest \"faint rest\" is", drawn.contrastFailures())
    }

    @Test
    fun aStyledTextUnderAFadingLayerFails() {
        drawn.setContent {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("Plain") }
                    append(" text")
                },
                Modifier.alpha(0.3f),
            )
        }
        assertFailures(3, "\"Plain text\" is", drawn.contrastFailures())
    }

    @Test
    fun aPartPastTheLastShownLineIsNotMeasured() {
        drawn.setContent {
            Text(
                buildAnnotatedString {
                    append("Shown line\n")
                    withStyle(SpanStyle(color = faint)) { append("hidden line") }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        assertEquals(emptyList(), drawn.contrastFailures())
    }

    @Test
    fun aFaintPartAroundAPlainPartStillFails() {
        drawn.setContent {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = faint)) {
                    append("faint ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("plain") }
                }
            })
        }
        assertFailures(3, "part \"faint\"", drawn.contrastFailures())
    }

    @Test
    fun twoPlainPartsWithAFaintSpaceBetweenPass() {
        drawn.setContent {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("Plain") }
                withStyle(SpanStyle(color = faint)) { append(" ") }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append("parts") }
            })
        }
        assertEquals(emptyList(), drawn.contrastFailures())
    }

    @Test
    fun aFaintValueAboveSupportingTextFails() {
        drawn.setContent {
            TextField(
                value = "Typed value",
                onValueChange = {},
                label = { Text("Label") },
                supportingText = { Text("Supporting text") },
                colors = TextFieldDefaults.colors(unfocusedTextColor = faint),
            )
        }
        assertFailures(3, "\"Typed value\"", drawn.contrastFailures())
    }

    @Test
    fun aFaintValueInAnOutlinedErrorFieldFails() {
        drawn.setContent {
            OutlinedTextField(
                value = "Typed value",
                onValueChange = {},
                label = { Text("Label") },
                isError = true,
                supportingText = { Text("Supporting text") },
                colors = OutlinedTextFieldDefaults.colors(errorTextColor = faint),
            )
        }
        assertFailures(3, "\"Typed value\"", drawn.contrastFailures())
    }

    @Test
    fun aFaintOneLetterValueInAnOutlinedErrorFieldFails() {
        drawn.setContent {
            OutlinedTextField(
                value = "1",
                onValueChange = {},
                label = { Text("Label") },
                isError = true,
                colors = OutlinedTextFieldDefaults.colors(errorTextColor = faint),
            )
        }
        assertFailures(3, "\"1\"", drawn.contrastFailures())
    }

    @Test
    fun aFaintValueBesideADecorativeIconFails() {
        drawn.setContent {
            TextField(
                value = "Typed value",
                onValueChange = {},
                trailingIcon = { Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.onSurface)) },
                colors = TextFieldDefaults.colors(unfocusedTextColor = faint),
            )
        }
        assertFailures(3, "\"Typed value\"", drawn.contrastFailures())
    }

    @Test
    fun aFaintPartAddedToAFieldsValueForDisplayFails() {
        drawn.setContent {
            val suffix = faint
            TextField(
                value = "name",
                onValueChange = {},
                visualTransformation = { value ->
                    val shown = buildAnnotatedString {
                        append(value)
                        withStyle(SpanStyle(color = suffix)) { append("@example.com") }
                    }
                    TransformedText(shown, object : OffsetMapping {
                        override fun originalToTransformed(offset: Int) = offset
                        override fun transformedToOriginal(offset: Int) = minOf(offset, value.length)
                    })
                },
            )
        }
        assertFailures(3, "part \"@example.com\"", drawn.contrastFailures())
    }

    @Test
    fun aFieldsBackgroundIsItsContainerUnderManyShortSupportingLines() {
        drawn.setContent {
            // Fails on each theme's field container and passes on its page.
            val value = when (MaterialTheme.colorScheme.background) {
                Color.White -> Color(0xFF767676)
                Color.Black -> Color(0xFF777777)
                else -> Color(0xFFAAAAAA)
            }
            Column {
                Spacer(Modifier.height(200.dp))
                TextField(
                    value = "Typed value",
                    onValueChange = {},
                    supportingText = { Text("One\nTwo\nThree\nFour\nFive\nSix\nSeven\nEight") },
                    colors = TextFieldDefaults.colors(unfocusedTextColor = value),
                )
            }
        }
        assertFailures(3, "\"Typed value\"", drawn.contrastFailures())
    }

    @Test
    fun aOneLetterValueInADefaultOutlinedFieldPasses() {
        drawn.setContent { OutlinedTextField(value = "1", onValueChange = {}) }
        assertEquals(emptyList(), drawn.contrastFailures())
    }

    @Test
    fun aFaintValueBesideAPlainLabelFails() {
        drawn.setContent {
            TextField(
                value = "Typed value",
                onValueChange = {},
                label = { Text("Label") },
                colors = TextFieldDefaults.colors(unfocusedTextColor = faint),
            )
        }
        assertFailures(3, "\"Typed value\"", drawn.contrastFailures())
    }

    @Test
    fun textInsideADisabledNodeIsLeftOutUnlessNamed() {
        drawn.setContent {
            Column {
                Text("Plain")
                TextButton(onClick = {}, enabled = false) { Text("Off button") }
                Row(Modifier.clickable(enabled = false) {}) { Text("Off row", color = faint) }
                TextField(value = "Off field", onValueChange = {}, enabled = false)
            }
        }
        assertEquals(emptyList(), drawn.contrastFailures())
        assertFailures(3, "\"Off row\"", drawn.contrastFailures(hasText("Off row")))
    }

    @Test
    fun textInsideADisabledRoleOrToggleIsLeftOut() {
        drawn.setContent {
            Column {
                Text("Plain")
                Box(Modifier.semantics { role = Role.Button; disabled() }) { Text("Off role", color = faint) }
                Box(Modifier.semantics { toggleableState = ToggleableState.Off; disabled() }) { Text("Off toggle", color = faint) }
            }
        }
        assertEquals(emptyList(), drawn.contrastFailures())
    }

    @Test
    fun anEnabledButtonInsideADisabledCardIsMeasured() {
        drawn.setContent {
            Card(onClick = {}, enabled = false) { TextButton(onClick = {}) { Text("On button", color = faint) } }
        }
        assertFailures(3, "\"On button\"", drawn.contrastFailures())
    }

    @Test
    fun aNamedNodeThatIsNotDrawnFails() {
        drawn.setContent { Text("Plain") }
        assertFailures(3, "nothing is", drawn.contrastFailures(hasText("Missing")))
    }

    @Test
    fun aPieceWithNothingToMeasureFails() {
        drawn.setContent { Box {} }
        assertFailures(3, "no enabled text to measure", drawn.contrastFailures())
    }

    @Test
    fun setupContentIsMeasuredOnceInSetupsScheme() {
        drawn.setSetupContent {
            val setup = MaterialTheme.colorScheme.primary == onboardingScheme.primary
            Text("Dim in setup", color = if (setup) faint else MaterialTheme.colorScheme.onBackground)
        }
        assertFailures(1, "Setup: \"Dim in setup\"", drawn.contrastFailures())
    }

    @Test
    fun aColorFailingOnlyInDarkFailsOnlyThere() {
        drawn.setContent {
            val dark = MaterialTheme.colorScheme.background == greyScheme.background
            Text("Dim in Dark", color = if (dark) Color(0xFF444444) else MaterialTheme.colorScheme.onBackground)
        }
        assertFailures(1, "Dark: \"Dim in Dark\"", drawn.contrastFailures())
    }

    @Test
    fun textBelowTheFoldOfAScrollingListIsMeasuredInView() {
        drawn.setContent {
            Column(Modifier.height(100.dp).verticalScroll(rememberScrollState())) {
                repeat(20) { Text("Row $it") }
            }
        }
        assertEquals(emptyList(), drawn.contrastFailures())
    }
}
