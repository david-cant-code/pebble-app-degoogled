package coredevices.coreapp.ui.navigation

import CommonRoutes
import CoreRoute
import androidx.navigation.NavUri
import co.touchlab.kermit.Logger
import com.eygraber.uri.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class CoreDeepLinkHandler {
    private val _navigateToDeepLink = MutableSharedFlow<Any>(extraBufferCapacity = 1, replay = 1)
    val navigateToDeepLink = _navigateToDeepLink.asSharedFlow()

    // The upstream ring-route parsing (pebblecore://deep-link/object and
    // /recording, minting typed RingRoutes) is removed with the ring feature:
    // those routes are no longer registered in the nav graph, and emitting an
    // unregistered typed route from an externally fireable VIEW intent would
    // crash the nav host.
    fun handle(uri: Uri): Boolean {
        logger.d { "handle: uri = $uri" }
        return _navigateToDeepLink.tryEmit(NavUri(uri.toString()))
    }

    fun clearPendingDeepLink() {
        _navigateToDeepLink.resetReplayCache()
    }

    companion object {
        private val logger = Logger.withTag("CoreDeepLinkHandler")
        private const val SCHEME = "pebblecore"
        private const val HOST = "deep-link"
        private const val VIEW_BUG_REPORT_PATH = "view-bug-report"
        private const val CONVERSATION_ID_QUERY_PARAM = "conversationId"

        fun CommonRoutes.ViewBugReportRoute.asUri(): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendPath(VIEW_BUG_REPORT_PATH)
            .appendQueryParameter(CONVERSATION_ID_QUERY_PARAM, conversationId)
            .build()
    }
}