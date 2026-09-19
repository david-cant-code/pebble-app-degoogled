package com.anopticlabs.gravel.socketfilter

import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// This test APK has the platform's default Application, so its process starts unfiltered.
class UdpSocketFilterTest {

    // One ordered method: the filter is one-way and the process is shared by the class.
    @Test
    fun theFilterRefusesUdpSocketsInEveryThreadAndNothingElse() {
        assertTrue(UdpSocketFilter.loadLibrary(), "the library did not load")

        // Baseline, so a later refusal is the filter's doing.
        val baseline = assertNotNull(UdpSocketFilter.selfTest())
        assertEquals(listOf(0, 0, 0, 0), baseline.datagram, "a UDP socket was refused before install")
        assertTrue(baseline.othersCreated, "baseline: ${baseline.others}")
        val lookupBefore = lookupOutcome()
        val release = CountDownLatch(1)
        val parkedReports = ConcurrentHashMap<Int, SelfTestReport>()
        val parked = (0 until 3).map { index ->
            thread(name = "parked-$index") {
                release.await(60, TimeUnit.SECONDS)
                UdpSocketFilter.selfTest()?.let { parkedReports[index] = it }
            }
        }
        val countsBefore = seccompFilterCounts()

        // Refusals that leave the process unfiltered.
        ProbeTestHooks.setProbeBehavior(1, 0)
        assertEquals(
            InstallResult.Refused(RefusalStage.ProbeSignaled, OsConstants.SIGSYS),
            UdpSocketFilter.install(),
        )
        ProbeTestHooks.setProbeBehavior(2, OsConstants.ENOSYS)
        assertEquals(
            InstallResult.Unsupported(UnsupportedReason.KernelLacksFilterMode, OsConstants.ENOSYS),
            UdpSocketFilter.install(),
        )
        ProbeTestHooks.setProbeBehavior(2, OsConstants.EPERM)
        assertEquals(
            InstallResult.Refused(RefusalStage.SeccompCall, OsConstants.EPERM),
            UdpSocketFilter.install(),
        )
        ProbeTestHooks.setProbeBehavior(0, 0)
        assertEquals(
            InstallResult.Unsupported(UnsupportedReason.ArchitectureMismatch, 0),
            UdpSocketFilter.install(elfHeaderForAnotherMachine().path),
        )
        assertEquals(
            listOf(0, 0, 0, 0),
            assertNotNull(UdpSocketFilter.selfTest()).datagram,
            "a refused install left a filter behind",
        )

        assertEquals(InstallResult.Installed, UdpSocketFilter.install())
        assertEquals(InstallResult.Installed, UdpSocketFilter.lastResult)

        val after = assertNotNull(UdpSocketFilter.selfTest())
        assertTrue(after.datagramRefused, "UDP sockets after install: ${after.datagram}")
        assertTrue(after.othersCreated, "other sockets after install: ${after.others}")
        assertFailsWith<SocketException> { DatagramSocket().close() }

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                server.accept().use { accepted ->
                    client.getOutputStream().write(7)
                    assertEquals(7, accepted.getInputStream().read())
                }
            }
        }
        assertEquals(lookupBefore, lookupOutcome(), "a name lookup changed its outcome")

        // Threads that existed before the install, and one started after it.
        release.countDown()
        parked.forEach { it.join(10_000) }
        assertEquals(3, parkedReports.size)
        parkedReports.values.forEach { assertTrue(it.datagramRefused, "a parked thread: ${it.datagram}") }
        var lateReport: SelfTestReport? = null
        thread { lateReport = UdpSocketFilter.selfTest() }.join(10_000)
        assertTrue(assertNotNull(lateReport).datagramRefused)

        val countsAfter = seccompFilterCounts()
        if (countsBefore.isEmpty()) {
            println("Seccomp_filters is not exposed by this kernel; per-thread counts not checked")
        }
        countsBefore.forEach { (tid, before) ->
            countsAfter[tid]?.let { assertEquals(before + 1, it, "thread $tid") }
        }

        assertEquals(InstallResult.AlreadyInstalled, UdpSocketFilter.install())
        assertEquals(InstallResult.Installed, UdpSocketFilter.lastResult)
        seccompFilterCounts().forEach { (tid, count) ->
            countsAfter[tid]?.let { assertEquals(it, count, "a second install stacked a program on thread $tid") }
        }
    }

    @Test
    fun theProgramRefusesOnlyInetDatagramSocketsAndForeignArchitectures() {
        val program = assertNotNull(UdpSocketFilter.program())
        val (arch, socketCall, allow, refuse) = assertNotNull(UdpSocketFilter.constants()).toList()
        val dgram = OsConstants.SOCK_DGRAM.toLong()
        val stream = OsConstants.SOCK_STREAM.toLong()
        val flags = (OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC).toLong()
        val garbage = 0x5a5a5a5aL shl 32
        fun action(a: Long, nr: Long, domain: Long = 0, type: Long = 0) =
            evaluate(program, seccompData(nr.toInt(), a, domain, type))

        assertEquals(refuse, action(arch + 1, socketCall + 1), "a foreign architecture was allowed")
        assertEquals(refuse, action(arch + 1, socketCall, AF_UNIX, dgram))
        assertEquals(allow, action(arch, socketCall + 1, OsConstants.AF_INET.toLong(), dgram))
        for (domain in listOf(AF_UNIX, AF_NETLINK, AF_BLUETOOTH)) {
            assertEquals(allow, action(arch, socketCall, domain, dgram), "family $domain")
        }
        for (domain in listOf(OsConstants.AF_INET.toLong(), OsConstants.AF_INET6.toLong())) {
            assertEquals(allow, action(arch, socketCall, domain, stream), "stream, family $domain")
            assertEquals(refuse, action(arch, socketCall, domain, dgram), "datagram, family $domain")
            assertEquals(refuse, action(arch, socketCall, domain, dgram or flags), "flag bits, family $domain")
            assertEquals(refuse, action(arch, socketCall, domain or garbage, dgram or garbage), "high bits, family $domain")
        }
    }

    private fun lookupOutcome(): String =
        runCatching { InetAddress.getByName("example.com") }.fold({ "resolved" }, { it.javaClass.name })

    // Per-thread filter counts, where the kernel reports them (Seccomp_filters in the task status).
    private fun seccompFilterCounts(): Map<String, Int> =
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
            runCatching { File(task, "status").readLines() }.getOrNull()
                ?.firstOrNull { it.startsWith("Seccomp_filters:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
                ?.let { task.name to it }
        }.toMap()

    private fun elfHeaderForAnotherMachine(): File {
        val header = ByteArray(64)
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()).copyInto(header)
        header[5] = 1 // EI_DATA: little-endian
        header[18] = EM_X86_64
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cacheDir, "other-machine.elf").apply { writeBytes(header) }
    }

    // struct seccomp_data: nr, arch, instruction pointer, six 64-bit arguments; little-endian.
    private fun seccompData(nr: Int, arch: Long, arg0: Long, arg1: Long): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(nr).putInt(arch.toInt()).putLong(0).putLong(arg0).putLong(arg1)
        return buffer.array()
    }

    // The classic BPF subset the program uses: load word absolute, jump if equal, and, return.
    private fun evaluate(program: IntArray, data: ByteArray): Long {
        val words = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var accumulator = 0L
        var pc = 0
        while (true) {
            val code = program[pc * 4]
            val k = program[pc * 4 + 3].toLong() and 0xffffffffL
            when (code) {
                0x20 -> accumulator = words.getInt(k.toInt()).toLong() and 0xffffffffL
                0x15 -> pc += if (accumulator == k) program[pc * 4 + 1] else program[pc * 4 + 2]
                0x54 -> accumulator = accumulator and k
                0x06 -> return k
                else -> error("instruction $pc uses opcode $code, which this evaluator does not model")
            }
            pc++
        }
    }

    private companion object {
        const val AF_UNIX = 1L
        const val AF_NETLINK = 16L
        const val AF_BLUETOOTH = 31L
        const val EM_X86_64: Byte = 62
    }
}
