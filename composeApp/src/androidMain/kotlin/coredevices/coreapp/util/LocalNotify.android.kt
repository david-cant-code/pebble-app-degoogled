package coredevices.coreapp.util

import PlatformContext
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import coredevices.coreapp.MainActivity
import coredevices.util.R

private const val CHANNEL_ID = "app_notices"
private const val NOTIFICATION_ID = 3006090

actual fun notifyLocal(platformContext: PlatformContext, title: String, message: String) {
    val context = platformContext.context
    val channel = NotificationChannel(
        CHANNEL_ID,
        "App notices",
        NotificationManager.IMPORTANCE_DEFAULT
    )
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)

    val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        pendingIntentFlags
    )

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()
    try {
        manager.notify(NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
        // POST_NOTIFICATIONS can be denied; the nudge is best-effort.
        Logger.withTag("LocalNotify").w(e) { "Notification blocked" }
    }
}
