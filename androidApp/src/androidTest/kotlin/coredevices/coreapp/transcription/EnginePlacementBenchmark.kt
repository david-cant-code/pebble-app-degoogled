package coredevices.coreapp.transcription

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.coreapp.testsupport.ReadOnlyModelPathProvider
import coredevices.whisper.EnginePlacement
import coredevices.whisper.isWhisperSupported
import coredevices.whisper.pcm16ToFloats
import coredevices.whisper.whisperFree
import coredevices.whisper.whisperInit
import coredevices.whisper.whisperTranscribe
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.time.TimeSource

/**
 * Measurement, not a pass/fail test: decodes the bundled speech clip under
 * every combination of affinity mask, thread count and priority the engine
 * placement can express, and prints a table. Masks stand in for the
 * cpusets a non-visible process lands in on this and other devices (all
 * cores, the six non-big cores, the four little cores), so the numbers
 * show what a dictation pays when the app is not on screen and what the
 * thread-count and placement changes buy back. The clip must decode
 * successfully under every configuration; timings are reported through
 * the instrumentation output and logcat under the tag below.
 */
class EnginePlacementBenchmark {

    private companion object {
        const val TAG = "PlacementBench"
        const val CLIP_ASSET = "eval_shopping_list_shrimp.raw"
        const val KEYWORD = "shrimp"
        const val RUNS = 3
    }

    private data class Config(val label: String, val mask: Long, val threads: Int, val nice: Int)

    private fun log(line: String) {
        android.util.Log.i(TAG, line)
        println("[$TAG] $line")
    }

    private fun maxFreqKHz(cpu: Int): Long? = runCatching {
        File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
    }.getOrNull()

    private fun maskOf(cpus: Iterable<Int>): Long = cpus.fold(0L) { acc, cpu -> acc or (1L shl cpu) }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun cpuset(): String = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")

    private fun allowedList(): String = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()
        }
    }.getOrNull() ?: "?"

    @Test
    fun measurePlacementMatrix() {
        Assume.assumeTrue("engine unsupported on this CPU", isWhisperSupported())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // An instrumented process without a visible activity sits in the
        // foreground cpuset, which on this device excludes the big cores.
        // Bringing the app's own activity up makes the process top-app, so
        // the unmasked run is the visible-app baseline and the explicit
        // masks emulate the restricted cpusets a non-visible process gets.
        log("before activity: cpuset=${cpuset()} allowed=${allowedList()}")
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.startActivitySync(intent)
            Thread.sleep(1500)
        }
        log("with activity: cpuset=${cpuset()} allowed=${allowedList()}")
        val clip = instrumentation.context.assets
            .open(CLIP_ASSET).use { it.readBytes() }
        val pcm = pcm16ToFloats(clip)
        val provider = ReadOnlyModelPathProvider(File(context.filesDir, "models"), "whisper-base-en")

        val online = Runtime.getRuntime().availableProcessors()
        val cpus = (0 until online).toList()
        val byFreq = cpus.sortedByDescending { maxFreqKHz(it) ?: 0L }
        log("cpus=$online maxFreqKHz=${cpus.map { "$it:${maxFreqKHz(it)}" }} fastestFirst=$byFreq")

        val allMasks = linkedMapOf(
            "all" to 0L,
            "cpu0-5" to maskOf(0..5),
            "cpu0-3" to maskOf(0..3),
            "cpu0-2" to maskOf(0..2),
            "fastest4" to maskOf(byFreq.take(4)),
            "fastest2" to maskOf(byFreq.take(2)),
        )
        // Instrumentation arguments narrow the matrix for focused runs:
        // -e masks all,cpu0-2 -e threads 2,3,4,6 -e nice 0
        val args = InstrumentationRegistry.getArguments()
        fun listArg(name: String, default: List<String>): List<String> =
            args.getString(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default
        val masks = listArg("masks", listOf("all", "cpu0-5", "cpu0-3", "fastest4", "fastest2"))
            .associateWith { allMasks.getValue(it) }
        val threadCounts = listArg("threads", listOf("2", "4", "6")).map { it.toInt() }
        val niceValues = listArg("nice", listOf("0", "-10")).map { it.toInt() }
        val fullMatrix = buildList {
            for ((label, mask) in masks) for (threads in threadCounts) for (nice in niceValues) {
                add(Config(label, mask, threads, nice))
            }
        }
        // The small model decodes about three times slower, so it runs the
        // requested thread counts at nice 0 only, without the two-core mask.
        val reducedMatrix = buildList {
            for ((label, mask) in masks.filterKeys { it != "fastest2" }) {
                for (threads in threadCounts) add(Config(label, mask, threads, 0))
            }
        }

        for ((modelId, matrix) in listOf("whisper-base-en" to fullMatrix, "whisper-small-en" to reducedMatrix)) {
            if (!provider.isModelDownloaded(modelId)) {
                log("model $modelId not installed; skipping")
                continue
            }
            val path = runBlocking { provider.getModelPath(modelId) }
            val handle = whisperInit(path)
            var callId = 1L
            try {
                // Warm-up absorbs the one-time graph and buffer setup.
                whisperTranscribe(handle, pcm, 4, "en", callId++, EnginePlacement.DEFAULT)
                log("model=$modelId clipSec=${"%.2f".format(pcm.size / 16_000.0)} runs=$RUNS (median ms)")
                for (config in matrix) {
                    val placement = EnginePlacement(cpuMask = config.mask, nice = config.nice)
                    val times = ArrayList<Long>(RUNS)
                    var text = ""
                    repeat(RUNS) {
                        val start = TimeSource.Monotonic.markNow()
                        text = whisperTranscribe(handle, pcm, config.threads, "en", callId++, placement)
                        times += start.elapsedNow().inWholeMilliseconds
                    }
                    val ok = text.lowercase().contains(KEYWORD)
                    log(
                        "model=$modelId mask=${config.label} threads=${config.threads} nice=${config.nice} " +
                            "medianMs=${median(times)} runsMs=$times ok=$ok",
                    )
                    check(ok) { "decode lost the keyword under $config: '$text'" }
                }
            } finally {
                whisperFree(handle)
            }
        }
    }
}
