package com.anopticlabs.gravel.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import theme.CoreAppColorScheme
import theme.blackScheme
import theme.greyScheme
import theme.lightScheme
import theme.onboardingScheme
import theme.onboardingTextSelectionColors
import theme.textSelectionColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Light, Dark and Black schemes' text and content colors, and setup's selected text, against the 4.5:1 floor, for
 * the pairs listed here.
 */
class SchemeTextContrastTest {

    private val schemes = listOf("light" to lightScheme, "dark" to greyScheme, "black" to blackScheme)

    private fun textColors(s: ColorScheme) = listOf(
        "primary" to s.primary,
        "secondary" to s.secondary,
        "tertiary" to s.tertiary,
        "error" to s.error,
        "onBackground" to s.onBackground,
        "onSurface" to s.onSurface,
        "onSurfaceVariant" to s.onSurfaceVariant,
    )

    // surfaceDim and surfaceBright are absent: no Gravel screen paints them, and no component token
    // in material3-android 1.5.0-alpha08 (androidx.compose.material3.tokens) refers to them.
    // surfaceVariant is the Notebook's card color; 6 dp is the reminder popup.
    private fun surfaces(s: ColorScheme) = listOf(
        "background" to s.background,
        "surface" to s.surface,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
        "surfaceVariant" to s.surfaceVariant,
        "surface at 6 dp" to s.surfaceColorAtElevation(6.dp),
    )

    private fun fills(s: ColorScheme) = listOf(
        "onPrimary on primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer on primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondary on secondary" to (s.onSecondary to s.secondary),
        "onSecondaryContainer on secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiary on tertiary" to (s.onTertiary to s.tertiary),
        "onTertiaryContainer on tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onError on error" to (s.onError to s.error),
        "onErrorContainer on errorContainer" to (s.onErrorContainer to s.errorContainer),
        "inverseOnSurface on inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
        "inversePrimary on inverseSurface" to (s.inversePrimary to s.inverseSurface),
    )

    @Test
    fun `text colors clear 4_5 on the listed surfaces`() {
        val failures = schemes.flatMap { (name, scheme) ->
            textColors(scheme).flatMap { (text, color) ->
                surfaces(scheme).mapNotNull { (surface, background) ->
                    failure("$name: $text on $surface", color, background)
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `content colors clear 4_5 on their fills`() {
        val failures = schemes.flatMap { (name, scheme) ->
            fills(scheme).mapNotNull { (pair, colors) -> failure("$name: $pair", colors.first, colors.second) }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // A text field draws the highlight and then its text in its own color, onSurface (foundation-android 1.11.2,
    // TextFieldDelegate.draw; material3-android 1.5.0-alpha08, FilledTextFieldTokens and OutlinedTextFieldTokens InputColor).
    @Test
    fun `selected text clears 4_5 on the selection highlight`() {
        val themes = listOf(CoreAppColorScheme.Light to lightScheme, CoreAppColorScheme.Grey to greyScheme, CoreAppColorScheme.Black to blackScheme)
        val failures = themes.flatMap { (theme, scheme) ->
            val highlight = textSelectionColors(theme).backgroundColor
            surfaces(scheme).mapNotNull { (surface, background) ->
                failure("$theme: selected onSurface on $surface", scheme.onSurface, highlight.compositeOver(background))
            }
        }
        // Setup's page, its popups, and a card's translucent white over the page.
        val setup = onboardingScheme
        val setupSurfaces = listOf(
            "surface" to setup.surface,
            "surfaceContainerHigh" to setup.surfaceContainerHigh,
            "surfaceContainerHighest" to setup.surfaceContainerHighest.compositeOver(setup.surface),
        )
        val setupFailures = setupSurfaces.mapNotNull { (surface, background) ->
            failure("setup: selected onSurface on $surface", setup.onSurface, onboardingTextSelectionColors.backgroundColor.compositeOver(background))
        }
        assertTrue((failures + setupFailures).isEmpty(), (failures + setupFailures).joinToString("\n"))
    }

    // A default Card, which M3Dialog is, paints surfaceContainerHighest with contentColorFor of it
    // (material3-android 1.5.0-alpha08, CardDefaults.cardColors and FilledCardTokens).
    @Test
    fun `default cards get onSurface content`() {
        schemes.forEach { (name, scheme) ->
            assertEquals(scheme.onSurface, scheme.contentColorFor(scheme.surfaceContainerHighest), name)
        }
    }

    // A translucent text color is measured as drawn, composited over its background.
    private fun failure(label: String, text: Color, background: Color): String? {
        val drawn = text.compositeOver(background)
        val lighter = maxOf(drawn.luminance(), background.luminance()) + 0.05f
        val darker = minOf(drawn.luminance(), background.luminance()) + 0.05f
        val ratio = lighter / darker
        return if (ratio < 4.5f) "$label is $ratio:1" else null
    }
}
