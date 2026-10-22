package com.anopticlabs.gravel.notebook.pipeline

import android.app.ActivityManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.koin.mp.KoinPlatform
import kotlin.test.assertTrue

/**
 * The app's keep-alive on the device: a processing or recording hold starts
 * NotebookProcessingService in the foreground and takes the wake lock, both run while either hold
 * is taken, and a release and hold in one main-thread turn leave the service running.
 */
class NotebookProcessingServiceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val keepAlive = KoinPlatform.getKoin().get<AndroidCaptureKeepAlive>()

    @Test
    fun aHoldStartsTheServiceAndTakesTheWakeLockAndAReleaseEndsBoth() {
        instrumentation.runOnMainSync { keepAlive.hold() }
        try {
            assertTrue(waitFor { serviceInForeground() }, "a hold must start the service in the foreground")
            assertTrue(wakeLockHeld(), "a hold must take the wake lock")
        } finally {
            instrumentation.runOnMainSync { keepAlive.release() }
        }
        assertTrue(waitFor { !serviceInForeground() }, "a release must stop the service")
        assertTrue(waitFor { !wakeLockHeld() }, "a release must let the wake lock go")
    }

    @Test
    fun aReleaseAndHoldInOneTurnKeepTheService() {
        instrumentation.runOnMainSync { keepAlive.hold() }
        try {
            assertTrue(waitFor { serviceInForeground() })
            instrumentation.runOnMainSync {
                keepAlive.release()
                keepAlive.hold()
            }
            Thread.sleep(1_000)
            assertTrue(serviceInForeground(), "a hold taken again in the same turn must keep the service")
        } finally {
            instrumentation.runOnMainSync { keepAlive.release() }
        }
        assertTrue(waitFor { !serviceInForeground() })
    }

    @Test
    fun aRecordingHoldAloneRunsTheServiceUntilItsRelease() {
        instrumentation.runOnMainSync { keepAlive.holdRecording() }
        try {
            assertTrue(waitFor { serviceInForeground() }, "a recording hold must start the service in the foreground")
            assertTrue(wakeLockHeld(), "a recording hold must take the wake lock")
        } finally {
            instrumentation.runOnMainSync { keepAlive.releaseRecording() }
        }
        assertTrue(waitFor { !serviceInForeground() }, "releasing the recording hold must stop the service")
        assertTrue(waitFor { !wakeLockHeld() })
    }

    @Test
    fun eachHoldKeepsTheServiceAndWakeLockWhileTheOtherIsReleased() {
        instrumentation.runOnMainSync {
            keepAlive.holdRecording()
            keepAlive.hold()
        }
        try {
            assertTrue(waitFor { serviceInForeground() })
            instrumentation.runOnMainSync { keepAlive.release() }
            Thread.sleep(1_000)
            assertTrue(serviceInForeground(), "the recording hold must keep the service after processing lets go")
            assertTrue(wakeLockHeld(), "the recording hold must keep the wake lock after processing lets go")
            instrumentation.runOnMainSync {
                keepAlive.hold()
                keepAlive.releaseRecording()
            }
            Thread.sleep(1_000)
            assertTrue(serviceInForeground(), "the processing hold must keep the service after the recording lets go")
            assertTrue(wakeLockHeld(), "the processing hold must keep the wake lock after the recording lets go")
        } finally {
            instrumentation.runOnMainSync {
                keepAlive.releaseRecording()
                keepAlive.release()
            }
        }
        assertTrue(waitFor { !serviceInForeground() })
        assertTrue(waitFor { !wakeLockHeld() })
    }

    private fun serviceInForeground(): Boolean {
        @Suppress("DEPRECATION") // Still answers for the caller's own services.
        return context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).any {
            it.service.className == NotebookProcessingService::class.java.name && it.foreground
        }
    }

    private fun wakeLockHeld(): Boolean {
        val out = instrumentation.uiAutomation.executeShellCommand("dumpsys power")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(out).bufferedReader().use { it.readText() }
        return text.lines().any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("'gravel:notebook-processing'") }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(100) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return false
    }
}
