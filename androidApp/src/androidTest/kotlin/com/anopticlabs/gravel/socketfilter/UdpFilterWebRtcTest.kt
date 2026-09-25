package com.anopticlabs.gravel.socketfilter

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

// A data-channel offer with no ICE servers, counting UDP and other candidates.
private const val PROBE = """
    window.probe = { udp: 0, other: 0, gathering: 'new', error: 'none' };
    try {
        var pc = new RTCPeerConnection();
        window.pc = pc;
        pc.onicecandidate = function (e) {
            if (!e.candidate || !e.candidate.candidate) return;
            if (/ udp /i.test(e.candidate.candidate)) window.probe.udp++; else window.probe.other++;
        };
        pc.onicegatheringstatechange = function () { window.probe.gathering = pc.iceGatheringState; };
        pc.createDataChannel('probe');
        pc.createOffer()
            .then(function (offer) { return pc.setLocalDescription(offer); })
            .catch(function (e) { window.probe.error = String(e); });
    } catch (e) {
        window.probe.error = String(e);
    }
    0;
"""

// Instrumentation runs in the app's own process, where MainApplication installed the filter; why
// that reaches the WebView's own sockets is in KNOWN_ISSUES.md, "No UDP for web content inside
// Gravel". An equivalent offer without the filter gathers candidates (DeniedSessionWebRtcTest,
// granted case).
class UdpFilterWebRtcTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rendererGone = CompletableDeferred<Unit>()

    // Bounded: a view whose renderer has gone never answers.
    private suspend fun WebView.eval(js: String): String {
        assertFalse(rendererGone.isCompleted, "the renderer exited during the offer")
        return withTimeout(5.seconds) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont -> evaluateJavascript(js) { cont.resume(it) } }
            }
        }
    }

    // Otherwise no candidate would be gathered whatever the filter does.
    private fun deviceCanGather(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val link = connectivity.getLinkProperties(connectivity.activeNetwork ?: return false) ?: return false
        return link.linkAddresses.any { !it.address.isLoopbackAddress }
    }

    @Test
    fun aWebRtcOfferInTheAppProcessGathersNoUdpCandidate() = runBlocking {
        val result = UdpSocketFilter.lastResult
        assumeTrue("needs the filter installed in this process, was $result", result == InstallResult.Installed)
        assumeTrue("needs internet access and an active network with an address", deviceCanGather())
        val loaded = CompletableDeferred<Unit>()
        val webView = withContext(Dispatchers.Main) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        loaded.complete(Unit)
                    }

                    // Platform contract: see WebViewJsRunner's override.
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        rendererGone.complete(Unit)
                        return true
                    }
                }
                loadDataWithBaseURL("https://gravel.invalid/", "<html></html>", "text/html", "utf-8", null)
            }
        }
        try {
            withTimeout(10.seconds) { loaded.await() }
            webView.eval(PROBE)
            withTimeoutOrNull(5.seconds) {
                while (webView.eval("window.probe.gathering == 'new' && window.probe.error == 'none'") == "true") delay(50)
            }
            assertEquals("\"none\"", webView.eval("window.probe.error"), webView.eval("JSON.stringify(window.probe)"))
            assertNotEquals("\"new\"", webView.eval("window.probe.gathering"), "gathering never started")
            // Far past the time an offer without the filter takes to gather its first UDP candidate.
            delay(10.seconds)
            val probe = webView.eval("JSON.stringify(window.probe)")
            assertEquals("0", webView.eval("window.probe.udp"), "UDP candidates gathered with the filter installed: $probe")
            assertFalse(rendererGone.isCompleted, "the renderer exited during the offer")
        } finally {
            withContext(Dispatchers.Main) { webView.destroy() }
        }
    }
}
