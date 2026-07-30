package coredevices.coreapp.util

import PlatformContext

/**
 * Fork-owned local-notification helper replacing the kmpnotifier local
 * notifier, which left the build with the FCM push stack (the library
 * hard-depends on firebase-messaging). Modeled on the existing platform
 * notification code in BugReports.android.kt. Android-only, like the rest
 * of this fork; no iOS actual is provided.
 */
expect fun notifyLocal(platformContext: PlatformContext, title: String, message: String)
