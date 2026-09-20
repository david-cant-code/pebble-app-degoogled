package com.anopticlabs.gravel.pkjs

import android.webkit.WebResourceResponse
import java.io.InputStream

/**
 * The host page of a network-denied PebbleKit JS session: the only URL its interceptor serves
 * while the grant stays denied. `.invalid` never resolves (RFC 2606).
 */
const val DENY_HOST_PAGE_SCHEME = "https"
const val DENY_HOST_PAGE_HOST = "pkjs.gravel.invalid"
const val DENY_HOST_PAGE_PATH = "/host.html"
const val DENY_HOST_PAGE_URL = "$DENY_HOST_PAGE_SCHEME://$DENY_HOST_PAGE_HOST$DENY_HOST_PAGE_PATH"

const val CONNECTION_ALLOWLIST_HEADER = "Connection-Allowlist"

// One inner list with no URL patterns, and WebRTC blocked (Chromium M153,
// refs/branch-heads/8010, connection_allowlist_parser.cc, ParseConnectionAllowlist). A WebView
// that does not know the header ignores it.
const val CONNECTION_ALLOWLIST_DENY_ALL = "();webrtc=block"

const val BLOCK_REASON_FORBIDDEN = "Forbidden"
const val BLOCK_REASON_NETWORK_DENIED = "Network access denied for this app"

sealed interface PkjsRequestPlan {
    data object ServeHostPage : PkjsRequestPlan
    data class Block(val reason: String) : PkjsRequestPlan
    data object PassThrough : PkjsRequestPlan
}

/**
 * What the runner's request interceptor does with one request. [denyConstruction] is fixed for
 * the session; [networkAllowed] is the live grant. A null [scheme] stands for a null URL.
 */
fun planPkjsRequest(
    denyConstruction: Boolean,
    networkAllowed: Boolean,
    scheme: String?,
    host: String?,
    path: String?,
    isForMainFrame: Boolean,
    appJsPath: String,
): PkjsRequestPlan {
    if (scheme == null) return PkjsRequestPlan.Block(BLOCK_REASON_FORBIDDEN)
    val isFile = scheme.equals("file", ignoreCase = true)
    if (denyConstruction) {
        val isHostPage = scheme == DENY_HOST_PAGE_SCHEME && host == DENY_HOST_PAGE_HOST &&
                path == DENY_HOST_PAGE_PATH
        if (isHostPage && isForMainFrame) return PkjsRequestPlan.ServeHostPage
        // Nothing in the deny construction loads by URL, so its own origin and file: get nothing else.
        if (isHostPage || isFile) return PkjsRequestPlan.Block(BLOCK_REASON_FORBIDDEN)
    } else if (isFile) {
        return if (path.equals(appJsPath, ignoreCase = true)) {
            PkjsRequestPlan.PassThrough
        } else {
            PkjsRequestPlan.Block(BLOCK_REASON_FORBIDDEN)
        }
    }
    return if (networkAllowed) PkjsRequestPlan.PassThrough else PkjsRequestPlan.Block(BLOCK_REASON_NETWORK_DENIED)
}

/** Headers for every response the app itself produces in a session of the given construction. */
fun pkjsResponseHeaders(denyConstruction: Boolean): Map<String, String> =
    if (denyConstruction) {
        mapOf(
            CONNECTION_ALLOWLIST_HEADER to CONNECTION_ALLOWLIST_DENY_ALL,
            "Cache-Control" to "no-store",
        )
    } else {
        emptyMap()
    }

/**
 * The response for a plan, with the headers of the session's construction. Null means the WebView
 * loads the request itself.
 */
fun PkjsRequestPlan.toWebResourceResponse(
    denyConstruction: Boolean,
    openHostPage: () -> InputStream,
): WebResourceResponse? {
    val headers = pkjsResponseHeaders(denyConstruction)
    return when (this) {
        PkjsRequestPlan.ServeHostPage ->
            WebResourceResponse("text/html", "utf-8", 200, "OK", headers, openHostPage())
        is PkjsRequestPlan.Block ->
            WebResourceResponse("text/plain", "utf-8", 403, reason, headers, null)
        PkjsRequestPlan.PassThrough -> null
    }
}
