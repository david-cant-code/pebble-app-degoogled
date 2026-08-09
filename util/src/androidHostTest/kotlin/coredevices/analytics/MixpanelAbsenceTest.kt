package coredevices.analytics

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MixpanelAbsenceTest {
    // The Mixpanel backend was swapped for a no-op at the DI seam and every
    // consumer stays wired, so an upstream merge that restores the factory
    // together with its dependency would compile cleanly and re-enable
    // telemetry silently. This probe fails the moment the Mixpanel artifact
    // returns to util's classpath, whatever the source code looks like.
    @Test
    fun mixpanelIsAbsentFromTheClasspath() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.mixpanel.android.mpmetrics.MixpanelAPI")
        }
    }
}
