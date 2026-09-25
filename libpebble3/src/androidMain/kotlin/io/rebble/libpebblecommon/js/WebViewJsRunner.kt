package io.rebble.libpebblecommon.js

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.pkjs.DENY_HOST_PAGE_URL
import com.anopticlabs.gravel.pkjs.NetworkDenyEnforcement
import com.anopticlabs.gravel.pkjs.PkjsRequestPlan
import com.anopticlabs.gravel.pkjs.planPkjsRequest
import com.anopticlabs.gravel.pkjs.shouldRunPkjs
import com.anopticlabs.gravel.pkjs.toWebResourceResponse
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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds


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
    private val networkDenyEnforcement: NetworkDenyEnforcement,
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

    // Fixed in start() from the same grant read that seeds networkAllowed, and never changed:
    // a denied session runs the watchapp's script in a sandboxed frame under the host page
    // (DESIGN_NOTES.md, watchapp network gate). A grant change the lifecycle sees restarts the
    // session, and endGrantedSessionOnDenial ends one built as granted once its grant is denied.
    @Volatile
    internal var denyConstruction: Boolean = false
        private set

    // Read by startup.js (via the _Pebble bridge) to install the JS-shim layer.
    fun isNetworkAllowedForJs(): Boolean = networkAllowed
    companion object {
        const val API_NAMESPACE = "Pebble"
        const val PRIVATE_API_NAMESPACE = "_$API_NAMESPACE"
        const val STARTUP_URL = "file:///android_asset/webview_startup.html"
        private val PAGE_LOAD_TIMEOUT = 15.seconds
        private val FRAME_EVAL_TIMEOUT = 10.seconds
        private val PERSIST_TIMEOUT = 3.seconds
        private const val DENY_VIEW_WIDTH_PX = 320
        private const val DENY_VIEW_HEIGHT_PX = 240
        private const val DENY_HOST_PAGE_ASSET = "webview_deny_host.html"
        private const val DENY_FRAME_BOOTSTRAP_ASSET = "webview_deny_frame_bootstrap.js"
        private const val STARTUP_SCRIPT_ASSET = "startup.js"
        private const val LOAD_APP_SCRIPT_IN_FRAME = """
            (function () {
                var script = document.createElement("script");
                script.textContent = _Pebble.readAppScript();
                document.head.appendChild(script);
                _Pebble.signalAppScriptLoadedByBootstrap();
            })();
        """
        private val logger = Logger.withTag(WebViewJsRunner::class.simpleName!!)
    }

    private var webView: WebView? = null

    // Set once the renderer process has exited. The session then stays dead until the
    // lifecycle stops it; it does not restart itself, so a script that ends its renderer
    // on every start cannot loop.
    @Volatile
    internal var rendererGone = false
        private set

    // Document-commit guard. Set once the frame of a deny session has loaded a second
    // document; the session is then dead the same way as after a renderer exit.
    @Volatile
    internal var frameRecommitted = false
        private set
    private val frameDocumentCounts = ConcurrentHashMap<FrameDocumentSignal, Int>()

    @Volatile
    internal var refusedNavigationCount = 0
        private set

    private val frameEvalResults = ConcurrentHashMap<String, CompletableDeferred<String>>()

    // Main thread only. The persist wait, parked so a renderer exit can release it.
    private var persistWait: CancellableContinuation<Unit>? = null

    // Main thread only. Set once endGrantedSessionOnDenial starts, while its save still holds the view.
    private var ending = false

    // The live-toggle collector launched in start(), kept so stop() can cancel it (see there).
    private var networkPermissionCollector: Job? = null
    private val pageLoaded = CompletableDeferred<Unit>()
    private var restoreCompleted: Boolean = false
    private val initializedLock = Object()
    private val publicJsInterface = WebViewPKJSInterface(this, device, context, libPebble, jsTokenUtil)
    private val privateJsInterface = WebViewPrivatePKJSInterface(this, device, scope, _outgoingAppMessages, logMessages, jsTokenUtil, remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow, watchappPermissions)
    private val localStorageInterface = WebViewJSLocalStorageInterface(appInfo.uuid, appContext) {
        // The frame's localStorage stand-in computes its own length.
        if (denyConstruction) return@WebViewJSLocalStorageInterface
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
            return refusePageNavigation(request)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            logger.d { "Page finished loading: $url" }
            pageLoaded.complete(Unit)
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
            // Fork network gate (layer 1, deterministic for http/https). When the app's
            // Network permission is denied, refuse every request so no XHR, fetch, page
            // subresource or navigation reaches the network. WebSocket handshakes do NOT
            // pass through this callback (a documented WebView limitation); the proxy
            // override layer is what covers those.
            val plan = planPkjsRequest(
                denyConstruction = denyConstruction,
                networkAllowed = networkAllowed,
                scheme = url?.scheme,
                host = url?.host,
                path = url?.path,
                isForMainFrame = request?.isForMainFrame == true,
                appJsPath = jsPath.toString(),
            )
            if (plan is PkjsRequestPlan.Block) {
                logger.d { "Blocking ${url?.scheme} request to ${url?.host ?: url?.path}: ${plan.reason}" }
            }
            return plan.toWebResourceResponse(denyConstruction) {
                context.assets.open(DENY_HOST_PAGE_ASSET)
            } ?: super.shouldInterceptRequest(view, request)
        }

        // Returning false ends the app process, and the view cannot be used again
        // (android16-release, WebViewClient.java, onRenderProcessGone).
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            logger.w {
                "Renderer gone for ${appInfo.longName} (${appInfo.uuid}): didCrash=${detail.didCrash()} " +
                        "networkAllowed=$networkAllowed provider=${webViewProvider()}"
            }
            rendererGone = true
            endSession(view)
            return true
        }
    }

    private fun releasePersistWait() {
        persistWait?.let {
            persistWait = null
            if (it.isActive) it.resume(Unit)
        }
    }

    // Main thread only. The dead state: not ready, no WebView, until the lifecycle stops the session.
    private fun endSession(view: WebView) {
        _readyState.value = false
        geolocationInterface.endWatches()
        releasePersistWait()
        view.destroy()
        synchronized(initializedLock) {
            webView = null
        }
    }

    // Navigation lock (first holder; the document-commit guard is the deterministic one).
    // The watchapp frame must keep the document it was created with, which the network-deny
    // frame construction relies on (DESIGN_NOTES.md, watchapp network gate). Returning true
    // aborts a navigation the app is consulted for; WebView does not consult the app for every
    // subframe navigation (Chromium M153, aw_content_browser_client.cc,
    // AwContentBrowserClient::ShouldOverrideUrlLoading), which is why the commit guard exists.
    // Do not return false for any request, and do not drop this override in an upstream sync.
    private fun refusePageNavigation(request: WebResourceRequest?): Boolean {
        refusedNavigationCount++
        logger.d {
            "Refused navigation to ${request?.url?.scheme}://${request?.url?.host} " +
                    "mainFrame=${request?.isForMainFrame}"
        }
        return true
    }

    // Document-commit guard (the deterministic holder of the navigation lock's requirement).
    // Each signal is expected once per session: the frame's bootstrap runs once, and the host
    // page sees one load event on the frame. A second of either means the frame loaded another
    // document, and the session ends. Called on the JavaScript bridge thread; false tells the
    // bootstrap to load nothing.
    internal fun onFrameDocument(signal: FrameDocumentSignal): Boolean {
        if (!denyConstruction) return false
        val count = frameDocumentCounts.merge(signal, 1, Int::plus) ?: 1
        if (count == 1 && !frameRecommitted) return true
        logger.w { "Frame of ${appInfo.longName} (${appInfo.uuid}) loaded a second document ($signal); ending the session" }
        frameRecommitted = true
        _readyState.value = false
        Handler(Looper.getMainLooper()).post { webView?.let { endSession(it) } }
        return false
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun frameMayReadScripts() = denyConstruction && !frameRecommitted

    internal fun readFrameBootstrap(): String =
        if (frameMayReadScripts()) readAsset(DENY_FRAME_BOOTSTRAP_ASSET) else ""

    internal fun readStartupScript(): String =
        if (frameMayReadScripts()) readAsset(STARTUP_SCRIPT_ASSET) else ""

    // The sourceURL line keeps the file name in stack traces, which console logging reads.
    internal fun readAppScript(): String =
        if (frameMayReadScripts()) {
            val file = File(jsPath.toString())
            file.readText() + "\n//# sourceURL=${file.name}"
        } else {
            ""
        }

    internal fun onFrameEvalResult(id: String, json: String) {
        frameEvalResults.remove(id)?.complete(json)
    }

    // Main thread only. In a deny session the top document parses only this fixed call; the
    // payload is a string to it, and the host page forwards it to the frame.
    private fun evaluateInPage(js: String) {
        if (denyConstruction) {
            webView?.evaluateJavascript("__pkjsHost.run(${Json.encodeToString(js)})", null)
        } else {
            webView?.evaluateJavascript(js, null)
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
        logger.d { "WebView initialized (provider=${webViewProvider()}, multiProcess=${multiProcessState()})" }

        // Resolve the app's Network grant and put the enforcement layers in place
        // BEFORE any app page/script loads, so there is no window in which a denied
        // app can reach the network. The initial value gates layers 1 (intercept) and
        // 2 (JS shim); the proxy (layer 3) is applied and awaited here too.
        val uuid = Uuid.parse(appInfo.uuid)
        networkAllowed = watchappPermissions.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network)
        denyConstruction = !networkAllowed
        // CompanionAppLifecycleManager decides the same from earlier reads of the grant and the
        // switch; these are the reads the session is built from.
        val switchOn = libPebble.config.value.watchConfig.deniedPkjsWithoutPrimaryLayer
        if (!shouldRunPkjs(hasPkjs = true, networkAllowed, networkDenyEnforcement, switchOn)) {
            logger.w { "Not loading ${appInfo.longName} (${appInfo.uuid}): Network is denied, the primary deny layer is not active and the switch is off or does not apply" }
            return
        }
        // Below the gate's return, so a session refused there installs no override
        // (WebViewJsRunnerStartOrderSentinelTest).
        applyNetworkProxy(networkAllowed)
        if (denyConstruction) {
            withContext(Dispatchers.Main) {
                // Nothing in the deny construction uses a file URL.
                webView?.settings?.apply {
                    allowFileAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                }
                // The view is never attached to a window, so it gets its size here. Without one
                // the frame's timers fall behind their interval
                // (PKJSDenyConstructionTest.frameTimersRunAtTheirInterval).
                webView?.apply {
                    measure(
                        View.MeasureSpec.makeMeasureSpec(DENY_VIEW_WIDTH_PX, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(DENY_VIEW_HEIGHT_PX, View.MeasureSpec.EXACTLY),
                    )
                    layout(0, 0, DENY_VIEW_WIDTH_PX, DENY_VIEW_HEIGHT_PX)
                }
            }
        }
        // Track live toggles: a change in the resolved grant (per-app override or the
        // global default) re-caches the value and re-applies/clears the proxy.
        networkPermissionCollector = scope.launch {
            watchappPermissions.watchappPermissionGranted(uuid, LockerAppPermissionType.Network)
                .collect { allowed ->
                    if (allowed != networkAllowed) {
                        logger.d { "Network grant for $uuid changed -> $allowed" }
                    }
                    networkAllowed = allowed
                    applyNetworkProxy(allowed)
                    if (!allowed && !denyConstruction) endGrantedSessionOnDenial()
                }
        }

        loadApp(jsPath.toString())
        scope.launch {
            if (withTimeoutOrNull(PAGE_LOAD_TIMEOUT) { pageLoaded.await() } == null &&
                synchronized(initializedLock) { webView != null }
            ) {
                logger.e {
                    "Startup page never loaded (provider=${webViewProvider()}): PKJS for " +
                            "${appInfo.longName} will never become ready"
                }
            }
        }
    }

    /**
     * Layer 3 of the network gate: a process-wide WebView proxy override that
     * black-holes all egress (every scheme, including ws/wss that shouldInterceptRequest
     * cannot see) when the running app's network is denied, and is cleared when allowed.
     *
     * The override applies to every WebView in the app (androidx.webkit 1.16.0,
     * ProxyController.setProxyOverride), so while the black-hole is active it also covers a
     * page left open for another app. Only one PKJS WebView runs at a time, and the developer
     * config page is gated for network-denied apps. Requires the
     * PROXY_OVERRIDE WebView feature; when unsupported, layers 1 and 2 still apply, and
     * WebSocket and WebRTC over TCP lose this cover (recorded in KNOWN_ISSUES).
     */
    private suspend fun applyNetworkProxy(allowed: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (!allowed) {
                logger.w { "PROXY_OVERRIDE unsupported; WebSocket and WebRTC over TCP deny is best-effort for this app" }
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
    private fun webViewProvider(): String = WebView.getCurrentWebViewPackage()
        ?.let { "${it.packageName} ${it.versionName}" } ?: "none"

    // A single-process WebView has no separate renderer, so onRenderProcessGone cannot fire there.
    private fun multiProcessState(): String =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROCESS)) {
            WebViewCompat.isMultiProcessEnabled().toString()
        } else {
            "unknown"
        }

    // Called on the main thread. With no WebView left nothing would resume the wait, so it is
    // skipped; onRenderProcessGone also releases a wait that is already parked. Bounded, because
    // a page can keep its main thread busy for as long as it likes. The script clears only the
    // page's storage, so the native store changes only in saveState's single call.
    private suspend fun persistLocalStorage() {
        val view = webView
        if (rendererGone || view == null) {
            logger.d { "Skipping persistLocalStorage: no usable WebView" }
            return
        }
        val saved = withTimeoutOrNull(PERSIST_TIMEOUT) {
            suspendCancellableCoroutine { cont ->
                persistWait = cont
                view.evaluateJavascript("""
                    (function() {
                        const data = {};
                        for (let i = 0; i < window.localStorage.length; i++) {
                            const key = window.localStorage.key(i);
                            const value = window.localStorage.getItem(key);
                            data[key] = value;
                        }
                        Storage.prototype.clear.call(window.localStorage);
                        window._localStorage.saveState(JSON.stringify(data));
                    })();
                        """.trimIndent()
                ) {
                    releasePersistWait()
                }
            }
        }
        if (saved == null) {
            persistWait = null
            logger.w { "localStorage save for ${appInfo.uuid} did not finish within $PERSIST_TIMEOUT" }
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
                // A deny session writes every change through as it happens and has no copy step.
                if (denyConstruction) {
                    logger.d { "Skipping persistLocalStorage: deny session" }
                } else if (restoreCompleted) {
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

    // A session built as granted has no sandboxed frame and no response header, so a denial ends
    // it, as a renderer exit does, without waiting for the lifecycle's restart, which does not come
    // when the lifecycle's grant watcher never sees the grant differ from the value it started
    // from. The save runs after the collector's proxy call. Must stay NonCancellable: stop()
    // cancels the collector and then saves too, and a second save would store the localStorage
    // this one emptied.
    private suspend fun endGrantedSessionOnDenial() {
        withContext(NonCancellable + Dispatchers.Main) {
            if (webView == null) return@withContext
            logger.w { "Network grant for ${appInfo.longName} (${appInfo.uuid}) denied in a session built as granted; ending the session" }
            ending = true
            _readyState.value = false
            if (restoreCompleted) persistLocalStorage()
            webView?.let { endSession(it) }
        }
    }

    private suspend fun loadApp(url: String) {
        // endGrantedSessionOnDenial can end the session before this runs.
        if (webView == null) return
        withContext(Dispatchers.Main) {
            if (denyConstruction) {
                webView?.loadUrl(DENY_HOST_PAGE_URL)
                return@withContext
            }
            webView?.loadUrl(
                STARTUP_URL.toUri().buildUpon()
                    .appendQueryParameter("params", "{\"loadUrl\": \"$url\"}")
                    .build()
                    .toString()
            )
        }
    }

    @VisibleForTesting
    internal suspend fun evalInTopDocumentForTest(js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView?.evaluateJavascript(js) { cont.resume(it) }
                ?: cont.resumeWithException(IllegalStateException("WebView not initialized"))
        }
    }

    @VisibleForTesting
    internal suspend fun webViewSettingsForTest(): WebSettings? = withContext(Dispatchers.Main) {
        webView?.settings
    }

    @VisibleForTesting
    internal suspend fun loadUrlForTest(url: String) = withContext(Dispatchers.Main) {
        webView?.loadUrl(url)
    }

    override suspend fun loadAppJs(jsUrl: String) {
        webView?.let { webView ->
            if (!denyConstruction) restoreLocalStorage()

            if (jsUrl.isBlank() || !jsUrl.endsWith(".js")) {
                logger.e { "loadUrl passed to loadAppJs empty or invalid" }
                return
            }

            if (denyConstruction) {
                withContext(Dispatchers.Main) {
                    evaluateInPage(LOAD_APP_SCRIPT_IN_FRAME)
                    webView.evaluateJavascript("document.title = ${Json.encodeToString("PKJS: ${appInfo.longName}")};", null)
                }
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
        } ?: logger.w { "Not loading the app script: the session has ended" }
    }

    override suspend fun signalInterceptResponse(callbackId: String, result: InterceptResponse) {
        val jsonString = buildJsonObject {
            put("callbackId", callbackId)
            put("response", result.result)
            put("status", result.status)
        }.toString()
        withContext(Dispatchers.Main) {
            // No Json.encodeToString here, we want the raw object {} in the JS call
            evaluateInPage("window.signalInterceptResponse($jsonString)")
        }
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to token, "callId" to callId))
        withContext(Dispatchers.Main) {
            evaluateInPage("window.signalTimelineTokenSuccess(${Json.encodeToString(tokenJson)})")
        }
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to null, "callId" to callId))
        withContext(Dispatchers.Main) {
            evaluateInPage("window.signalTimelineTokenFailure(${Json.encodeToString(tokenJson)})")
        }
    }

    override suspend fun signalReady() {
        val readyDeviceIds = listOf(device.identifier.asString)
        val readyJson = Json.encodeToString(readyDeviceIds)
        withContext(Dispatchers.Main) {
            evaluateInPage("window.signalReady(${readyJson})")
            markReadyUnlessEnded()
        }
    }

    // Called on the JavaScript bridge thread, by any script in the session.
    override fun onReadyConfirmed(success: Boolean) {
        Handler(Looper.getMainLooper()).post { markReadyUnlessEnded() }
    }

    // Main thread only, where endSession clears the view and endGrantedSessionOnDenial sets ending:
    // a session endSession has ended, or endGrantedSessionOnDenial is ending, is not marked ready.
    private fun markReadyUnlessEnded() {
        if (webView != null && !ending) _readyState.value = true
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        readyState.first { it }
        withContext(Dispatchers.Main) {
            evaluateInPage("window.signalNewAppMessageData(${data?.let { Json.encodeToString(data) } ?: "null"})")
        }
        return true
    }

    override suspend fun signalShowConfiguration() {
        readyState.first { it }
        withContext(Dispatchers.Main) {
            evaluateInPage("window.signalShowConfiguration()")
        }
    }

    override suspend fun signalWebviewClosed(data: String?) {
        withContext(Dispatchers.Main) {
            evaluateInPage("window.signalWebviewClosedEvent(${Json.encodeToString(data)})")
        }
    }

    override suspend fun eval(js: String) {
        withContext(Dispatchers.Main) {
            if (webView == null) {
                logger.e { "WebView not initialized, cannot evaluate JS" }
            }
            evaluateInPage(js)
        }
    }

    override suspend fun evalWithResult(js: String): Any? {
        if (denyConstruction) return evalInFrameWithResult(js)
        return withContext(Dispatchers.Main) {
            return@withContext suspendCancellableCoroutine { cont ->
                webView?.evaluateJavascript(js) { result ->
                    cont.resume(result)
                } ?: cont.resumeWithException(IllegalStateException("WebView not initialized"))
            }
        }
    }

    // The frame cannot answer evaluateJavascript's callback, so the result comes back over the
    // bridge as JSON, in the same form the callback gives.
    private suspend fun evalInFrameWithResult(js: String): String {
        val id = Uuid.random().toString()
        val result = CompletableDeferred<String>()
        frameEvalResults[id] = result
        try {
            withContext(Dispatchers.Main) {
                check(webView != null) { "WebView not initialized" }
                evaluateInPage(
                    """
                    (function () {
                        var json = "null";
                        try {
                            var value = (0, eval)(${Json.encodeToString(js)});
                            if (value !== undefined) json = JSON.stringify(value) || "null";
                        } finally {
                            _Pebble.onFrameEvalResult(${Json.encodeToString(id)}, json);
                        }
                    })();
                    """.trimIndent()
                )
            }
            return withTimeoutOrNull(FRAME_EVAL_TIMEOUT) { result.await() }
                ?: error("No result from the frame within $FRAME_EVAL_TIMEOUT")
        } finally {
            frameEvalResults.remove(id)
        }
    }

    override fun debugForceGC() {
        // No-op on Android
    }
}

/** Which observer reported that the deny session's frame loaded a document. */
internal enum class FrameDocumentSignal { Bootstrap, HostLoadEvent }
