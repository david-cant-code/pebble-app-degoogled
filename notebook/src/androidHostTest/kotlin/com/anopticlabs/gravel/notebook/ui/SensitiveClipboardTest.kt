package com.anopticlabs.gravel.notebook.ui

import android.app.AlarmManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveClipboardTest {

    @Test
    fun theClearAlarmGoesOffAMinuteLaterOnTheElapsedClock() {
        assertEquals(ClearAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, 5_000L + 60_000L), clearAlarm(elapsedRealtimeMillis = 5_000L))
    }

    @Test
    fun theClearAlarmIsSetBeforeTheCopy() {
        val steps = mutableListOf<String>()
        assertTrue(copyWithClearSet({ steps += "cancel" }, { steps += "set" }, { steps += "copy" }, { steps += "clear earlier" }))
        assertEquals(listOf("cancel", "set", "copy"), steps)
    }

    @Test
    fun aClearAlarmAndroidRefusesCopiesNothingAndTakesAnEarlierCopyOff() {
        val steps = mutableListOf<String>()
        val refused = { steps += "set"; throw IllegalStateException("alarm limit") }
        assertFalse(copyWithClearSet({ steps += "cancel" }, refused, { steps += "copy" }, { steps += "clear earlier" }))
        assertEquals(listOf("cancel", "set", "clear earlier"), steps)
    }
}
