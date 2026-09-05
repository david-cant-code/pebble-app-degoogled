package coredevices.coreapp.transcription

import android.app.ActivityManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.util.transcription.InferenceBoost
import org.junit.Test
import org.koin.mp.KoinPlatform
import java.io.File

/**
 * Measurement, not a pass/fail test: records which cpuset and CPU mask
 * the app process holds in each state a dictation can run in, on the
 * device under test. The states are the process as instrumentation
 * leaves it, with the inference boost foreground service held, with the
 * app's activity on screen, and with the activity sent behind the
 * launcher while the service is still held. The last one is the
 * reporter's configuration: a foreground-service process that is not
 * visible. Instrumentation itself can lift the process, so the first
 * line is the baseline every later line is read against. Run on its own:
 *   adb shell am instrument -w \
 *     -e class coredevices.coreapp.transcription.ProcessPlacementProbe \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class ProcessPlacementProbe {

    private companion object {
        const val TAG = "PlacementProbe"
    }

    private fun log(line: String) {
        android.util.Log.i(TAG, line)
        println("[$TAG] $line")
    }

    private fun cpuset(): String = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")

    private fun allowed(): String = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()
        }
    }.getOrNull() ?: "?"

    private fun importance(): Int = runCatching {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
    }.getOrDefault(-1)

    private fun snapshot(state: String) {
        log("state=$state cpuset=${cpuset()} allowed=${allowed()} importance=${importance()}")
    }

    @Test
    fun recordPlacementPerState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        snapshot("instrumented, no service, no activity")

        val boost = KoinPlatform.getKoin().get<InferenceBoost>()
        boost.acquire()
        Thread.sleep(3000)
        snapshot("boost service held, no activity")

        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.startActivitySync(intent)
        }
        Thread.sleep(3000)
        snapshot("boost service held, activity on screen")

        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(home)
        Thread.sleep(5000)
        snapshot("boost service held, activity behind the launcher, 5 s")
        Thread.sleep(30_000)
        snapshot("boost service held, activity behind the launcher, 35 s")

        boost.release()
        Thread.sleep(5000)
        snapshot("service released, activity behind the launcher")
    }
}
