package coredevices.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.anopticlabs.gravel.ui.RendererGoneAwareWebViewClient
import com.multiplatform.webview.request.RequestInterceptor
import com.multiplatform.webview.request.WebRequest
import com.multiplatform.webview.request.WebRequestInterceptResult
import com.multiplatform.webview.web.LoadingState
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.PlatformWebViewParams
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.WebViewNavigator
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import coreapp.util.generated.resources.Res
import coreapp.util.generated.resources.webview_page_stopped
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun PebbleWebview(
    url: String,
    interceptor: PebbleWebviewUrlInterceptor,
    modifier: Modifier,
    onPageFinishedJavaScript: String?,
    onPageError: ((message: String) -> Unit)?,
) {
    val state = rememberWebViewState(url)

    DisposableEffect(Unit) {
        state.webSettings.apply {
            isJavaScriptEnabled = true
            androidWebSettings.apply {
                domStorageEnabled = true
                // Let WebView read the Activity's theme (set by MainActivity.setTheme based on the
                // in-app App Theme) for prefers-color-scheme, instead of falling back to the OS
                // Configuration.uiMode. Requires WebView component ~102+; older components fall
                // back to OS-driven dark mode.
                isAlgorithmicDarkeningAllowed = true
            }
        }
        onDispose { }
    }

    val int = object : RequestInterceptor {
        override fun onInterceptUrlRequest(
            request: WebRequest,
            navigator: WebViewNavigator,
        ): WebRequestInterceptResult {
            val pebbleNavigator = object : PebbleWebviewNavigator {
                override fun loadUrl(url: String) {
                    navigator.loadUrl(url)
                }

                override fun goBack(): Boolean {
                    if (navigator.canGoBack) {
                        navigator.navigateBack()
                        return true
                    } else {
                        return false
                    }
                }

                override fun reload() {
                    navigator.reload()
                }
            }
            interceptor.navigator = pebbleNavigator
            return if (interceptor.onIntercept(request.url, pebbleNavigator)) {
                WebRequestInterceptResult.Allow
            } else {
                WebRequestInterceptResult.Reject
            }
        }
    }
    val navigator = rememberWebViewNavigator(requestInterceptor = int)
    
    // Execute JavaScript when page finishes loading
    LaunchedEffect(state.loadingState, onPageFinishedJavaScript) {
        if (state.loadingState is LoadingState.Finished && onPageFinishedJavaScript != null) {
            navigator.evaluateJavaScript(onPageFinishedJavaScript)
        }
    }

    // Surface main-frame load failures (cleared automatically when a new request starts).
    val mainFrameError = state.errorsForCurrentRequest.firstOrNull { it.isFromMainFrame }
    LaunchedEffect(mainFrameError) {
        if (mainFrameError != null) {
            onPageError?.invoke(mainFrameError.description)
        }
    }
    
    var rendererGone by remember { mutableStateOf(false) }
    val stoppedMessage = stringResource(Res.string.webview_page_stopped)
    val currentOnPageError by rememberUpdatedState(onPageError)
    val platformParams = remember {
        PlatformWebViewParams(
            client = RendererGoneAwareWebViewClient { _, _ ->
                rendererGone = true
                currentOnPageError?.invoke(stoppedMessage)
            },
        )
    }
    if (rendererGone) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(stoppedMessage)
        }
    } else {
        WebView(
            state = state,
            navigator = navigator,
            modifier = modifier,
            platformWebViewParams = platformParams,
            onDispose = { view: NativeWebView -> if (rendererGone) view.destroy() },
        )
    }
}