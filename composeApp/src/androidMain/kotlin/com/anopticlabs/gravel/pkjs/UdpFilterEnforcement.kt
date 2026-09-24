package com.anopticlabs.gravel.pkjs

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.socketfilter.InstallResult
import java.io.File
import java.io.IOException

private const val INSTALLED_MARKER = "udp-filter-installed"

/** Records an Installed [result] in [noBackupDir], refreshing the record's modification time. */
fun recordUdpFilterInstall(result: InstallResult?, noBackupDir: File) {
    if (result != InstallResult.Installed) return
    val marker = File(noBackupDir, INSTALLED_MARKER)
    try {
        marker.createNewFile()
    } catch (e: IOException) {
        logger.w(e) { "Could not record that the UDP filter installed" }
        return
    }
    if (!marker.setLastModified(System.currentTimeMillis())) {
        logger.w { "Could not refresh the record of the UDP filter install" }
    }
}

/**
 * What the UDP socket filter's install [result] means for Network-denied sessions. The switch for
 * them applies only where no install is recorded in [noBackupDir] ([recordUdpFilterInstall]) and
 * the WebView's major version, from [webViewMajor] (null when unknown), carries the header layer.
 * The version is read at each check, so the runner's check, made after its WebView is created,
 * reads the provider that session loaded (android16-release, WebView.getCurrentWebViewPackage).
 */
fun udpFilterEnforcement(result: InstallResult?, noBackupDir: File, webViewMajor: () -> Int?): NetworkDenyEnforcement {
    if (result == InstallResult.Installed) return FixedNetworkDenyEnforcement(primaryLayerActive = true)
    val recorded = File(noBackupDir, INSTALLED_MARKER).exists()
    return object : NetworkDenyEnforcement {
        override val primaryLayerActive = false
        override val switchMayRunDeniedPkjs: Boolean
            get() = !recorded && (webViewMajor() ?: 0) >= HEADER_MIN_WEBVIEW_MAJOR
    }
}

private val logger = Logger.withTag("UdpFilterEnforcement")
