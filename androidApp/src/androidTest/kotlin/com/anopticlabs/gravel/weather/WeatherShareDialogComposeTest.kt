package com.anopticlabs.gravel.weather

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBackUnconditionally
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * The one-time weather question after the update, and the Settings switch's dialog.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.weather.WeatherShareDialogComposeTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class WeatherShareDialogComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val choices = mutableListOf<Boolean>()

    private var confirms = 0
    private var dismisses = 0

    private fun show() = compose.setContent { WeatherShareDialog(1.hours) { choices += it } }

    private fun showSettingsDialog() = compose.setContent {
        WeatherConsentDialog(
            title = "Share your location for weather?",
            interval = 1.hours,
            confirmLabel = "Share and turn on",
            dismissLabel = "Cancel",
            onConfirm = { confirms++ },
            onDismiss = { dismisses++ },
        )
    }

    private val done get() = compose.onNodeWithText("Done")

    /** Back sent to the dialog's window once it has focus, not to the activity behind it. */
    private fun backOnTheDialog() {
        onView(isRoot()).inRoot(isDialog()).perform(pressBackUnconditionally())
    }

    private fun waitForDone() = compose.waitUntil(5_000) { runCatching { done.assertIsEnabled() }.isSuccess }

    private val shareSwitch get() = compose.onNode(hasContentDescription(WEATHER_SHARE_LABEL) and isToggleable())

    /** Nodes of Android's accessibility tree, which a screen reader reads, that carry the label. */
    private fun labeledAccessibilityNodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            val label = node.contentDescription?.toString() ?: node.text?.toString()
            if (label == WEATHER_SHARE_LABEL) found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::walk)
        return found
    }

    /** The node a screen reader stops on for [node]: itself if focusable, else its nearest focusable parent. */
    private fun focusStop(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        generateSequence(node) { it.parent }.firstOrNull { it.isFocusable }

    @Test
    fun doneOnItsOwnSharesNothing() {
        show()
        shareSwitch.assertIsOff()
        waitForDone()
        done.performClick()
        assertEquals(listOf(false), choices)
    }

    @Test
    fun onlyTheSwitchThenDoneShares() {
        show()
        shareSwitch.performClick()
        shareSwitch.assertIsOn()
        waitForDone()
        done.performClick()
        assertEquals(listOf(true), choices)
    }

    @Test
    fun aScreenReaderReadsTheLabelWithTheSwitch() {
        show()
        compose.waitForIdle()
        var nodes = labeledAccessibilityNodes()
        val deadline = System.currentTimeMillis() + 5_000
        while (nodes.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            nodes = labeledAccessibilityNodes()
        }
        assertTrue(nodes.isNotEmpty(), "no accessibility node carries the label")
        nodes.forEach {
            val stop = focusStop(it)
            assertTrue(stop != null && stop.isCheckable && stop.isClickable, "the label is read apart from the switch: $it")
        }
    }

    @Test
    fun theSwitchCanBeTurnedBackOffBeforeDone() {
        show()
        shareSwitch.performClick()
        shareSwitch.performClick()
        waitForDone()
        done.performClick()
        assertEquals(listOf(false), choices)
    }

    @Test
    fun doneIgnoresATapRightAfterThePopupAppears() {
        show()
        done.assertIsNotEnabled()
        done.performClick()
        assertEquals(emptyList(), choices)
        waitForDone()
        done.performClick()
        assertEquals(listOf(false), choices)
    }

    @Test
    fun aSecondTapOnDoneIsNotASecondAnswer() {
        show()
        shareSwitch.performClick()
        waitForDone()
        done.performClick()
        done.performClick()
        assertEquals(listOf(true), choices)
    }

    @Test
    fun backDoesNotCloseThePopup() {
        show()
        backOnTheDialog()
        done.assertExists()
        assertEquals(emptyList(), choices)
    }

    @Test
    fun settingsCancelDoesNotAgree() {
        showSettingsDialog()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0 to 1, confirms to dismisses)
    }

    @Test
    fun settingsShareAndTurnOnAgrees() {
        showSettingsDialog()
        compose.onNodeWithText("Share and turn on").performClick()
        assertEquals(1 to 0, confirms to dismisses)
    }

    @Test
    fun backDoesNotCloseTheSettingsDialog() {
        showSettingsDialog()
        backOnTheDialog()
        compose.onNodeWithText("Share and turn on").assertExists()
        assertEquals(0 to 0, confirms to dismisses)
    }
}
