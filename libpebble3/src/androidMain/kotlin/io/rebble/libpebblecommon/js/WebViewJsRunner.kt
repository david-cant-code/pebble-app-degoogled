package io.rebble.libpebblecommon.js

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.WebViewGeolocationInterface
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.WebViewJSLocalStorageInterface
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.util.concurrent.Executor
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


class WebViewJsRunner(
    appContext: AppContext,
    private val libPebble: LibPebble,
    jsTokenUtil: JsTokenUtil,
    device: CompanionAppDevice,
    private val scope: CoroutineScope,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    urlOpenRequests: Channel<String>,
    logMessages: Channel<String>,
    remoteTimelineEmulator: RemoteTimelineEmulator,
    httpInterceptorManager: HttpInterceptorManager,
    notificationConfigFlow: NotificationConfigFlow,
    private val watchappPermissions: WatchappPermissionResolver,
): JsRunner(appInfo, lockerEntry, jsPath, device, urlOpenRequests), LibPebbleKoinComponent {
    private val context = appContext.context

    // Fork network gate, cached snapshot. Defaults to false so that during the brief
    // window between WebView creation and the first permission resolve, requests are
    // blocked rather than leaked. Updated synchronously in start() before any app code
    // runs, then kept live by a collector on the resolved grant flow. Read from the
    // WebView client thread (shouldInterceptRequest) and the JS bridge thread, hence
    // @Volatile.
    @Volatile
    private var networkAllowed: Boolean = false

    // Read by startup.js (via the _Pebble bridge) to install the JS-shim layer.
    fun isNetworkAllowedForJs(): Boolean = networkAllowed
    companion object {
        const val API_NAMESPACE = "Pebble"
        const val PRIVATE_API_NAMESPACE = "_$API_NAMESPACE"
        const val STARTUP_URL = "file:///android_asset/webview_startup.html"
        private val logger = Logger.withTag(WebViewJsRunner::class.simpleName!!)
    }

    private var webView: WebView? = null

    // The live-toggle collector launched in start(); stop() cancels it before any
    // proxy teardown so the two can never interleave on the process-global override.
    private var networkPermissionCollector: Job? = null
    private var restoreCompleted: Boolean = false
    private val initializedLock = Object()
    private val publicJsInterface = WebViewPKJSInterface(this, device, context, libPebble, jsTokenUtil)
    private val privateJsInterface = WebViewPrivatePKJSInterface(this, device, scope, _outgoingAppMessages, logMessages, jsTokenUtil, remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow, watchappPermissions)
    private val localStorageInterface = WebViewJSLocalStorageInterface(appInfo.uuid, appContext) {
        runBlocking(Dispatchers.Main) {
            webView?.evaluateJavascript(
                it,
                null
            )
        }
    }
    private val geolocationInterface = WebViewGeolocationInterface(scope, this)
    private val interfaces = setOf(
            Pair(API_NAMESPACE, publicJsInterface),
            Pair(PRIVATE_API_NAMESPACE, privateJsInterface),
            Pair("_localStorage", localStorageInterface),
            Pair("_PebbleGeo", geolocationInterface)
    )

    private val webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            logger.d { "Page finished loading: $url" }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            logger.e {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    "Error loading page: ${error?.errorCode} ${error?.description}"
                } else {
                    "Error loading page: ${error?.toString()}"
                }
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            super.onReceivedSslError(view, handler, error)
            logger.e { "SSL error loading page: ${error?.primaryError}" }
            handler?.cancel()
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url
            if (isForbidden(url)) {
                return blockedResponse("Forbidden")
            }
            // Fork network gate (layer 1, deterministic for http/https). When the app's
            // Network permission is denied, refuse every non-file request so no XHR,
            // fetch, page subresource or navigation reaches the network. WebSocket
            // handshakes do NOT pass through this callback (a documented WebView
            // limitation); the proxy override layer is what covers those.
            if (!networkAllowed && url != null && url.scheme?.uppercase() != "FILE") {
                logger.d { "Network denied; blocking ${url.scheme} request to ${url.host}" }
                return blockedResponse("Network access denied for this app")
            }
            return super.shouldInterceptRequest(view, request)
        }
    }

    private fun blockedResponse(reason: String): WebResourceResponse =
        object : WebResourceResponse("text/plain", "utf-8", null) {
            override fun getStatusCode(): Int = 403
            override fun getReasonPhrase(): String = reason
        }

    private fun isForbidden(url: Uri?): Boolean {
        return if (url == null) {
            logger.w { "Blocking null URL" }
            true
        } else if (url.scheme?.uppercase() != "FILE") {
            false
        } else if (url.path?.uppercase() == jsPath.toString().uppercase()) {
            false
        } else {
            logger.w { "Blocking access to file: ${url.path}" }
            true
        }
    }

    private val chromeClient = object : WebChromeClient() {

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            return false
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            return false
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            return false
        }

        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            return false
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            return false
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            //Stub
        }

        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
            return false
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            logger.d { "Permission request for: ${request?.resources?.joinToString()}" }
            request?.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            callback?.invoke(origin, false, false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun init() = withContext(Dispatchers.Main) {
        if (libPebble.config.value.watchConfig.pkjsInspectable) {
            WebView.setWebContentsDebuggingEnabled(true) // Sadly sets globally for this process
        }
        webView = WebView(context).also {
            it.setWillNotDraw(true)
            val settings = it.settings
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false

            //TODO: use WebViewAssetLoader instead
            settings.allowUniversalAccessFromFileURLs = true
            settings.allowFileAccessFromFileURLs = true

            settings.setGeolocationEnabled(true)
            settings.databaseEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            it.clearCache(true)

            interfaces.forEach { (namespace, jsInterface) ->
                it.addJavascriptInterface(jsInterface, namespace)
            }
            it.webViewClient = webViewClient
            it.webChromeClient = chromeClient
        }
    }

    private fun restoreLocalStorage() {
        runBlocking(Dispatchers.Main) {
            webView?.evaluateJavascript("""
                (function() {
                    window.localStorage.clear();
                    const localStorageData = JSON.parse(window._localStorage.restoreState());
                    for (const [key, value] of Object.entries(localStorageData)) {
                        window.localStorage.setItem(key, value);
                    }
                    const originalSetItem = window.localStorage.setItem;
                    const originalRemoveItem = window.localStorage.removeItem;
                    const originalClear = window.localStorage.clear;
                    
                    ${/* Shim to keep _localStorage in sync with localStorage realtime as best we can (can't handle property accessors) */ ""}
                    window.localStorage.setItem = function(key, value) {
                        originalSetItem.call(this, key, value);
                        window._localStorage.setItem(key, value);
                    };
                    window.localStorage.removeItem = function(key) {
                        originalRemoveItem.call(this, key);
                        window._localStorage.removeItem(key);
                    };
                    window.localStorage.clear = function() {
                        originalClear.call(this);
                        window._localStorage.clear();
                    };
                })();
                window.__localStorageShimmed = true;
            """.trimIndent()
            ) {
                restoreCompleted = true
                logger.d { "localStorage shimmed" }
            }
        }
    }


    override suspend fun start() {
        synchronized(initializedLock) {
            check(webView == null) { "WebviewJsRunner already started" }
        }
        restoreCompleted = false
        try {
            init()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(initializedLock) {
                webView = null
            }
            throw e
        }
        check(webView != null) { "WebView not initialized" }
        logger.d { "WebView initialized" }

        // Resolve the app's Network grant and put the enforcement layers in place
        // BEFORE any app page/script loads, so there is no window in which a denied
        // app can reach the network. The initial value gates layers 1 (intercept) and
        // 2 (JS shim); the proxy (layer 3) is applied and awaited here too.
        val uuid = Uuid.parse(appInfo.uuid)
        networkAllowed = watchappPermissions.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network)
        applyNetworkProxy(networkAllowed)
        // Track live toggles: a change in the resolved grant (per-app override or the
        // global default) re-caches the value and re-applies/clears the proxy while the
        // app keeps running. The job is kept so stop() can cancel it FIRST, before it
        // touches the proxy itself: the runner scope outlives stop()'s suspension
        // points (PKJSApp cancels it only after stop() returns), so an emission landing
        // mid-teardown would otherwise re-install the process-wide black-hole after
        // stop() cleared it, with nothing left alive to ever clear it again.
        networkPermissionCollector = scope.launch {
            watchappPermissions.watchappPermissionGranted(uuid, LockerAppPermissionType.Network)
                .collect { allowed ->
                    if (allowed != networkAllowed) {
                        logger.d { "Network grant for $uuid changed -> $allowed" }
                    }
                    networkAllowed = allowed
                    applyNetworkProxy(allowed)
                }
        }

        loadApp(jsPath.toString())
    }

    /**
     * Layer 3 of the network gate: a process-wide WebView proxy override that
     * black-holes all egress (every scheme, including ws/wss that shouldInterceptRequest
     * cannot see) when the running app's network is denied, and is cleared when allowed.
     *
     * Process-global is inherent to the ProxyController API, but only one PKJS WebView
     * runs at a time and the developer config page is gated for network-denied apps, so
     * nothing legitimate needs the network while the black-hole is active. Requires the
     * PROXY_OVERRIDE WebView feature; when unsupported, layers 1 and 2 still apply and
     * only the WebSocket-deny corner degrades to best-effort (recorded in KNOWN_ISSUES).
     */
    private suspend fun applyNetworkProxy(allowed: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (!allowed) {
                logger.w { "PROXY_OVERRIDE unsupported; WebSocket deny is best-effort for this app" }
            }
            return
        }
        val controller = ProxyController.getInstance()
        val executor = Executor { it.run() }
        suspendCancellableCoroutine { cont ->
            if (allowed) {
                controller.clearProxyOverride(executor) { if (cont.isActive) cont.resume(Unit) }
            } else {
                // Route everything to an unroutable address (RFC 5737 TEST-NET-1), so
                // every connection attempt fails fast. removeImplicitRules() matters:
                // without it Chromium exempts localhost and link-local destinations
                // from any proxy override, which would leave a denied app's WebSocket
                // reachable to other apps' local socket servers and to link-local
                // hosts on the same network segment. Those destinations are egress
                // too; the black-hole has to cover them for this layer to be the
                // deterministic WebSocket cover it claims to be.
                val config = ProxyConfig.Builder()
                    .addProxyRule("192.0.2.1:1")
                    .removeImplicitRules()
                    .build()
                controller.setProxyOverride(config, executor) { if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    private suspend fun persistLocalStorage() {
        suspendCancellableCoroutine { cont ->
            webView?.evaluateJavascript("""
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
                    """.trimIndent()
            ) {
                cont.resume(Unit)
            }
        }
    }

    override suspend fun stop() {
        //TODO: Close config screens
        _readyState.value = false
        // Run teardown as NonCancellable. stop() is frequently invoked from an already-cancelled
        // connection scope — e.g. on watch disconnect.
        withContext(NonCancellable + Dispatchers.Main) {
            try {
                // Stop the live-toggle collector before anything else so a grant-flow
                // emission (the combined flow re-emits on any permission-table or
                // config write) cannot re-install the process-global black-hole while
                // teardown is mid-flight; the clear at the end of this block must be
                // the last word on the override. cancelAndJoin, not just cancel: the
                // collector may be suspended inside a proxy call of its own, and it
                // must have fully wound down before the teardown sequence proceeds.
                networkPermissionCollector?.cancelAndJoin()
                networkPermissionCollector = null
                // Save final state of localStorage to our scoped storage, to catch any
                // property-accessor changes (not caught by our shim).
                // Skip if restoreLocalStorage() never completed: window.localStorage is
                // still empty and persisting it would clear the user's stored settings
                // (saveState() does a clear() first). MOB-6881.
                if (restoreCompleted) {
                    persistLocalStorage()
                } else {
                    logger.d { "Skipping persistLocalStorage: restore did not complete" }
                }
                interfaces.forEach { (namespace, _) ->
                    webView?.removeJavascriptInterface(namespace)
                }
                webView?.loadUrl("about:blank")
                webView?.stopLoading()
                webView?.clearHistory()
                webView?.removeAllViews()
                webView?.clearCache(true)
            } catch (e: Exception) {
                logger.e(e) { "Error during WebView teardown; destroying anyway" }
            } finally {
                // destroy() must always run, even if the pre-destroy teardown fails
                webView?.destroy()
                // Clear any black-hole proxy this app set, so the process-global
                // override never outlives the session and starves a later WebView
                // (config page or the next app). Deliberately the LAST teardown step,
                // after destroy(): the page's JS stays live through the earlier steps
                // (persistLocalStorage even evaluates into it), and clearing the
                // proxy any sooner would hand a denied app's still-running scripts a
                // WebSocket-egress window on every stop. No-op if this app was
                // network-allowed.
                runCatching { applyNetworkProxy(allowed = true) }
                    .onFailure { logger.w(it) { "Failed to clear network proxy on stop" } }
            }
        }
        synchronized(initializedLock) {
            webView = null
        }
    }

    private suspend fun loadApp(url: String) {
        check(webView != null) { "WebView not initialized" }
        withContext(Dispatchers.Main) {
            webView?.loadUrl(
                STARTUP_URL.toUri().buildUpon()
                    .appendQueryParameter("params", "{\"loadUrl\": \"$url\"}")
                    .build()
                    .toString()
            )
        }
    }

    override suspend fun loadAppJs(jsUrl: String) {
        webView?.let { webView ->
            restoreLocalStorage()

            if (jsUrl.isBlank() || !jsUrl.endsWith(".js")) {
                logger.e { "loadUrl passed to loadAppJs empty or invalid" }
                return
            }

            val urlAsUri = Uri.fromFile(File(jsUrl)).toString()

            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                        """
                            (() => {
                                const signalLoaded = () => {
                                    _Pebble.signalAppScriptLoadedByBootstrap();
                                }
                                const head = document.getElementsByTagName("head")[0];
                                const script = document.createElement("script");
                                script.type = "text/javascript";
                                script.onreadystatechange = signalLoaded;
                                script.onload = signalLoaded;
                                script.charset = "utf-8";
                                script.src = ${Json.encodeToString(urlAsUri)};
                                head.appendChild(script);
                            })();
                            """.trimIndent()
                ) { value -> logger.d { "added app script tag" } }
                webView.evaluateJavascript("document.title = ${Json.encodeToString("PKJS: ${appInfo.longName}")};", null)
            }
        } ?: error("WebView not initialized")
    }

    override suspend fun signalInterceptResponse(callbackId: String, result: InterceptResponse) {
        val jsonString = buildJsonObject {
            put("callbackId", callbackId)
            put("response", result.result)
            put("status", result.status)
        }.toString()
        withContext(Dispatchers.Main) {
            // No Json.encodeToString here, we want the raw object {} in the JS call
            webView?.evaluateJavascript("window.signalInterceptResponse($jsonString)", null)
        }
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to token, "callId" to callId))
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalTimelineTokenSuccess(${Json.encodeToString(tokenJson)})", null)
        }
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to null, "callId" to callId))
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalTimelineTokenFailure(${Json.encodeToString(tokenJson)})", null)
        }
    }

    override suspend fun signalReady() {
        val readyDeviceIds = listOf(device.identifier.asString)
        val readyJson = Json.encodeToString(readyDeviceIds)
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalReady(${readyJson})", null)
        }
        _readyState.value = true
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        readyState.first { it }
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalNewAppMessageData(${data?.let { Json.encodeToString(data) } ?: "null"})", null)
        }
        return true
    }

    override suspend fun signalShowConfiguration() {
        readyState.first { it }
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalShowConfiguration()", null)
        }
    }

    override suspend fun signalWebviewClosed(data: String?) {
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalWebviewClosedEvent(${Json.encodeToString(data)})", null)
        }
    }

    override suspend fun eval(js: String) {
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript(js, null) ?: run {
                logger.e { "WebView not initialized, cannot evaluate JS" }
            }
        }
    }

    override suspend fun evalWithResult(js: String): Any? {
        return withContext(Dispatchers.Main) {
            return@withContext suspendCancellableCoroutine { cont ->
                webView?.evaluateJavascript(js) { result ->
                    cont.resume(result)
                } ?: cont.resumeWithException(IllegalStateException("WebView not initialized"))
            }
        }
    }

    override fun debugForceGC() {
        // No-op on Android
    }
}
