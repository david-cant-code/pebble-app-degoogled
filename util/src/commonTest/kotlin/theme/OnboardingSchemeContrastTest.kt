package theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the legibility of Material3 dialogs under the onboarding scheme, and
 * the Light text colors setup's store card draws on it.
 * AlertDialog draws its card on surfaceContainerHigh with onSurface (title),
 * onSurfaceVariant (body), and primary (text buttons) as content colors. A
 * container tier left unset in the scheme silently falls back to
 * lightColorScheme's near-white default, which renders this scheme's white
 * content colors invisible; the fresh-install privacy confirm dialog rendered
 * exactly that way and was only caught on-device. The floor is the WCAG AA
 * body-text ratio, 4.5:1.
 */
class OnboardingSchemeContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** The scheme's translucent content colors render composited over the card. */
    private fun Color.over(bg: Color): Color = Color(
        red = red * alpha + bg.red * (1f - alpha),
        green = green * alpha + bg.green * (1f - alpha),
        blue = blue * alpha + bg.blue * (1f - alpha),
    )

    @Test
    fun `dialog title clears AA contrast`() {
        val ratio = contrast(onboardingScheme.onSurface, onboardingScheme.surfaceContainerHigh)
        assertTrue(ratio >= 4.5f, "onSurface on surfaceContainerHigh is $ratio:1")
    }

    @Test
    fun `dialog body clears AA contrast`() {
        val body = onboardingScheme.onSurfaceVariant.over(onboardingScheme.surfaceContainerHigh)
        val ratio = contrast(body, onboardingScheme.surfaceContainerHigh)
        assertTrue(ratio >= 4.5f, "composited onSurfaceVariant on surfaceContainerHigh is $ratio:1")
    }

    @Test
    fun `dialog buttons clear AA contrast`() {
        val ratio = contrast(onboardingScheme.primary, onboardingScheme.surfaceContainerHigh)
        assertTrue(ratio >= 4.5f, "primary on surfaceContainerHigh is $ratio:1")
    }

    // The colors SetupStoreCard (WatchOnboardingScreen) draws: the Light
    // scheme's text on this scheme's onBackground at 91% over the page. This
    // checks the values, not that the card uses them.
    @Test
    fun `setup store card text clears AA contrast`() {
        val card = onboardingScheme.onBackground.copy(alpha = 0.91f).over(onboardingScheme.background)
        val texts = listOf("onSurface" to lightScheme.onSurface, "onSurfaceVariant" to lightScheme.onSurfaceVariant)
        for ((name, text) in texts) {
            val ratio = contrast(text.over(card), card)
            assertTrue(ratio >= 4.5f, "Light $name on the setup store card is $ratio:1")
        }
    }
}
