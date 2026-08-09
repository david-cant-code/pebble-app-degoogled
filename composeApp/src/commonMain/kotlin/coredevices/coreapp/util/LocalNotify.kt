package coredevices.coreapp.util

import PlatformContext

/**
 * Fork-owned local-notification helper replacing the kmpnotifier local
 * notifier, which left the build with the FCM push stack (the library
 * hard-depends on firebase-messaging). Modeled on the existing platform
 * notification code in BugReports.android.kt. Android-only, like the rest
 * of this fork; no iOS actual is provided.
 */
expect fun notifyLocal(platformContext: PlatformContext, id: Int, title: String, message: String)

/**
 * Removes a notification previously posted with [notifyLocal] under the same
 * [id]. Added when upstream started dismissing the STT-model-update nag once
 * the user begins the download; the upstream call goes through kmpnotifier's
 * local notifier, this fork routes it through the same seam as [notifyLocal].
 * Cancelling an id that is not currently shown is a harmless no-op.
 */
expect fun cancelNotifyLocal(platformContext: PlatformContext, id: Int)
