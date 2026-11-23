package com.anopticlabs.gravel.accessibility

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import theme.AppTheme
import theme.CoreAppColorScheme
import theme.OnboardingTheme
import theme.gravelPurple
import kotlin.test.assertEquals

/**
 * The selection highlight the app's themes provide to what they draw; SchemeTextContrastTest measures text on it.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.accessibility.SelectionHighlightTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class SelectionHighlightTest {

    @get:Rule
    val compose = createComposeRule()

    private fun highlightIn(theme: @Composable (@Composable () -> Unit) -> Unit): Color {
        var highlight = Color.Unspecified
        compose.setContent { theme { val seen = LocalTextSelectionColors.current.backgroundColor; SideEffect { highlight = seen } } }
        compose.waitForIdle()
        return highlight
    }

    @Test
    fun darkHighlightsInGravelPurple() {
        assertEquals(gravelPurple.copy(alpha = 0.4f), highlightIn { AppTheme(CoreAppColorScheme.Grey, it) })
    }

    @Test
    fun setupHighlightsInBlack() {
        assertEquals(Color.Black.copy(alpha = 0.4f), highlightIn { AppTheme(CoreAppColorScheme.Light) { OnboardingTheme(it) } })
    }
}
