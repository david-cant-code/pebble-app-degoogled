package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.notebook.alarms.ReminderIntents
import com.anopticlabs.gravel.notebook.ui.NotebookLink
import com.anopticlabs.gravel.notebook.ui.NotebookNavigator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookIntentsTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val navigator = NotebookNavigator()
    private var shown = true
    private var opened = 0
    private var tokenReads = 0
    private val intents = NotebookIntents(
        tabShown = { shown },
        token = { tokenReads++; token },
        navigator = navigator,
        openTab = { opened++ },
    )

    private fun take(action: String?, extras: Map<String, Any> = emptyMap(), fromHistory: Boolean = false) =
        intents.take(action, { extras[it] as? String }, { extras[it] as? Int }, { extras[it] as? Long }, fromHistory)

    private val withToken = mapOf<String, Any>(ReminderIntents.EXTRA_LINK_TOKEN to token)

    @Test
    fun aTapWithTheTokenOpensTheTabWithItsLink() {
        assertTrue(take(ReminderIntents.ACTION_OPEN_VOICE_COMPLETIONS, withToken))
        assertEquals(NotebookLink.Completions, navigator.pending.value)
        assertEquals(1, opened)
    }

    @Test
    fun aTapWithoutTheTokenIsTakenButNotRouted() {
        assertTrue(take(ReminderIntents.ACTION_OPEN_REMINDERS))
        assertNull(navigator.pending.value)
        assertEquals(0, opened)
        assertEquals(0, tokenReads)
    }

    @Test
    fun aTapWhileTheTabIsHiddenIsTakenButNotRouted() {
        shown = false
        assertTrue(take(ReminderIntents.ACTION_OPEN_REMINDERS, withToken))
        assertNull(navigator.pending.value)
        assertEquals(0, opened)
    }

    @Test
    fun aRelaunchFromRecentsIsTakenButNotRouted() {
        assertTrue(take(ReminderIntents.ACTION_OPEN_REMINDERS, withToken, fromHistory = true))
        assertEquals(0, opened)
    }

    @Test
    fun anotherIntentIsLeftToTheActivity() {
        assertFalse(take("android.intent.action.VIEW", withToken))
        assertFalse(take(null))
        assertEquals(0, opened)
    }
}

class AndroidNotebookTabTest {

    private var saved = true

    @Test
    fun anUnsupportedPhoneNeverShowsTheTab() {
        val tab = AndroidNotebookTab(supported = false, savedShown = { saved }, saveShown = { saved = it })
        assertFalse(tab.shown.value)
        tab.setShown(true)
        assertFalse(tab.shown.value)
    }

    @Test
    fun theSwitchShowsAndHidesTheTabAndIsSaved() {
        val tab = AndroidNotebookTab(supported = true, savedShown = { saved }, saveShown = { saved = it })
        assertTrue(tab.shown.value)
        tab.setShown(false)
        assertFalse(tab.shown.value)
        assertFalse(saved)
        assertFalse(AndroidNotebookTab(supported = true, savedShown = { saved }, saveShown = { saved = it }).shown.value)
    }
}
