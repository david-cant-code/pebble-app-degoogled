package com.anopticlabs.gravel.pkjs

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import io.rebble.libpebblecommon.js.PKJSRunnerTests
import io.rebble.libpebblecommon.js.WebViewJsRunner
import io.rebble.libpebblecommon.js.createJsRunner
import io.rebble.libpebblecommon.js.stopWithinTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

// A data-channel offer with no ICE servers, counting the candidates it gathers. A refused
// construction leaves the count at 0.
private const val PROBE = """
    window.rtcCandidates = 0;
    try {
        window.rtcProbe = new RTCPeerConnection();
        window.rtcProbe.onicecandidate = function (e) {
            if (e.candidate && e.candidate.candidate) window.rtcCandidates++;
        };
        window.rtcProbe.createDataChannel('probe');
        window.rtcProbe.createOffer().then(function (offer) { return window.rtcProbe.setLocalDescription(offer); });
    } catch (e) {}
    window.rtcCandidates;
"""

/**
 * WebRTC in a Network-denied session with the Connection-Allowlist header as the only layer
 * against UDP: this test process installs no UDP filter. Under the header the browser does not
 * register the frame's P2P socket interface, and a frame that asks for it is reported as sending
 * a bad message (Chromium M153, refs/branch-heads/8010, content/browser/browser_interface_binders.cc,
 * should_ban_p2p_for_connection_allowlist; content/browser/renderer_host/render_frame_host_impl.cc,
 * RenderFrameHostImpl::ReportNoBinderForInterface), so a renderer exit counts as refused.
 */
@MediumTest
class DeniedSessionWebRtcTest : PKJSRunnerTests(::createJsRunner, networkGrantedByDefault = false) {
    // Without internet access the platform refuses the sockets itself, and both cases would say
    // nothing about the header.
    @Before
    fun requireAWebViewThatHonorsTheHeaderAndInternetAccess() {
        val major = webViewMajorVersion()
        assumeTrue("WebView $major predates the header", (major ?: 0) >= HEADER_MIN_WEBVIEW_MAJOR)
        val context = InstrumentationRegistry.getInstrumentation().context
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.INTERNET),
            "grant the test package internet access",
        )
    }

    private suspend fun startedRunner(networkGranted: Boolean): WebViewJsRunner {
        val runner = makeRunner("", Uuid.random(), networkGranted = networkGranted) as WebViewJsRunner
        runner.start()
        withTimeout(10.seconds) { runner.readyState.first { it } }
        return runner
    }

    // Null once the renderer has gone.
    private suspend fun WebViewJsRunner.evalUnlessGone(js: String): String? =
        try {
            if (rendererGone) null else evalWithResult(js) as String
        } catch (e: IllegalStateException) {
            if (rendererGone) null else throw e
        }

    @Test
    fun aDeniedSessionGathersNoCandidate() = runBlocking {
        val runner = startedRunner(networkGranted = false)
        assertTrue(runner.denyConstruction)
        var seen = runner.evalUnlessGone(PROBE)
        val end = TimeSource.Monotonic.markNow() + 5.seconds
        while (seen != null && end.hasNotPassedNow()) {
            assertEquals("0", seen, "WebRTC candidates in a Network-denied session")
            delay(100)
            seen = runner.evalUnlessGone("window.rtcCandidates")
        }
        runner.stopWithinTimeout()
    }

    // The control: without it a WebView that gathers nothing at all would pass the denied case.
    @Test
    fun aGrantedSessionGathersACandidate() = runBlocking {
        val runner = startedRunner(networkGranted = true)
        runner.evalWithResult(PROBE)
        withTimeout(10.seconds) {
            while ((runner.evalWithResult("window.rtcCandidates") as String).toInt() == 0) delay(100)
        }
        runner.stop()
    }
}
