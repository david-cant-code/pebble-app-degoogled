package com.anopticlabs.gravel.socketfilter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The parts of UdpSocketFilter that need no device: the host JVM has no build of the library.
class UdpSocketFilterHostTest {
    private val mismatch = InstallResult.Unsupported(UnsupportedReason.ArchitectureMismatch, 0)

    @Test
    fun onlyAnArmPrimaryAbiCounts() {
        assertTrue(UdpSocketFilter.primaryAbiIsArm(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertTrue(UdpSocketFilter.primaryAbiIsArm(listOf("armeabi-v7a", "armeabi")))
        for (abis in listOf(listOf("x86_64", "arm64-v8a"), listOf("x86", "armeabi-v7a"), listOf("riscv64"), emptyList())) {
            assertFalse(UdpSocketFilter.primaryAbiIsArm(abis), "$abis")
        }
    }

    @Test
    fun aPrimaryAbiOtherThanArmIsAnArchitectureMismatch() {
        for (abis in listOf(listOf("x86_64", "arm64-v8a"), listOf("x86", "armeabi-v7a"), listOf("riscv64"), emptyList())) {
            assertEquals(mismatch, UdpSocketFilter.install("/proc/self/exe", abis), "$abis")
        }
        assertEquals(mismatch, UdpSocketFilter.lastResult)
    }

    // pack() in socket_filter.c: kind << 40 | reason << 32 | detail.
    @Test
    fun theResolverCheckReasonsDecode() {
        fun unsupported(reason: Long, detail: Int) = (3L shl 40) or (reason shl 32) or detail.toLong()
        assertEquals(
            InstallResult.Unsupported(UnsupportedReason.ResolverUnreachable, 111),
            UdpSocketFilter.decode(unsupported(3, 111)),
        )
        assertEquals(
            InstallResult.Unsupported(UnsupportedReason.ResolverUncheckable, 110),
            UdpSocketFilter.decode(unsupported(4, 110)),
        )
    }

    @Test
    fun anArmDeviceWithoutTheLibraryReportsItMissing() {
        assertEquals(
            InstallResult.Unsupported(UnsupportedReason.LibraryMissing, 0),
            UdpSocketFilter.install("/proc/self/exe", listOf("arm64-v8a")),
        )
    }
}
