package com.anopticlabs.gravel.socketfilter

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.pkjs.HEADER_MIN_WEBVIEW_MAJOR
import com.anopticlabs.gravel.pkjs.NetworkDenyEnforcement
import com.anopticlabs.gravel.pkjs.deniedPkjsSwitchApplies
import com.anopticlabs.gravel.pkjs.udpFilterEnforcement
import com.anopticlabs.gravel.pkjs.webViewMajorVersion
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.mp.KoinPlatform
import java.io.File
import java.net.DatagramSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Instrumentation runs in the app's own process, after MainApplication.attachBaseContext. Whether
// the resolver check answers correctly on a device is UdpSocketFilterTest's; this covers the app
// process running the install and acting on its result.
class UdpFilterInstalledTest {
    // The same rule as UdpSocketFilter.primaryAbiIsArm, which is internal to its module.
    private val deviceIsArm = Build.SUPPORTED_ABIS.first().startsWith("arm")

    // What MainApplication's Koin graph tells libpebble3 and the permission screen.
    private val reported get() = KoinPlatform.getKoin().get<NetworkDenyEnforcement>()
    private val reportedActive get() = reported.primaryLayerActive

    // INSTALLED_MARKER in UdpFilterEnforcement.kt.
    private val installMarker
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "udp-filter-installed")

    @Test
    fun theAppProcessActsOnItsInstallResult() {
        assumeTrue("needs an ARM device", deviceIsArm)
        val result = assertNotNull(UdpSocketFilter.lastResult, "the app process ran no filter install")
        val report = assertNotNull(UdpSocketFilter.selfTest())
        if (result == InstallResult.Installed) {
            // The app process starts afresh for instrumentation; 2 s covers file-time granularity.
            val processStart = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            assertTrue(installMarker.lastModified() >= processStart - 2_000, "this process start recorded no install")
            assertEquals(InstallResult.AlreadyInstalled, UdpSocketFilter.install())
            assertTrue(report.datagramRefused, "UDP sockets in the app process: ${report.datagram}")
            assertTrue(report.othersCreated, "other sockets in the app process: ${report.others}")
            assertTrue(reportedActive, "the app's graph does not report the installed filter")
            assertFalse(reported.deniedPkjsSwitchApplies, "the switch applies with the filter installed")
            return
        }
        assertEquals(listOf(0, 0, 0, 0), report.datagram, "UDP sockets in the app process after $result")
        DatagramSocket().close()
        assertFalse(reportedActive, "the app's graph reports a filter that is not installed")
        checkTheSwitchWithoutTheFilter(result)
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
        checkTheSwitchWithoutTheFilter(UdpSocketFilter.lastResult)
    }

    // Below HEADER_MIN_WEBVIEW_MAJOR the switch must not apply, whatever the record says.
    private fun checkTheSwitchWithoutTheFilter(result: InstallResult?) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val versionName = WebView.getCurrentWebViewPackage()?.versionName
        val webView = webViewMajorVersion()
        assertEquals(versionName?.substringBefore('.'), webView?.toString(), "the WebView major read from $versionName")
        val applies = reported.deniedPkjsSwitchApplies
        val state = "after $result, record ${installMarker.exists()}, WebView $versionName"
        assertEquals(udpFilterEnforcement(result, context.noBackupFilesDir) { webView }.deniedPkjsSwitchApplies, applies, state)
        if ((webView ?: 0) < HEADER_MIN_WEBVIEW_MAJOR) assertFalse(applies, "the switch applies $state")
    }
}
