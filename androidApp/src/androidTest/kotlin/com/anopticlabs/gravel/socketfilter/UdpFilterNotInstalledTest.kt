package com.anopticlabs.gravel.socketfilter

import com.anopticlabs.gravel.pkjs.NetworkDenyEnforcement
import org.junit.Test
import org.koin.mp.KoinPlatform
import java.net.DatagramSocket
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Instrumentation runs in the app's own process, after MainApplication has started it.
class UdpFilterNotInstalledTest {
    // Name lookups on LineageOS need UDP sockets in the app process (KNOWN_ISSUES.md, "The UDP
    // filter is off"), so this runs on every device, ARM or not.
    @Test
    fun theAppProcessStartsUnfiltered() {
        assertNull(UdpSocketFilter.lastResult, "the app process ran a filter install")
        UdpSocketFilter.selfTest()?.let { report ->
            assertTrue(report.datagram.all { it == 0 }, "UDP sockets in the app process: ${report.datagram}")
        }
        DatagramSocket().close()
        assertFalse(
            KoinPlatform.getKoin().get<NetworkDenyEnforcement>().primaryLayerActive,
            "the app's graph reports a filter that is not installed",
        )
    }
}
