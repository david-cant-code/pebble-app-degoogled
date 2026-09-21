package com.anopticlabs.gravel.socketfilter

import android.os.Build
import com.anopticlabs.gravel.pkjs.NetworkDenyEnforcement
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.mp.KoinPlatform
import java.net.DatagramSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Instrumentation runs in the app's own process, after MainApplication.attachBaseContext.
class UdpFilterInstalledTest {
    // The same rule as UdpSocketFilter.primaryAbiIsArm, which is internal to its module.
    private val deviceIsArm = Build.SUPPORTED_ABIS.first().startsWith("arm")

    // What MainApplication's Koin graph tells libpebble3 and the permission screen.
    private val reportedActive get() = KoinPlatform.getKoin().get<NetworkDenyEnforcement>().primaryLayerActive

    @Test
    fun theAppProcessStartsWithTheFilterInstalled() {
        assumeTrue("needs an ARM device", deviceIsArm)
        assertEquals(InstallResult.Installed, UdpSocketFilter.lastResult)
        assertEquals(InstallResult.AlreadyInstalled, UdpSocketFilter.install())
        val report = assertNotNull(UdpSocketFilter.selfTest())
        assertTrue(report.datagramRefused, "UDP sockets in the app process: ${report.datagram}")
        assertTrue(report.othersCreated, "other sockets in the app process: ${report.others}")
        assertTrue(reportedActive, "the app's graph does not report the installed filter")
    }

    @Test
    fun onADeviceThatIsNotArmTheAppProcessStartsUnfiltered() {
        assumeTrue("needs a device whose primary ABI is not ARM", !deviceIsArm)
        assertEquals(
            InstallResult.Unsupported(UnsupportedReason.ArchitectureMismatch, 0),
            UdpSocketFilter.lastResult,
        )
        DatagramSocket().close()
        assertFalse(reportedActive, "the app's graph reports a filter that is not installed")
    }
}
