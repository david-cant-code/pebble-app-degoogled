package theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import theme.CoreAppTheme.Companion.asCoreAppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeTest {
    @Test
    fun `dark schemes report isDark`() {
        assertTrue(CoreAppColorScheme.Grey.isDark)
        assertTrue(CoreAppColorScheme.Black.isDark)
        assertFalse(CoreAppColorScheme.Light.isDark)
    }

    @Test
    fun `theme keys round trip`() {
        CoreAppTheme.entries.forEach {
            assertEquals(it, it.key.asCoreAppTheme())
        }
    }

    @Test
    fun `unknown theme key falls back to system`() {
        assertEquals(CoreAppTheme.System, null.asCoreAppTheme())
        assertEquals(CoreAppTheme.System, "amoled".asCoreAppTheme())
    }

    @Test
    fun `black scheme is true black and distinct from grey`() {
        assertEquals(Color.Black, blackScheme.background)
        assertEquals(Color.Black, blackScheme.surface)
        assertTrue(blackScheme.background != greyScheme.background)
        assertTrue(blackScheme.surface != greyScheme.surface)
    }

    @Test
    fun `dark scheme container roles ascend`() {
        listOf("black" to blackScheme, "grey" to greyScheme).forEach { (schemeName, scheme) ->
            val ladder = listOf(
                "surfaceContainerLowest" to scheme.surfaceContainerLowest,
                "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            )
            ladder.zipWithNext { (lowerName, lower), (higherName, higher) ->
                assertTrue(
                    lower.luminance() <= higher.luminance(),
                    "$schemeName: $lowerName (${lower.luminance()}) is lighter than " +
                        "$higherName (${higher.luminance()})",
                )
            }
        }
    }

    // Menus paint themselves surfaceContainer. On the dark schemes that has to clear the page, which
    // shadow elevation cannot do for them the way it does on light.
    @Test
    fun `dark scheme menu containers are distinct from the page`() {
        listOf(blackScheme, greyScheme).forEach { scheme ->
            assertTrue(scheme.surfaceContainer != scheme.surface)
            assertTrue(scheme.surfaceContainer != scheme.background)
        }
    }

    // gravelPurple is darker than the orange it replaced, so black foregrounds that passed
    // WCAG AA on orange fail on it; every text role painted over a purple fill must clear 4.5:1.
    @Test
    fun `foregrounds over the brand purple meet WCAG AA for text`() {
        listOf("grey" to greyScheme, "black" to blackScheme, "light" to lightScheme)
            .forEach { (schemeName, scheme) ->
                listOf(
                    "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                    "onPrimaryContainer/primaryContainer" to
                        (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "onSecondary/secondary" to (scheme.onSecondary to scheme.secondary),
                    "onSecondaryContainer/secondaryContainer" to
                        (scheme.onSecondaryContainer to scheme.secondaryContainer),
                    "onTertiary/tertiary" to (scheme.onTertiary to scheme.tertiary),
                ).forEach { (pairName, colors) ->
                    val (foreground, background) = colors
                    if (background == gravelPurple) {
                        val ratio = contrastRatio(foreground, background)
                        assertTrue(
                            ratio >= 4.5f,
                            "$schemeName $pairName contrast $ratio is below the 4.5:1 AA threshold",
                        )
                    }
                }
            }
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance()) + 0.05f
        val darker = minOf(a.luminance(), b.luminance()) + 0.05f
        return lighter / darker
    }
}
