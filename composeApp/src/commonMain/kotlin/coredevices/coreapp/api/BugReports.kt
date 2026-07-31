package coredevices.coreapp.api

import PlatformContext
import co.touchlab.kermit.Logger
import coredevices.util.emailOrNull
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BugReports(
    private val platformContext: PlatformContext,
    private val bugReportsService: BugReportsService,
) {
    private val logger = Logger.withTag("BugReports")
    private val _ticketDetails = MutableStateFlow<BugReportsListResponse?>(null)
    val ticketDetails: StateFlow<BugReportsListResponse?> = _ticketDetails.asStateFlow()

    fun init() {
        GlobalScope.launch {
            Firebase.auth.idTokenChanged.collect {
                refresh()
            }
        }
    }

    // The upstream handlePushMessage entry point left with the FCM push
    // stack; nothing can deliver an atlas_message without it. The
    // createNotification expect/actual it called stays for cheap merges.
    suspend fun refresh() {
        logger.d { "refresh" }

        // Get Google ID token for authentication
        val idToken = try {
            Firebase.auth.currentUser?.getIdToken(false)
        } catch (e: Exception) {
            logger.e(e) { "Failed to get ID token" }
            null
        }
        if (idToken == null) {
            logger.i { "Not logged in, can't fetch bug reports" }
            _ticketDetails.value = null
            return
        }

        val reports = fetchBugReports(idToken)
        if (reports != null) {
            _ticketDetails.value = reports
        }
    }

    private suspend fun fetchBugReports(idToken: String): BugReportsListResponse? {
        // TODO timeout
        try {
            val userEmail = Firebase.auth.currentUser?.emailOrNull
            if (userEmail == null) {
                logger.w { "Not logged in!" }
                return null
            }
            logger.d { "Starting to load bug reports" }

            val response = bugReportsService.fetchBugReports(userEmail, idToken)
            if (response?.success == true) {
                return response
            } else {
                // Clear existing data when request fails
                logger.e { "Failed to load bug reports: response was null or not successful" }
                return null
            }
        } catch (e: Exception) {
             logger.e(e) { "Exception while loading bug reports" }
            return null
        }
    }
}

expect fun createNotification(
    platformContext: PlatformContext,
    title: String,
    message: String,
    conversationId: String,
)