package com.anopticlabs.gravel.pkjs

import android.webkit.WebView

/**
 * The WebView version from which the denied-session page's Connection-Allowlist header is on
 * by default (KNOWN_ISSUES.md, "The WebRTC response-header layer needs WebView 152 or newer").
 * Below it the switch would leave WebRTC over UDP with no layer at all.
 */
const val HEADER_MIN_WEBVIEW_MAJOR = 152

/** The current WebView provider's major version; null when unknown. */
fun webViewMajorVersion(): Int? = webViewMajor(WebView.getCurrentWebViewPackage()?.versionName)

/** The major version in a WebView versionName such as "153.0.8010.36"; null when it has none. */
fun webViewMajor(versionName: String?): Int? = versionName?.substringBefore('.')?.toIntOrNull()
