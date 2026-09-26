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
 * WebRTC in a Network-denied session, in this test process, which installs no UDP filter: an offer
 * in the frame gathers no candidate, and a renderer exit counts as refused (KNOWN_ISSUES.md, "The
 * WebRTC response-header layer needs WebView 152 or newer").
 */
@MediumTest
class DeniedSessionWebRtcTest : PKJSRunnerTests(::createJsRunner, networkGrantedByDefault = false) {
    // Both cases need inet sockets (the test manifest's INTERNET).
    @Before
    fun requireAHeaderVersionWebViewAndInternetAccess() {
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
