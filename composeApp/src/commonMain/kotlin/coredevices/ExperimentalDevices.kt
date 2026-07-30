@file:Suppress("RedundantSuspendModifier", "UNUSED_PARAMETER", "unused")

package coredevices

import CoreNav
import DocumentAttachment
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import com.eygraber.uri.Uri
import coredevices.pebble.ui.TopBarParams
import kotlinx.io.files.Path

/**
 * Fork-owned no-op replacement for the class of the same name in the
 * unplugged :experimental module (the Ring/Index feature facade). It keeps
 * the upstream call sites in MainApplication, MainActivity,
 * CommonAppDelegate, AppNavHost, BugReportProcessor, and logging.kt
 * compiling byte-identically while the ring feature stays out of the build.
 * If an upstream merge ever re-plugs :experimental, the duplicate class
 * name in package `coredevices` fails the build loudly, which is the
 * intended tripwire.
 */
class ExperimentalDevices {
    fun appInit() {}

    suspend fun init() {}

    fun onBackgroundSync() {}

    fun handleDeepLink(uri: Uri): Boolean = false

    fun addExperimentalRoutes(builder: NavGraphBuilder, coreNav: CoreNav) {}

    @Composable
    fun IndexScreen(coreNav: CoreNav, topBarParams: TopBarParams) {
    }

    fun badCollectionsDir(): Path? = null

    suspend fun exportOutput(id: String): List<DocumentAttachment> = emptyList()

    suspend fun exportRecentRecordings(limit: Int = 10): List<DocumentAttachment> = emptyList()

    fun debugSummary(): String = ""
}
