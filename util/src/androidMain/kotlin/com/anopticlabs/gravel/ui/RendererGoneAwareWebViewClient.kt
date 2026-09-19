package com.anopticlabs.gravel.ui

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import co.touchlab.kermit.Logger
import com.multiplatform.webview.web.AccompanistWebViewClient

/**
 * Keeps the app running when the WebView's renderer process exits. The view cannot be used
 * afterwards: [onRendererGone] has to take the WebView composable out of composition, and the
 * site has to destroy the view once it has left: the library's release path
 * (compose-webview-multiplatform 2.0.3, AccompanistWebView) only calls the caller's onDispose.
 */
class RendererGoneAwareWebViewClient(
    private val onRendererGone: (view: WebView, didCrash: Boolean) -> Unit,
) : AccompanistWebViewClient() {
    // Platform contract: see WebViewJsRunner's override.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        logger.w { "WebView renderer gone (didCrash=${detail.didCrash()}) at ${view.url}" }
        onRendererGone(view, detail.didCrash())
        return true
    }

    private companion object {
        val logger = Logger.withTag("RendererGoneAwareWebViewClient")
    }
}
