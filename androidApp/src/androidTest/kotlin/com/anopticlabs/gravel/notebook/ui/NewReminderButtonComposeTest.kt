package com.anopticlabs.gravel.notebook.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.R
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The Reminders tab's New reminder button as a screen reader finds it.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.notebook.ui.NewReminderButtonComposeTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class NewReminderButtonComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theButtonIsNamedForAScreenReader() {
        var clicks = 0
        compose.setContent { NewReminderButton(onClick = { clicks++ }) }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.notebook_reminders_new)
        compose.onNode(hasContentDescription(label) and hasClickAction()).performClick()
        assertEquals(1, clicks)
    }
}
