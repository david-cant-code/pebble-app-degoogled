package com.anopticlabs.gravel.ui

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class RendererGoneAwareWebViewClientTest {
    // Ends the renderer the way PKJSRunnerTestsAndroid does. A false return from the callback
    // ends the app process (WebViewJsRunner's override has the contract), so what shows the
    // true is the test completing: the main thread still serves the destroy() hop afterwards.
    // No page is loaded first: the library client's page callbacks need state that only its
    // composable sets.
    @Test
    fun rendererExitReachesTheCallbackAndTheProcessSurvives() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val goneView = CompletableDeferred<WebView>()
        val webView = withContext(Dispatchers.Main) {
            WebView(context).apply {
                webViewClient = RendererGoneAwareWebViewClient { view, _ -> goneView.complete(view) }
                loadUrl("chrome://crash")
            }
        }
        assertSame(webView, withTimeout(10.seconds) { goneView.await() })
        withContext(Dispatchers.Main) { webView.destroy() }
    }
}
