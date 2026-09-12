package coredevices.coreapp.transcription

import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.util.transcription.WhisperTranscriptionService
import coredevices.whisper.isWhisperSupported
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.koin.mp.KoinPlatform
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Measurement, not a pass/fail test: reports what the app's own whisper
 * service pays on its cold path. The service is a Koin singleton built
 * with the process, and its config collector starts the cold load at
 * once, so in a process started for this run the load is in flight the
 * way it is after a restart in the field. The probe resolves that
 * singleton and dictates the bundled clip immediately: the engine line's
 * `initWaitMs` is whatever of the load was left, the service's
 * `dictation coldpath:` line splits the load into the re-hash, the engine
 * init and the warm-up, and logcat's timestamps place both against the
 * process start. A second dictation follows for the warm figure. The
 * model is the one configured in the app; `-e model <id>` makes the
 * probe skip unless that is the configured one, so a matrix run cannot
 * silently measure the wrong model. Run on its own, after a force-stop,
 * once per configured model and device:
 *   adb shell am force-stop com.anopticlabs.gravel
 *   adb shell am instrument -w \
 *     -e class coredevices.coreapp.transcription.DictationColdPathProbe \
 *     -e model whisper-base-en \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d | grep -E 'ColdPathProbe|dictation (coldpath|engine)'
 */
class DictationColdPathProbe {

    private companion object {
        const val TAG = "ColdPathProbe"
        const val CLIP_ASSET = "eval_shopping_list_shrimp.raw"
        const val KEYWORD = "shrimp"
        const val SAMPLE_RATE = 16_000
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

    @Test
    fun recordColdPath() {
        Assume.assumeTrue("engine unsupported on this CPU", isWhisperSupported())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Assets live in the test APK, so read them from the instrumentation context.
        val clip = instrumentation.context.assets.open(CLIP_ASSET).use { it.readBytes() }
        val service = KoinPlatform.getKoin().get<WhisperTranscriptionService>()
        val configured = service.configuredModel
        InstrumentationRegistry.getArguments().getString("model")?.let { wanted ->
            Assume.assumeTrue(
                "the app has $configured configured, not $wanted; change the model in the app first",
                wanted == configured,
            )
        }
        Assume.assumeTrue("no local model installed for $configured", service.isLocalAvailable())
        val processAgeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        log(
            "device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} model=$configured pid=${Process.myPid()} " +
                "processAgeMs=$processAgeMs modelReady=${service.isModelReady} cpuset=${cpuset()} allowed=${allowed()}",
        )
        // Straight into the dictation, with a ceiling far above the app's,
        // so it waits out whatever is left of the load and reports the wait.
        runBlocking {
            // The figures describe a dictation that decoded the clip; a
            // blank or wrong decode fails the probe rather than being timed.
            val cold = service.transcribeLocal(clip, SAMPLE_RATE, timeout = 60.seconds, initTimeout = 5.minutes)
            log("cold dictation: '$cold'")
            check(cold.lowercase().contains(KEYWORD)) { "cold decode lost the keyword: '$cold'" }
            val warm = service.transcribeLocal(clip, SAMPLE_RATE, timeout = 60.seconds)
            log("warm dictation: '$warm'")
            check(warm.lowercase().contains(KEYWORD)) { "warm decode lost the keyword: '$warm'" }
        }
    }
}
