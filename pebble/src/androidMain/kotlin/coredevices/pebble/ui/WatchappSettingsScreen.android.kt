package coredevices.pebble.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.anopticlabs.gravel.ui.RendererGoneAwareWebViewClient
import com.multiplatform.webview.web.AccompanistWebChromeClient
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.PlatformWebViewParams
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory
import coreapp.util.generated.resources.Res
import coreapp.util.generated.resources.webview_settings_page_stopped
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.WebViewJSLocalStorageInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path
import kotlin.uuid.Uuid

internal actual fun webViewFactory(
    params: WebViewFactoryParam,
    uuid: Uuid
): NativeWebView = defaultWebViewFactory(params).apply {
    // Don't store the webview state (which includes localstorage) in bundle - can be too large
    isSaveEnabled = false
    val localStorageInterface = WebViewJSLocalStorageInterface("$uuid-config", AppContext(context)) {
        runBlocking(Dispatchers.Main) {
            evaluateJavascript(
                it,
                null
            )
        }
    }
    addJavascriptInterface(localStorageInterface, "_localStorage")
    settings.domStorageEnabled = true
    settings.databasePath = Path(context.filesDir.path, "watchapp_settings/$uuid").toString()
}

internal actual suspend fun restoreLocalStorage(webView: NativeWebView) {
    withContext(Dispatchers.Main) {
        webView.evaluateJavascript("""
            (function() {
                window.localStorage.clear();
                const localStorageData = JSON.parse(window._localStorage.restoreState());
                for (const [key, value] of Object.entries(localStorageData)) {
                    window.localStorage.setItem(key, value);
                }
            })();
                """.trimIndent(), null
        )
    }
}

@Composable
internal actual fun rememberPlatformWebViewParams(onRendererGone: () -> Unit): PlatformWebViewParams? {
    val context = LocalContext.current
    val stoppedMessage = stringResource(Res.string.webview_settings_page_stopped)
    val currentOnRendererGone by rememberUpdatedState(onRendererGone)
    val goneView = remember { AtomicReference<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            // The view left composition with this screen (RendererGoneAwareWebViewClient has
            // the constraint). The destroy is posted, so it runs after this disposal pass.
            goneView.getAndSet(null)?.let { view ->
                Handler(Looper.getMainLooper()).post { view.destroy() }
            }
        }
    }
    val pending = remember { AtomicReference<ValueCallback<Array<Uri>>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        pending.getAndSet(null)?.onReceiveValue(uris)
    }
    return remember(launcher) {
        PlatformWebViewParams(
            client = RendererGoneAwareWebViewClient { view, _ ->
                goneView.set(view)
                Toast.makeText(context, stoppedMessage, Toast.LENGTH_LONG).show()
                currentOnRendererGone()
            },
            chromeClient = object : AccompanistWebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    val intent = fileChooserParams?.createIntent() ?: return false
                    return try {
                        launcher.launch(intent)
                        // Returning false leaves the callback to the webview, so only take
                        // ownership of it once the chooser is actually up.
                        pending.getAndSet(filePathCallback)?.onReceiveValue(null)
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    }
                }
            }
        )
    }
}

internal actual fun persistLocalStorage(webView: NativeWebView) {
    runBlocking(Dispatchers.Main) {
        webView.evaluateJavascript("""
            (function() {
                const data = {};
                for (let i = 0; i < window.localStorage.length; i++) {
                    const key = window.localStorage.key(i);
                    const value = window.localStorage.getItem(key);
                    data[key] = value;
                }
                window.localStorage.clear();
                window._localStorage.saveState(JSON.stringify(data));
            })();
                """.trimIndent(), null
        )
    }
}