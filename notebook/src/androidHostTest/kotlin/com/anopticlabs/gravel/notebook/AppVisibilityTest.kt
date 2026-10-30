package com.anopticlabs.gravel.notebook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppVisibilityTest {

    @Test
    fun backgroundedUntilAnActivityStartsAndAgainOnceEveryOneStopped() {
        val visibility = AppVisibility()
        assertTrue(visibility.isBackgrounded)
        visibility.onStarted()
        visibility.onStarted()
        visibility.onStopped()
        assertFalse(visibility.isBackgrounded)
        visibility.onStopped()
        assertTrue(visibility.isBackgrounded)
    }

    @Test
    fun anUnmatchedStopNeverCountsBelowZero() {
        val visibility = AppVisibility()
        visibility.onStopped()
        visibility.onStarted()
        assertFalse(visibility.isBackgrounded)
    }

    @Test
    fun aResumedListenerHearsTheStateNowAndEachChangeOnce() {
        val visibility = AppVisibility()
        val heard = mutableListOf<Boolean>()
        visibility.addResumedListener { heard += it }
        visibility.onResumed()
        visibility.onResumed()
        visibility.onPaused()
        assertTrue(visibility.isResumed)
        visibility.onPaused()
        visibility.onPaused()
        visibility.onResumed()
        assertEquals(listOf(false, true, false, true), heard)
    }

    @Test
    fun aBackgroundedListenerHearsOnlyTheLastStop() {
        val visibility = AppVisibility()
        var heard = 0
        visibility.addBackgroundedListener { heard++ }
        visibility.onStarted()
        visibility.onStarted()
        visibility.onStopped()
        assertEquals(0, heard)
        visibility.onStopped()
        visibility.onStopped()
        assertEquals(1, heard)
    }
}
