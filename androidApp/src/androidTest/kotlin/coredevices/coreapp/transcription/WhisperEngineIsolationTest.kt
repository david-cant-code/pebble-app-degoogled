package coredevices.coreapp.transcription

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.coreapp.testsupport.ReadOnlyModelPathProvider
import coredevices.whisper.EnginePlacement
import coredevices.whisper.TranscribeStats
import coredevices.whisper.WhisperEngineClient
import coredevices.whisper.WhisperEngineUnavailableException
import coredevices.whisper.isIsolatedUid
import coredevices.whisper.isWhisperSupported
import coredevices.whisper.pcm16ToFloats
import coredevices.whisper.whisperFree
import coredevices.whisper.whisperInit
import coredevices.whisper.whisperTranscribe
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * On-device guard for the engine process boundary: the engine runs in an
 * isolated process, a dictation and a full firmware window of audio
 * cross it, a handle from a dead engine process is refused rather than
 * dereferenced, the app process outlives the engine process and binds a
 * fresh one, a handle inside one call refuses a second, a call the
 * engine does not answer in time ends its process, the app can end it
 * on its own account, a calling thread's StrictMode policy is restored
 * after a call, and the engine process keeps no descriptor from the models it
 * is handed. The uid gate in front of every transaction has no negative
 * case here: the instrumentation shares the app's uid, and a foreign
 * uid cannot reach a service that is not exported. Uses the installed
 * base-en model and never downloads. Run on its own, against a
 * persistent install with the model:
 *   adb shell am instrument -w \
 *     -e class coredevices.coreapp.transcription.WhisperEngineIsolationTest \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class WhisperEngineIsolationTest {

    private companion object {
        const val TAG = "EngineIsolation"
        const val MODEL = "whisper-base-en"
        const val CLIP_ASSET = "eval_shopping_list_shrimp.raw"
        const val KEYWORD = "shrimp"
        const val WINDOW_SAMPLES = 15 * 16_000
        const val THREADS = 4
        const val GOOD_LOADS = 8
        const val FAILED_LOADS = 4
    }

    private fun log(line: String) {
        android.util.Log.i(TAG, line)
        println("[$TAG] $line")
    }

    /**
     * Skips on a device the engine does not support. The attach comes
     * first and is an assertion: an unattached client reads as
     * unsupported, so a lost attach would otherwise skip every case here
     * rather than fail one.
     */
    private fun assumeEngine() {
        assertTrue(WhisperEngineClient.isAttached, "the engine client was not attached in the application's onCreate")
        Assume.assumeTrue("engine unsupported on this device", isWhisperSupported())
    }

    /** The installed model's path, or the test is skipped. */
    private fun modelPath(): String {
        assumeEngine()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ReadOnlyModelPathProvider(File(context.filesDir, "models"), MODEL)
        Assume.assumeTrue("$MODEL not installed; tests never download", provider.isModelDownloaded(MODEL))
        return runBlocking { provider.getModelPath(MODEL) }
    }

    private fun clip(): FloatArray = pcm16ToFloats(
        InstrumentationRegistry.getInstrumentation().context.assets.open(CLIP_ASSET).use { it.readBytes() },
    )

    @Test
    fun engineRunsInAnIsolatedProcess() {
        assumeEngine()
        val runtime = assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = true))
        log(
            "engine pid=${runtime.pid} uid=${runtime.uid} cpuset=${runtime.cpuset} " +
                "allowed=${runtime.cpusAllowedList} importance=${runtime.importance} " +
                "oomScoreAdj=${runtime.oomScoreAdj} bindMs=${WhisperEngineClient.lastBindMillis()}",
        )
        assertNotEquals(Process.myPid(), runtime.pid, "the engine answered from the app process")
        assertNotEquals(Process.myUid(), runtime.uid, "the engine runs under the app's own uid")
        assertTrue(isIsolatedUid(runtime.uid), "engine uid ${runtime.uid} is not an isolated uid")
    }

    @Test
    fun dictationCrossesTheBoundary() {
        val handle = whisperInit(modelPath())
        try {
            val pcm = clip()
            val stats = TranscribeStats()
            val text = whisperTranscribe(handle, pcm, THREADS, "en", 1L, EnginePlacement.DEFAULT, stats)
            log("dictation: '$text' inputSamples=${stats.inputSamples}")
            assertTrue(text.lowercase().contains(KEYWORD), "transcription '$text' lost '$KEYWORD' across the boundary")
            assertEquals(pcm.size, stats.inputSamples, "the engine reported a different sample count than it was sent")
        } finally {
            whisperFree(handle)
        }
    }

    /** The largest audio a dictation can carry, far beyond one Binder transaction. */
    @Test
    fun fullWindowCrossesTheBoundary() {
        val handle = whisperInit(modelPath())
        try {
            val speech = clip()
            val window = FloatArray(WINDOW_SAMPLES) { speech[it % speech.size] }
            val start = SystemClock.elapsedRealtime()
            val text = whisperTranscribe(handle, window, THREADS, "en", 2L, EnginePlacement.DEFAULT)
            log("full window decoded in ${SystemClock.elapsedRealtime() - start} ms: '${text.take(80)}'")
            assertTrue(text.isNotBlank(), "a full window of speech transcribed to nothing")
        } finally {
            whisperFree(handle)
        }
    }

    @Test
    fun appOutlivesTheEngineProcess() {
        val path = modelPath()
        val handle = whisperInit(path)
        val before = assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = false))
        // A plain kill from the shell cannot signal an isolated uid; the
        // activity manager schedules a crash inside the process instead.
        // The shell command is split on spaces by the instrumentation, so
        // no quoting or redirection can appear in it.
        log("crashing engine pid ${before.pid}: ${shell("am crash ${before.pid}")}")
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (WhisperEngineClient.isConnected && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        if (WhisperEngineClient.isConnected) log("engine pid ${before.pid} after the crash request: ${shell("ps -p ${before.pid}")}")
        assertTrue(!WhisperEngineClient.isConnected, "the binding to the crashed engine process was not released")

        // The old handle names a context in a dead process; the fresh
        // engine process the call binds must refuse it by its check, not
        // die on it: a dereference would also read as an unavailable
        // engine, so the message and the generation count tell them apart.
        val generation = WhisperEngineClient.processGeneration()
        val refused = assertFailsWith<WhisperEngineUnavailableException> {
            whisperTranscribe(handle, clip(), THREADS, "en", 3L)
        }
        assertTrue(
            refused.message.orEmpty().contains("was not issued by this engine process"),
            "the stale handle was not refused by the engine's check: ${refused.message}",
        )
        val fresh = whisperInit(path)
        try {
            val after = assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = false))
            log("engine restarted as pid ${after.pid}, bindMs=${WhisperEngineClient.lastBindMillis()}")
            assertNotEquals(before.pid, after.pid, "the engine did not come back in a new process")
            assertEquals(generation + 1, WhisperEngineClient.processGeneration(), "the refusal and the reload took more than one engine process")
            val text = whisperTranscribe(fresh, clip(), THREADS, "en", 4L)
            assertTrue(text.lowercase().contains(KEYWORD), "post-restart transcription '$text' lost '$KEYWORD'")
        } finally {
            whisperFree(fresh)
        }
    }

    /**
     * The engine process refuses a second call on a handle inside one,
     * through the actuals so the app-side serialization is bypassed: the
     * first call decodes a full window, the second arrives while it runs.
     */
    @Test
    fun aHandleInsideACallRefusesASecondCall() {
        val handle = whisperInit(modelPath())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val speech = clip()
            val window = FloatArray(WINDOW_SAMPLES) { speech[it % speech.size] }
            val first = executor.submit<String> { whisperTranscribe(handle, window, THREADS, "en", 6L) }
            Thread.sleep(500)
            assertTrue(!first.isDone, "the full window finished before the second call could arrive")
            val refused = assertFailsWith<IllegalStateException> {
                whisperTranscribe(handle, speech, THREADS, "en", 7L)
            }
            assertTrue(refused.message.orEmpty().contains("is inside another call"), "unexpected refusal: ${refused.message}")
            assertTrue(first.get().isNotBlank(), "the refused second call disturbed the first")
        } finally {
            executor.shutdownNow()
            whisperFree(handle)
        }
    }

    /**
     * A transaction the engine never answers ends the engine process and
     * fails the call as an unavailable engine; the next call binds a
     * fresh process. A healthy engine's full-window decode stands in for
     * the silent one: the deadline is capped below its three seconds.
     */
    @Test
    fun aCallTheEngineDoesNotAnswerInTimeEndsItsProcess() {
        val path = modelPath()
        val handle = whisperInit(path)
        val before = assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = false))
        val generation = WhisperEngineClient.processGeneration()
        WhisperEngineClient.transactionDeadlineCapForTests = 500.milliseconds
        try {
            val speech = clip()
            val window = FloatArray(WINDOW_SAMPLES) { speech[it % speech.size] }
            val failed = assertFailsWith<WhisperEngineUnavailableException> {
                whisperTranscribe(handle, window, THREADS, "en", 8L)
            }
            assertTrue(failed.message.orEmpty().contains("did not answer"), "unexpected failure: ${failed.message}")
            assertTrue(!WhisperEngineClient.isConnected, "the binding to the silent engine process was not released")
        } finally {
            WhisperEngineClient.transactionDeadlineCapForTests = null
        }
        val fresh = whisperInit(path)
        try {
            val after = assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = false))
            log("engine ended for a missed deadline as pid ${before.pid}, back as pid ${after.pid}")
            assertNotEquals(before.pid, after.pid, "the silent engine process was not ended")
            assertEquals(generation + 1, WhisperEngineClient.processGeneration(), "the expiry and the reload took more than one engine process")
            val text = whisperTranscribe(fresh, clip(), THREADS, "en", 9L)
            assertTrue(text.lowercase().contains(KEYWORD), "post-expiry transcription '$text' lost '$KEYWORD'")
        } finally {
            whisperFree(fresh)
        }
    }

    /**
     * The app can end the engine process on its own account, as the
     * transcription service does for a decode that ignores its abort:
     * the death listeners hear which process went, a handle from it is
     * refused by the fresh process the next call binds, and that
     * process is a new one.
     */
    @Test
    fun theAppCanEndTheEngineProcess() {
        val path = modelPath()
        val handle = whisperInit(path)
        val before = assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = false))
        val generation = WhisperEngineClient.processGeneration()
        val deaths = CopyOnWriteArrayList<Long>()
        WhisperEngineClient.addDeathListener { deaths += it }
        WhisperEngineClient.endProcess("isolation test")
        assertTrue(!WhisperEngineClient.isConnected, "the binding to the ended engine process was not released")
        assertEquals(listOf(generation), deaths, "the death listeners did not hear the ended process")
        val refused = assertFailsWith<WhisperEngineUnavailableException> {
            whisperTranscribe(handle, clip(), THREADS, "en", 10L)
        }
        assertTrue(
            refused.message.orEmpty().contains("was not issued by this engine process"),
            "the handle from the ended process was not refused by the fresh one: ${refused.message}",
        )
        val fresh = whisperInit(path)
        try {
            val after = assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = false))
            log("engine ended on request as pid ${before.pid}, back as pid ${after.pid}")
            assertNotEquals(before.pid, after.pid, "the engine process was not ended")
            assertEquals(generation + 1, WhisperEngineClient.processGeneration(), "the end and the reload took more than one engine process")
            val text = whisperTranscribe(fresh, clip(), THREADS, "en", 11L)
            assertTrue(text.lowercase().contains(KEYWORD), "post-end transcription '$text' lost '$KEYWORD'")
        } finally {
            whisperFree(fresh)
        }
    }

    /**
     * The client sends a transaction without the calling thread's
     * StrictMode policy and restores it afterwards. What this case can
     * show is the restore and that the call under such a policy reads
     * its reply: the engine writes its header before its handler runs,
     * so a violation the handler gathers can reach a header only on the
     * engine's catch path, which no call here takes.
     */
    @Test
    fun theCallingThreadsStrictModePolicyIsRestoredAfterATransaction() {
        assumeEngine()
        val original = StrictMode.getThreadPolicy()
        val detecting = StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build()
        StrictMode.setThreadPolicy(detecting)
        try {
            val report = assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = true))
            assertTrue(WhisperEngineClient.isConnected, "the engine process was ended under a StrictMode policy")
            assertNotNull(report.cpusAllowedList, "the engine's procfs read did not reach the report")
            assertEquals(detecting.toString(), StrictMode.getThreadPolicy().toString(), "the thread's policy was not restored after the call")
        } finally {
            StrictMode.setThreadPolicy(original)
        }
    }

    /**
     * Every model descriptor handed to the engine process is closed
     * there, whether the load succeeds or fails on a truncated file. A
     * descriptor kept per load would add one for each of the loads
     * below; the process's total drifts by a few descriptors between two
     * reads on its own, so the bound is half the load count.
     */
    @Test
    fun theEngineProcessKeepsNoModelDescriptor() {
        val path = modelPath()
        whisperFree(whisperInit(path))
        val before = assertNotNull(assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = false)).openFds)
        repeat(GOOD_LOADS) { whisperFree(whisperInit(path)) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val truncated = File(context.cacheDir, "truncated-model.bin")
        File(path).inputStream().use { input ->
            val head = ByteArray(1 shl 20)
            val read = input.read(head)
            truncated.outputStream().use { it.write(head, 0, read) }
        }
        try {
            repeat(FAILED_LOADS) { assertFailsWith<RuntimeException> { whisperInit(truncated.absolutePath) } }
        } finally {
            truncated.delete()
        }
        val after = assertNotNull(assertNotNull(WhisperEngineClient.runtime(bindIfNeeded = false)).openFds)
        val loads = GOOD_LOADS + FAILED_LOADS
        log("engine open descriptors: $before before, $after after $GOOD_LOADS loads and $FAILED_LOADS failed loads")
        assertTrue(
            after - before < loads / 2,
            "the engine process went from $before to $after open descriptors over $loads model loads",
        )
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString().trim() }
    }
}
