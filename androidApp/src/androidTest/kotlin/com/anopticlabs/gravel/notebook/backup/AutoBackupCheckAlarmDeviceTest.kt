package com.anopticlabs.gravel.notebook.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.alarms.AlarmLimitFiller
import org.junit.Test
import kotlin.test.assertFalse

/**
 * The automatic backup check's alarm against the real AlarmManager. It moves the app's own check
 * alarm and leaves it two hours ahead, where that check sets the next one.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.backup.AutoBackupCheckAlarmDeviceTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class AutoBackupCheckAlarmDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarm = AndroidCheckAlarm(context)

    @Test
    fun theCheckAlarmMovesAtThePerAppAlarmLimit() {
        alarm.set(System.currentTimeMillis() + 3_600_000L)
        AlarmLimitFiller(context).use { filler ->
            filler.fillToLimit()
            alarm.set(System.currentTimeMillis() + 7_200_000L)
            // The moved alarm took the place it freed: the limit is met again.
            assertFalse(filler.addOne())
        }
    }
}
