package coredevices.resampler

import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class ResamplerTest {
    companion object {
        private val random = Random(1234)
    }

    @Test
    fun testResamplerTiming() {
        val sampleRateIn = 10000
        val sampleRateOut = 16000
        val lengthInSeconds = 10
        val input = ShortArray(sampleRateIn * lengthInSeconds) {
            random.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        // Gravel: gates the fastest run on this thread's CPU time (JDK 21
        // java.lang.management.ThreadMXBean.getCurrentThreadCpuTime: the time the thread executed).
        val threads = ManagementFactory.getThreadMXBean()
        val results = (0 until 10).map {
            val inputCopy = input.copyOf()
            val started = threads.currentThreadCpuTime
            Resampler(sampleRateIn, sampleRateOut).process(inputCopy)
            (threads.currentThreadCpuTime - started) / 1_000
        }

        val min = results.min()
        val max = results.max()
        val avg = results.average()
        val minMs = min / 1_000.0

        println("Resampling ${input.size} samples took (CPU time): min = $min µs, max = $max µs, avg = $avg µs")
        assertTrue(min > 0, "thread CPU time is not measured on this thread")
        assertTrue(minMs < 50, "The fastest resampling should take under 50 ms of CPU time, but took $minMs ms")
    }
}
