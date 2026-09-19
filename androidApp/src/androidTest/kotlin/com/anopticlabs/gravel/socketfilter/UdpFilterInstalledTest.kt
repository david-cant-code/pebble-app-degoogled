package com.anopticlabs.gravel.socketfilter

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Instrumentation runs in the app's own process, after MainApplication.attachBaseContext.
class UdpFilterInstalledTest {
    @Test
    fun theAppProcessStartsWithTheFilterInstalled() {
        assertEquals(InstallResult.Installed, UdpSocketFilter.lastResult)
        assertEquals(InstallResult.AlreadyInstalled, UdpSocketFilter.install())
        val report = assertNotNull(UdpSocketFilter.selfTest())
        assertTrue(report.datagramRefused, "UDP sockets in the app process: ${report.datagram}")
        assertTrue(report.othersCreated, "other sockets in the app process: ${report.others}")
    }
}
