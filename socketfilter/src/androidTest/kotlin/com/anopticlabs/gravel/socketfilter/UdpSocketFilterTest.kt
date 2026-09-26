package com.anopticlabs.gravel.socketfilter

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
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

    // Everything that needs the real install is in this one ordered method: the filter is
    // one-way and the class shares one process.
    @Test
    fun theFilterRefusesUdpSocketsInEveryThreadAndNothingElse() {
        assumeTrue("needs an ARM device", deviceIsArm)
        assertTrue(UdpSocketFilter.loadLibrary(), "the library did not load")

        // Baseline, so a later refusal is the filter's doing.
        val baseline = assertNotNull(UdpSocketFilter.selfTest())
        assertEquals(listOf(0, 0, 0, 0), baseline.datagram, "a UDP socket was refused before install")
        assertTrue(baseline.othersCreated, "baseline: ${baseline.others}")
        val lookupBefore = lookupOutcome("example.com")
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
        // The IPv6 socket is refused only if the check's thread carries the filter.
        ProbeTestHooks.setProbeBehavior(4, 0)
        assertEquals(
            InstallResult.Unsupported(UnsupportedReason.ResolverUnreachable, OsConstants.ECONNREFUSED),
            UdpSocketFilter.install(),
        )
        ProbeTestHooks.setProbeBehavior(5, 0)
        assertEquals(
            InstallResult.Unsupported(UnsupportedReason.ResolverUncheckable, OsConstants.ETIMEDOUT),
            UdpSocketFilter.install(),
        )
        ProbeTestHooks.setProbeBehavior(0, 0)
        awaitThreadGone(RESOLVER_CHECK_THREAD)
        // The platform's DNS client, not a test mode, declines here (dns_open_proxy honors it);
        // before Android 10 the check has no android_res_nsend to call.
        assertEquals(
            if (Build.VERSION.SDK_INT < 29) {
                InstallResult.Unsupported(UnsupportedReason.ResolverUncheckable, OsConstants.ENOSYS)
            } else {
                InstallResult.Unsupported(UnsupportedReason.ResolverUnreachable, OsConstants.ENOSYS)
            },
            withResolverProxyOff { UdpSocketFilter.install() },
        )
        awaitThreadGone(RESOLVER_CHECK_THREAD)
        assertEquals(
            listOf(0, 0, 0, 0),
            assertNotNull(UdpSocketFilter.selfTest()).datagram,
            "a refused install left a filter behind",
        )

        // What the real check must answer: whether a lookup works from a thread carrying the filter.
        val lookupUnderFilter = if (lookupBefore == "resolved") lookupOnAFilteredThread("example.net") else null
        // The check's thread outlives its answer, as it can by chance; the install still goes in.
        ProbeTestHooks.setProbeBehavior(6, 0)
        val result = try {
            UdpSocketFilter.install()
        } finally {
            ProbeTestHooks.setProbeBehavior(0, 0)
        }
        if (result != InstallResult.Installed) {
            release.countDown()
            if (Build.VERSION.SDK_INT < 29) {
                assertEquals(InstallResult.Unsupported(UnsupportedReason.ResolverUncheckable, OsConstants.ENOSYS), result)
            } else {
                assertEquals(UnsupportedReason.ResolverUnreachable, (result as? InstallResult.Unsupported)?.reason, "$result")
                assertTrue(lookupUnderFilter != "resolved", "the check declined, but a lookup under the filter resolved")
            }
            assertEquals(listOf(0, 0, 0, 0), assertNotNull(UdpSocketFilter.selfTest()).datagram)
            if (lookupBefore == "resolved") assertEquals("resolved", lookupOutcome("example.org"))
            println("The resolver check declined on this device ($result); the installed-filter checks did not run")
            return
        }
        lookupUnderFilter?.let { assertEquals("resolved", it, "the filter installed, but a lookup under it failed") }
            ?: println("No name resolution on this device; the check's answer not compared with a lookup")
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
        val inProcess = withResolverProxyOff { runCatching { InetAddress.getByName("in-process.example.com") }.exceptionOrNull() }
        assertTrue(inProcess is UnknownHostException, "an in-process lookup failed with $inProcess")
        assertEquals(OsConstants.EPROTONOSUPPORT, errnoCause(inProcess), "an in-process lookup did not meet the filter")
        if (lookupBefore == "resolved") {
            assertEquals(lookupBefore, lookupOutcome("example.org"), "a name lookup failed under the filter")
        } else {
            println("No name resolution on this device ($lookupBefore); lookups under the filter not checked")
        }

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

    // The program is attached by the time the post-check runs, so this install cannot share
    // the class's process with the ordered method's.
    @Test
    fun anInstallWhosePostCheckFailsIsNotRepeated() {
        assumeTrue("needs an ARM device", deviceIsArm)
        assertTrue(UdpSocketFilter.loadLibrary(), "the library did not load")
        ProbeTestHooks.setProbeBehavior(3, OsConstants.EPERM)
        val results = try {
            ProbeTestHooks.installTwiceInAChild("/proc/self/exe")
        } finally {
            ProbeTestHooks.setProbeBehavior(0, 0)
        }
        assertEquals(
            listOf(InstallResult.Refused(RefusalStage.PostCheck, OsConstants.EPERM), InstallResult.AlreadyInstalled),
            assertNotNull(results, "the child reported nothing").map(UdpSocketFilter::decode),
        )
    }

    // Runs where the device itself is not ARM, such as an x86_64 emulator image that translates
    // ARM code: the real install() reports the mismatch and leaves the process unfiltered.
    @Test
    fun onADeviceThatIsNotArmTheRealInstallIsAnArchitectureMismatch() {
        assumeTrue("needs a device whose primary ABI is not ARM", !deviceIsArm)
        assertEquals(
            InstallResult.Unsupported(UnsupportedReason.ArchitectureMismatch, 0),
            UdpSocketFilter.install(),
        )
        DatagramSocket().close()
    }

    private val deviceIsArm = UdpSocketFilter.primaryAbiIsArm(Build.SUPPORTED_ABIS.toList())

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

    // Callers pass a different name after the install: one this process has resolved is
    // answered from a cache without reaching the resolver (android16-release, libcore,
    // Inet6AddressImpl.lookupHostByName).
    private fun lookupOutcome(host: String): String =
        runCatching { InetAddress.getByName(host) }.fold({ "resolved" }, { it.javaClass.name })

    // ANDROID_DNS_MODE=local makes the netd client decline the resolver proxy (android16-release,
    // netd, client/NetdClient.cpp, dns_open_proxy), so bionic's own resolver runs in this process.
    // Changes the process environment: keep other name lookups out of this test while it runs.
    private fun <T> withResolverProxyOff(block: () -> T): T {
        val previous = Os.getenv(DNS_MODE)
        Os.setenv(DNS_MODE, "local", true)
        return try {
            block()
        } finally {
            if (previous == null) Os.unsetenv(DNS_MODE) else Os.setenv(DNS_MODE, previous, true)
        }
    }

    private fun errnoCause(failure: Throwable?): Int? =
        generateSequence(failure) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()?.errno

    private fun lookupOnAFilteredThread(host: String): String {
        var outcome = "not run"
        thread(name = ORACLE_THREAD) {
            val error = ProbeTestHooks.attachFilterToThisThread()
            outcome = if (error == 0) lookupOutcome(host) else "attach failed: $error"
        }.join(30_000)
        awaitThreadGone(ORACLE_THREAD)
        assertTrue(!outcome.startsWith("attach failed"), "the oracle's thread got no filter: $outcome")
        return outcome
    }

    // A thread that still carries its own filter fails the install's thread sync (set_filter_synced
    // in socket_filter.c waits out only the current check's thread).
    private fun awaitThreadGone(name: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        fun alive() = File("/proc/self/task").listFiles().orEmpty().any { task ->
            runCatching { File(task, "comm").readText().trim() }.getOrNull() == name
        }
        while (alive()) {
            assertTrue(System.nanoTime() < deadline, "thread $name still running")
            Thread.sleep(20)
        }
    }

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
        const val DNS_MODE = "ANDROID_DNS_MODE"
        const val RESOLVER_CHECK_THREAD = "gravel-dnscheck" // RESOLVER_CHECK_THREAD_NAME in socket_filter.c
        const val ORACLE_THREAD = "filter-oracle"
    }
}
