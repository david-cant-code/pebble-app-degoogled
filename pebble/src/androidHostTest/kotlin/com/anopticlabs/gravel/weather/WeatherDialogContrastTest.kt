package com.anopticlabs.gravel.weather

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import theme.blackScheme
import theme.greyScheme
import theme.lightScheme
import theme.onboardingScheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The colors the weather dialogs and the setup screen ask for: text clears WCAG AA (4.5:1) and the
 * switch outline the non-text floor (3:1) in every scheme they are shown under. These are the
 * values handed to Material3, not the drawn result.
 */
class WeatherDialogContrastTest {

    private val dialogSchemes = mapOf("grey" to greyScheme, "black" to blackScheme, "light" to lightScheme)

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** Translucent colors render composited over what is behind them. */
    private fun Color.over(bg: Color): Color = Color(
        red = red * alpha + bg.red * (1f - alpha),
        green = green * alpha + bg.green * (1f - alpha),
        blue = blue * alpha + bg.blue * (1f - alpha),
    )

    private fun assertAtLeast(floor: Float, fg: Color, bg: Color, what: String) {
        val ratio = contrast(fg.over(bg), bg)
        assertTrue(ratio >= floor, "$what is $ratio:1, needs $floor:1")
    }

    @Test
    fun dialogTextIsReadableInEveryAppScheme() {
        dialogSchemes.forEach { (name, scheme) ->
            val c = weatherDialogColors(scheme)
            assertAtLeast(4.5f, c.title, c.surface, "$name title")
            assertAtLeast(4.5f, c.body, c.surface, "$name body and switch label")
            assertAtLeast(4.5f, c.cancel, c.surface, "$name Cancel")
            assertAtLeast(4.5f, c.onAction, c.action, "$name action button text")
        }
    }

    @Test
    fun theSwitchOutlineIsVisibleInDialogs() {
        dialogSchemes.forEach { (name, scheme) -> assertSwitchVisible(scheme, weatherDialogColors(scheme).surface, name) }
    }

    @Test
    fun theSwitchOutlineIsVisibleOnTheSetupScreen() {
        assertSwitchVisible(onboardingScheme, onboardingScheme.background, "setup")
    }

    @Test
    fun theSetupScreensWeatherTextIsReadable() {
        assertAtLeast(4.5f, onboardingScheme.onBackground, onboardingScheme.background, "setup text")
    }

    private fun assertSwitchVisible(scheme: ColorScheme, behind: Color, name: String) {
        val c = weatherDialogColors(scheme)
        assertAtLeast(3f, c.switchOffOutline, behind, "$name switch off")
        assertAtLeast(3f, c.switchOnOutline, behind, "$name switch on")
    }
}
