package coredevices.coreapp

import android.app.ActivityManager
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.util.transcription.InferenceBoost
import org.junit.Test
import org.koin.mp.KoinPlatform
import kotlin.test.assertTrue

/**
 * Drives the real boost wiring end to end on device: acquire through the
 * DI-resolved InferenceBoost must start InferenceBoostService in the
 * foreground, and the final release must stop it. This is what actually
 * pins the AndroidInferenceBoost glue (delegation direction, Intent
 * target, main-handler post): a transposed delegation or wrong target
 * class passes every JVM test and only fails here.
 */
class InferenceBoostLifecycleTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun acquireStartsTheForegroundServiceAndFinalReleaseStopsIt() {
        val boost = KoinPlatform.getKoin().get<InferenceBoost>()
        boost.acquire()
        try {
            assertTrue(
                waitForService(expectRunning = true),
                "acquire must start InferenceBoostService in the foreground",
            )
        } finally {
            boost.release()
        }
        assertTrue(
            waitForService(expectRunning = false),
            "the final release must stop InferenceBoostService",
        )
    }

    private fun waitForService(expectRunning: Boolean): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        repeat(100) {
            @Suppress("DEPRECATION") // Still supported for the caller's own
            // services, which is all this asks about; the alternative is
            // parsing dumpsys through UiAutomation shell output.
            val running = am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == InferenceBoostService::class.java.name &&
                    it.foreground
            }
            if (running == expectRunning) return true
            Thread.sleep(100)
        }
        return false
    }
}
